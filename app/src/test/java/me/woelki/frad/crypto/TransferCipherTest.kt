package me.woelki.frad.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferCipherTest {

    private fun randomKey(): ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    @Test
    fun `a chunk round trips through independent sender and receiver instances sharing a key`() {
        val key = randomKey()
        val sender = TransferCipher(key)
        val receiver = TransferCipher(key)

        val plaintext = "part of a file".toByteArray()
        val ciphertext = sender.encryptChunk(plaintext)
        assertArrayEquals(plaintext, receiver.decryptChunk(ciphertext))
    }

    @Test
    fun `consecutive chunks keep round tripping as the nonce counter advances`() {
        val key = randomKey()
        val sender = TransferCipher(key)
        val receiver = TransferCipher(key)

        val chunks = listOf("first chunk", "second chunk", "third chunk").map { it.toByteArray() }
        chunks.forEach { plaintext ->
            assertArrayEquals(plaintext, receiver.decryptChunk(sender.encryptChunk(plaintext)))
        }
    }

    @Test
    fun `tampered ciphertext fails authentication instead of decrypting garbage`() {
        val key = randomKey()
        val sender = TransferCipher(key)
        val receiver = TransferCipher(key)

        val ciphertext = sender.encryptChunk("sensitive chunk".toByteArray())
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()

        assertThrows(InvalidCipherTextException::class.java) {
            receiver.decryptChunk(ciphertext)
        }
    }

    @Test
    fun `a different key cannot decrypt another transfer's chunks`() {
        val sender = TransferCipher(randomKey())
        val receiver = TransferCipher(randomKey())

        val ciphertext = sender.encryptChunk("hello".toByteArray())
        assertThrows(InvalidCipherTextException::class.java) {
            receiver.decryptChunk(ciphertext)
        }
    }

    @Test
    fun `two independent keys yield unlinkable ciphertexts for the same plaintext`() {
        val first = TransferCipher(randomKey()).encryptChunk("same plaintext".toByteArray())
        val second = TransferCipher(randomKey()).encryptChunk("same plaintext".toByteArray())
        assertNotEquals(String(first), String(second))
    }
}
