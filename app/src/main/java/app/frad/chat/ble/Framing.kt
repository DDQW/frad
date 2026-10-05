package app.frad.chat.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Upper bound on one framed message (handshake step, profile or chat envelope) - BLE and
 * wide-range alike. The length prefix comes from a peer that hasn't authenticated yet, so it
 * must be bounded before anything is allocated for it; 64 KiB is far above every legitimate
 * message (the profile with its thumbnail photo is a few KiB, a chat text is capped by
 * [app.frad.chat.chat.MAX_MESSAGE_CHARS]). File contents never travel as frames.
 */
const val MAX_FRAME_BYTES = 64 * 1024

/** A peer announced a frame longer than [MAX_FRAME_BYTES] (or a negative length) - a protocol
 *  violation the caller should answer by dropping the connection. */
class FrameTooLargeException(length: Int, max: Int) :
    IllegalArgumentException("Frame length $length outside 0..$max")

/**
 * BLE GATT writes/notifications are capped by the negotiated ATT MTU (as low as
 * 20 bytes of payload if MTU negotiation is refused), but our Noise handshake
 * messages and chat ciphertexts can be larger than that. [FrameWriter] splits a
 * single logical message into MTU-sized fragments; [FrameReassembler] puts them
 * back together on the other end. This is transport-only framing — it knows
 * nothing about what the bytes mean (handshake step vs. encrypted chat text).
 */
object FrameWriter {
    private const val LENGTH_PREFIX_SIZE = 4

    /**
     * @param maxFragmentSize the usable bytes per BLE write (already accounting for
     *   the negotiated MTU minus the 3-byte ATT header).
     */
    fun split(message: ByteArray, maxFragmentSize: Int): List<ByteArray> {
        require(maxFragmentSize > LENGTH_PREFIX_SIZE) { "maxFragmentSize too small for the length prefix" }

        val withLength = ByteBuffer.allocate(LENGTH_PREFIX_SIZE + message.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(message.size)
            .put(message)
            .array()

        return withLength.toList().chunked(maxFragmentSize).map { it.toByteArray() }
    }
}

/** Not thread-safe; use one instance per logical connection/direction. */
class FrameReassembler(private val maxMessageSize: Int = MAX_FRAME_BYTES) {
    private val header = ByteArray(4)
    private var headerFilled = 0
    private var body: ByteArray? = null
    private var bodyFilled = 0
    private var leftover = ByteArray(0)

    /** @return a completed message once enough fragments have arrived, or null if more are needed.
     *  @throws FrameTooLargeException if the peer announces a length outside 0..[maxMessageSize];
     *    the reassembler is reset, but the connection should be dropped regardless. */
    fun offer(fragment: ByteArray): ByteArray? {
        val input = if (leftover.isEmpty()) fragment else leftover + fragment
        leftover = ByteArray(0)
        var pos = 0

        if (body == null) {
            val headerBytes = minOf(header.size - headerFilled, input.size)
            input.copyInto(header, headerFilled, 0, headerBytes)
            headerFilled += headerBytes
            pos = headerBytes
            if (headerFilled < header.size) return null

            val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
            if (length !in 0..maxMessageSize) {
                reset()
                throw FrameTooLargeException(length, maxMessageSize)
            }
            // Safe to allocate up front now that the length is bounded, and it avoids re-copying
            // the whole buffer on every small BLE fragment.
            body = ByteArray(length)
            bodyFilled = 0
        }

        val message = body!!
        val bodyBytes = minOf(message.size - bodyFilled, input.size - pos)
        input.copyInto(message, bodyFilled, pos, pos + bodyBytes)
        bodyFilled += bodyBytes
        pos += bodyBytes
        if (bodyFilled < message.size) return null

        leftover = input.copyOfRange(pos, input.size) // leftover bytes belong to the next message
        body = null
        headerFilled = 0
        bodyFilled = 0
        return message
    }

    fun reset() {
        headerFilled = 0
        body = null
        bodyFilled = 0
        leftover = ByteArray(0)
    }
}
