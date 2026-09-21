package me.woelki.frad.crypto.noise

/** Noise spec section 5.1: a single directional key + nonce counter. */
internal class CipherState {
    private var key: ByteArray? = null
    private var n: Long = 0

    fun initializeKey(key: ByteArray) {
        this.key = key
        this.n = 0
    }

    fun hasKey(): Boolean = key != null

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        val ciphertext = Primitives.encrypt(k, n, ad, plaintext)
        n++
        return ciphertext
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        val plaintext = Primitives.decrypt(k, n, ad, ciphertext)
        n++
        return plaintext
    }
}
