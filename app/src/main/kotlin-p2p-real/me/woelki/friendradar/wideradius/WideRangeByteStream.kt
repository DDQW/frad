package me.woelki.friendradar.wideradius

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.woelki.friendradar.p2pgo.node.Stream as GoStream

/**
 * A single libp2p stream (chat or file-transfer protocol — see
 * [me.woelki.friendradar.wideradius.WideRangeChatController]), wrapping the
 * gomobile-generated [GoStream]. Plain byte in/out, same as
 * [me.woelki.friendradar.ble.Framing] expects — gomobile can't bind
 * `java.io`/`OutputStream` directly, which is why this exists instead of
 * exposing [GoStream] itself to callers.
 */
class WideRangeByteStream internal constructor(private val goStream: GoStream) {

    /** The libp2p peer id (transport-level, rotating per session) of whoever is on the
     *  other end of this stream — distinct from [me.woelki.friendradar.crypto.ChatSession.remotePeerId],
     *  the app-level long-term identity only known once the Noise handshake completes. */
    val remotePeerId: String get() = goStream.remotePeerId()

    suspend fun write(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { goStream.write(bytes) }.map { Unit }
    }

    /** Loops [GoStream.read] until exactly [length] bytes have been collected;
     *  fails (rather than returning a short read) if the stream closes first. */
    suspend fun readExactly(length: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val result = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val chunk = goStream.read(length - offset)
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            result
        }
    }

    fun close() {
        runCatching { goStream.close() }
    }
}
