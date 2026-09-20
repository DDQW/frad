package me.woelki.friendradar.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
class FrameReassembler {
    private var expectedLength = -1
    private var buffer = ByteArray(0)

    /** @return a completed message once enough fragments have arrived, or null if more are needed. */
    fun offer(fragment: ByteArray): ByteArray? {
        buffer += fragment

        if (expectedLength < 0) {
            if (buffer.size < 4) return null
            expectedLength = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        }

        val totalNeeded = 4 + expectedLength
        if (buffer.size < totalNeeded) return null

        val message = buffer.copyOfRange(4, totalNeeded)
        buffer = buffer.copyOfRange(totalNeeded, buffer.size) // leftover bytes belong to the next message
        expectedLength = -1
        return message
    }

    fun reset() {
        expectedLength = -1
        buffer = ByteArray(0)
    }
}
