package app.frad.chat.crypto

import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkedTransferTest {

    private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** A byte pipe backed by an in-memory queue, standing in for a real
     *  [app.frad.chat.wideradius.WideRangeByteStream] in these tests. */
    private class FakePipe {
        private val buffer = java.util.ArrayDeque<Byte>()
        fun write(bytes: ByteArray) { buffer.addAll(bytes.toList()) }
        fun readExactly(length: Int): ByteArray {
            check(buffer.size >= length) { "Not enough bytes buffered: wanted $length, have ${buffer.size}" }
            return ByteArray(length) { buffer.removeFirst() }
        }
    }

    @Test
    fun `plaintext larger than one chunk round trips exactly`() = runBlocking {
        val key = randomKey()
        val pipe = FakePipe()
        val plaintext = ByteArray(200_000) { (it % 256).toByte() }

        writeChunked(plaintext, TransferCipher(key), chunkSize = 64 * 1024, write = { pipe.write(it) })
        val decoded = readChunked(plaintext.size.toLong(), TransferCipher(key), readExactly = { pipe.readExactly(it) })

        assertArrayEquals(plaintext, decoded)
    }

    @Test
    fun `plaintext smaller than one chunk round trips exactly`() = runBlocking {
        val key = randomKey()
        val pipe = FakePipe()
        val plaintext = "small file contents".toByteArray()

        writeChunked(plaintext, TransferCipher(key), write = { pipe.write(it) })
        val decoded = readChunked(plaintext.size.toLong(), TransferCipher(key), readExactly = { pipe.readExactly(it) })

        assertArrayEquals(plaintext, decoded)
    }

    @Test
    fun `an implausible chunk length is rejected instead of over-allocating`() {
        val pipe = FakePipe()
        pipe.write(byteArrayOf(0x7F, 0x00, 0x00, 0x00)) // absurd 32-bit length prefix

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { readChunked(1L, TransferCipher(randomKey()), readExactly = { pipe.readExactly(it) }) }
        }
    }
}
