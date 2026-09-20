package me.woelki.friendradar.crypto

import me.woelki.friendradar.crypto.noise.CipherState

/**
 * Encrypts/decrypts a file transfer as a sequence of independently authenticated chunks,
 * keyed by [ChatSession.deriveTransferKey]. One instance is one-directional and single-use
 * per transfer — the sender's [encryptChunk] and the receiver's [decryptChunk] must be called
 * in the same order the chunks are sent, since the underlying nonce counter advances by one
 * per call on both ends (same discipline [CipherState] already enforces for chat messages).
 */
class TransferCipher(key: ByteArray) {
    private val cipher = CipherState().apply { initializeKey(key) }

    fun encryptChunk(plaintext: ByteArray): ByteArray = cipher.encryptWithAd(EMPTY, plaintext)

    /** @throws org.bouncycastle.crypto.InvalidCipherTextException on authentication failure. */
    fun decryptChunk(ciphertext: ByteArray): ByteArray = cipher.decryptWithAd(EMPTY, ciphertext)

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
