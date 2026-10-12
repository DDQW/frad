package app.frad.chat.crypto

import java.security.SecureRandom
import java.util.Base64
import app.frad.chat.crypto.noise.CipherState

/**
 * Encrypts/decrypts a file transfer as a sequence of independently authenticated chunks,
 * keyed by [ChatSession.deriveTransferKey]. One instance is one-directional and single-use
 * per transfer — and so is its key, since the nonce counter always starts at 0 (see
 * [newTransferId]). The sender's [encryptChunk] and the receiver's [decryptChunk] must be called
 * in the same order the chunks are sent, since the underlying nonce counter advances by one
 * per call on both ends (same discipline [CipherState] already enforces for chat messages).
 */
class TransferCipher(key: ByteArray) {
    private val cipher = CipherState().apply { initializeKey(key) }

    fun encryptChunk(plaintext: ByteArray): ByteArray = cipher.encryptWithAd(EMPTY, plaintext)

    /** @throws org.bouncycastle.crypto.InvalidCipherTextException on authentication failure. */
    fun decryptChunk(ciphertext: ByteArray): ByteArray = cipher.decryptWithAd(EMPTY, ciphertext)

    companion object {
        private val EMPTY = ByteArray(0)
        private const val TRANSFER_ID_BYTES = 16
        private val secureRandom = SecureRandom()

        /** A fresh random id for one file transfer, sent along in its offer and mixed into
         *  [ChatSession.deriveTransferKey] so no two transfers ever share a key. */
        fun newTransferId(): String {
            val bytes = ByteArray(TRANSFER_ID_BYTES).also { secureRandom.nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
