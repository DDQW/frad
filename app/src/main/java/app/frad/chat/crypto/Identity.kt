package app.frad.chat.crypto

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.util.Base64
import app.frad.chat.crypto.noise.Primitives

/**
 * The device's long-term X25519 keypair. It is generated once on first run and
 * kept sealed with a Keystore key ([StorageCipher]) — there is no account, phone
 * number or email tied to it.
 *
 * This key is deliberately never broadcast during BLE/DHT presence discovery
 * (see the `ble` and `pairing` packages, which use short-lived rotating session
 * ids instead). It only becomes known to a peer once a chat's Noise_XX handshake
 * completes with them specifically, which is what makes [peerId] meaningful as a
 * per-conversation "who am I blocking" identifier without it enabling passive
 * tracking by anyone merely scanning for nearby devices.
 */
class Identity private constructor(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    /** Stable id derived from the public key, safe to show/store locally for blocking. */
    val peerId: String
        get() = Base64.getUrlEncoder().withoutPadding().encodeToString(Primitives.sha256(publicKey))

    companion object {
        private const val PREFS_FILE = "frad_identity_v2"
        private const val KEY_PRIVATE = "static_private_key" // sealed with StorageCipher
        private const val KEY_PUBLIC = "static_public_key"

        // Where versions up to 0.3.31 kept the keypair (androidx.security's now-deprecated
        // EncryptedSharedPreferences); moved over on first start, then deleted.
        private const val LEGACY_PREFS_FILE = "frad_identity"
        private const val LEGACY_KEYSET_PREFS_FILE = "__androidx_security_crypto_encrypted_prefs__"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        private const val TAG = "Identity"

        @Volatile private var loaded: Identity? = null

        /** One instance per process: the BLE service and the UI both ask for it, and on a
         *  first start two unsynchronised calls would each generate - and store - a keypair. */
        fun loadOrCreate(context: Context): Identity =
            loaded ?: synchronized(this) { loaded ?: loadOrCreateLocked(context.applicationContext).also { loaded = it } }

        private fun loadOrCreateLocked(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            val sealedPrivate = prefs.getString(KEY_PRIVATE, null)
            val storedPublic = prefs.getString(KEY_PUBLIC, null)
            if (sealedPrivate != null && storedPublic != null) {
                try {
                    return Identity(StorageCipher.box.open(sealedPrivate), Base64.getDecoder().decode(storedPublic))
                } catch (e: Exception) {
                    // The Keystore key is gone (e.g. app data copied to another phone): the old
                    // identity can't be recovered, and refusing to start wouldn't bring it back.
                    Log.e(TAG, "Stored identity unreadable - creating a new one", e)
                }
            }

            val legacy = readLegacy(context)
            val (privateKey, publicKey) = legacy ?: Primitives.generateKeyPair()
            val saved = prefs.edit()
                .putString(KEY_PRIVATE, StorageCipher.box.seal(privateKey))
                .putString(KEY_PUBLIC, Base64.getEncoder().encodeToString(publicKey))
                .commit()
            if (legacy != null && saved) deleteLegacy(context)
            return Identity(privateKey, publicKey)
        }

        private fun readLegacy(context: Context): Pair<ByteArray, ByteArray>? {
            if (!File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_FILE.xml").exists()) return null
            return try {
                val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                @Suppress("DEPRECATION")
                val prefs = EncryptedSharedPreferences.create(
                    context,
                    LEGACY_PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
                val private = prefs.getString(KEY_PRIVATE, null) ?: return null
                val public = prefs.getString(KEY_PUBLIC, null) ?: return null
                Base64.getDecoder().decode(private) to Base64.getDecoder().decode(public)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't read the identity stored by an older version", e)
                null
            }
        }

        private fun deleteLegacy(context: Context) {
            context.deleteSharedPreferences(LEGACY_PREFS_FILE)
            context.deleteSharedPreferences(LEGACY_KEYSET_PREFS_FILE)
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LEGACY_MASTER_KEY_ALIAS) }
        }

        /** For tests: builds an Identity from a raw keypair without touching Android storage. */
        internal fun fromRawKeyPair(privateKey: ByteArray, publicKey: ByteArray): Identity =
            Identity(privateKey, publicKey)
    }
}
