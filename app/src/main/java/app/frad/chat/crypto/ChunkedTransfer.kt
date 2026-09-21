package app.frad.chat.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val DEFAULT_CHUNK_SIZE = 64 * 1024
private const val CHUNK_LENGTH_PREFIX_SIZE = 4

/**
 * The same length-prefixed, [TransferCipher]-authenticated chunk framing
 * [app.frad.chat.wifidirect.WifiDirectTransferManager] uses over a
 * `java.io` socket stream, reimplemented against plain suspend read/write
 * callbacks so it also works over a
 * [app.frad.chat.wideradius.WideRangeByteStream], which isn't a
 * `java.io` stream. `WifiDirectTransferManager` itself is left untouched.
 */
suspend fun writeChunked(
    plaintext: ByteArray,
    cipher: TransferCipher,
    chunkSize: Int = DEFAULT_CHUNK_SIZE,
    write: suspend (ByteArray) -> Unit,
) {
    var offset = 0
    while (offset < plaintext.size) {
        val end = minOf(offset + chunkSize, plaintext.size)
        val ciphertext = cipher.encryptChunk(plaintext.copyOfRange(offset, end))
        val framed = ByteBuffer.allocate(CHUNK_LENGTH_PREFIX_SIZE + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
        write(framed)
        offset = end
    }
}

/** Reverses [writeChunked]: reads chunks via [readExactly] (which must return exactly the
 *  requested number of bytes or throw) until [expectedSize] plaintext bytes have been decrypted. */
suspend fun readChunked(
    expectedSize: Long,
    cipher: TransferCipher,
    maxChunkOnWire: Int = DEFAULT_CHUNK_SIZE + 64,
    readExactly: suspend (Int) -> ByteArray,
): ByteArray {
    val result = ByteArrayOutputStream()
    while (result.size() < expectedSize) {
        val length = ByteBuffer.wrap(readExactly(CHUNK_LENGTH_PREFIX_SIZE)).order(ByteOrder.BIG_ENDIAN).int
        require(length in 0..maxChunkOnWire) { "Implausible chunk length $length" }
        result.write(cipher.decryptChunk(readExactly(length)))
    }
    return result.toByteArray()
}
