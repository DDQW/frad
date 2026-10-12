package app.frad.chat.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
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
) = writeChunkedFrom(ByteArrayInputStream(plaintext), plaintext.size.toLong(), cipher, chunkSize, onProgress, write)

/** Like [writeChunked], reading the [size] plaintext bytes from [source] one chunk at a time -
 *  so a file is never held in memory as a whole.
 *  @throws EOFException if [source] ends before [size] bytes. */
suspend fun writeChunkedFrom(
    source: InputStream,
    size: Long,
    cipher: TransferCipher,
    chunkSize: Int = DEFAULT_CHUNK_SIZE,
    onProgress: (Long) -> Unit = {},
    write: suspend (ByteArray) -> Unit,
) {
    val buffer = ByteArray(chunkSize)
    var done = 0L
    while (done < size) {
        val length = minOf(chunkSize.toLong(), size - done).toInt()
        var filled = 0
        while (filled < length) {
            val n = source.read(buffer, filled, length - filled)
            if (n < 0) throw EOFException("Source ended after ${done + filled} of $size bytes")
            filled += n
        }
        val ciphertext = cipher.encryptChunk(buffer.copyOf(length))
        val framed = ByteBuffer.allocate(CHUNK_LENGTH_PREFIX_SIZE + ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(ciphertext.size)
            .put(ciphertext)
            .array()
        write(framed)
        done += length
        onProgress(done)
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
    readChunkedTo(result, expectedSize, cipher, maxChunkOnWire, onProgress, readExactly)
    return result.toByteArray()
}

/** Like [readChunked], writing each decrypted chunk to [sink] as it arrives. */
suspend fun readChunkedTo(
    sink: OutputStream,
    expectedSize: Long,
    cipher: TransferCipher,
    maxChunkOnWire: Int = DEFAULT_CHUNK_SIZE + 64,
    onProgress: (Long) -> Unit = {},
    readExactly: suspend (Int) -> ByteArray,
) {
    var received = 0L
    while (received < expectedSize) {
        val length = ByteBuffer.wrap(readExactly(CHUNK_LENGTH_PREFIX_SIZE)).order(ByteOrder.BIG_ENDIAN).int
        require(length in 0..maxChunkOnWire) { "Implausible chunk length $length" }
        val plaintext = cipher.decryptChunk(readExactly(length))
        received += plaintext.size
        require(received <= expectedSize) { "Peer sent more than the announced $expectedSize bytes" }
        sink.write(plaintext)
        onProgress(received)
    }
}
