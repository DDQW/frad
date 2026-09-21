package me.woelki.frad.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Base64
import me.woelki.frad.crypto.noise.Primitives

/**
 * The device's long-term X25519 keypair. It is generated once on first run and
 * kept in Keystore-backed encrypted storage — there is no account, phone number
 * or email tied to it.
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
        private const val PREFS_FILE = "frad_identity"
        private const val KEY_PRIVATE = "static_private_key"
        private const val KEY_PUBLIC = "static_public_key"

        fun loadOrCreate(context: Context): Identity {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            val storedPrivate = prefs.getString(KEY_PRIVATE, null)
            val storedPublic = prefs.getString(KEY_PUBLIC, null)
            if (storedPrivate != null && storedPublic != null) {
                return Identity(
                    privateKey = Base64.getDecoder().decode(storedPrivate),
                    publicKey = Base64.getDecoder().decode(storedPublic),
                )
            }

            val (priv, pub) = Primitives.generateKeyPair()
            prefs.edit()
                .putString(KEY_PRIVATE, Base64.getEncoder().encodeToString(priv))
                .putString(KEY_PUBLIC, Base64.getEncoder().encodeToString(pub))
                .apply()
            return Identity(priv, pub)
        }

        /** For tests: builds an Identity from a raw keypair without touching Android storage. */
        internal fun fromRawKeyPair(privateKey: ByteArray, publicKey: ByteArray): Identity =
            Identity(privateKey, publicKey)
    }
}
