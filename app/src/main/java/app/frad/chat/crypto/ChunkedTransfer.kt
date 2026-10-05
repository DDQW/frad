package app.frad.chat.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val DEFAULT_CHUNK_SIZE = 64 * 1024
private const val CHUNK_LENGTH_PREFIX_SIZE = 4

/**
 * How a file travels once a transfer is under way - over Wi-Fi Direct's TCP socket
 * ([app.frad.chat.wifidirect.WifiDirectTransferManager]) and a wide-range libp2p stream
 * ([app.frad.chat.wideradius.WideRangeByteStream]) alike: length-prefixed chunks, each sealed by
 * [TransferCipher]. Written against plain suspend read/write callbacks so both can use it.
 *
 * [onProgress] gets the number of plaintext bytes handled so far after every chunk.
 */
suspend fun writeChunked(
    plaintext: ByteArray,
    cipher: TransferCipher,
    chunkSize: Int = DEFAULT_CHUNK_SIZE,
    onProgress: (Long) -> Unit = {},
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
        onProgress(end.toLong())
    }
}

/** Reverses [writeChunked]: reads chunks via [readExactly] (which must return exactly the
 *  requested number of bytes or throw) until [expectedSize] plaintext bytes have been decrypted.
 *  @throws IllegalArgumentException if a chunk is implausibly large or the peer sends more than
 *    [expectedSize] - the size the user agreed to receive. */
suspend fun readChunked(
    expectedSize: Long,
    cipher: TransferCipher,
    maxChunkOnWire: Int = DEFAULT_CHUNK_SIZE + 64,
    onProgress: (Long) -> Unit = {},
    readExactly: suspend (Int) -> ByteArray,
): ByteArray {
    val result = ByteArrayOutputStream()
    while (result.size() < expectedSize) {
        val length = ByteBuffer.wrap(readExactly(CHUNK_LENGTH_PREFIX_SIZE)).order(ByteOrder.BIG_ENDIAN).int
        require(length in 0..maxChunkOnWire) { "Implausible chunk length $length" }
        result.write(cipher.decryptChunk(readExactly(length)))
        require(result.size() <= expectedSize) { "Peer sent more than the announced $expectedSize bytes" }
        onProgress(result.size().toLong())
    }
    return result.toByteArray()
}
