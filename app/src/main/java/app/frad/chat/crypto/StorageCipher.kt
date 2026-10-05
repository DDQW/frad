package app.frad.chat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts what FRAD keeps on disk (the identity key, saved chat history) with an AES-256-GCM key
 * that lives in the Android Keystore - it never exists in app memory or files, so a copy of the
 * app's data directory (a rooted phone's backup, a forensic dump, a device-to-device transfer) is
 * useless without the phone it came from.
 *
 * Values are `"k1:" + base64(iv || ciphertext+tag)`; the prefix lets callers tell sealed values
 * from data stored before this existed, and leaves room for a future key/format.
 */
class SealedBox(private val key: SecretKey) {
    fun seal(plain: ByteArray): String {
        // The cipher picks the IV itself: Keystore keys refuse a caller-chosen one for encryption.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV length ${iv.size}" }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain))
    }

    /** @throws java.security.GeneralSecurityException if [sealed] was tampered with or sealed
     *  under another key; [IllegalArgumentException] if it isn't a sealed value at all. */
    fun open(sealed: String): ByteArray {
        require(isSealed(sealed)) { "Not a sealed value" }
        val raw = Base64.getDecoder().decode(sealed.substring(PREFIX.length))
        require(raw.size > IV_BYTES) { "Sealed value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        return cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
    }

    fun sealString(plain: String): String = seal(plain.toByteArray(Charsets.UTF_8))

    fun openString(sealed: String): String = String(open(sealed), Charsets.UTF_8)

    companion object {
        private const val PREFIX = "k1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        fun isSealed(value: String): Boolean = value.startsWith(PREFIX)
    }
}

/** The app-wide [SealedBox] over the Keystore key - created on first use, never exported. */
object StorageCipher {
    private const val KEY_ALIAS = "frad_storage_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    val box: SealedBox by lazy { SealedBox(loadOrCreateKey()) }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
