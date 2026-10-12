package app.frad.chat.ble

import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FramingTest {

    @Test
    fun `single fragment round trips when the message fits in one write`() {
        val message = "hello".toByteArray()
        val fragments = FrameWriter.split(message, maxFragmentSize = 64)
        assertEquals(1, fragments.size)

        val reassembler = FrameReassembler()
        val result = reassembler.offer(fragments[0]).single()
        assertArrayEquals(message, result)
    }

    @Test
    fun `message larger than one fragment is reassembled correctly`() {
        val message = Random(1).nextBytes(500) // e.g. a Noise handshake message over a tiny MTU
        val fragments = FrameWriter.split(message, maxFragmentSize = 20)
        assert(fragments.size > 1)

        val reassembler = FrameReassembler()
        var result: ByteArray? = null
        for (fragment in fragments) {
            reassembler.offer(fragment).singleOrNull()?.let { result = it }
        }
        assertArrayEquals(message, result)
    }

    @Test
    fun `returns null until the full message has arrived`() {
        val message = Random(2).nextBytes(100)
        val fragments = FrameWriter.split(message, maxFragmentSize = 20)
        val reassembler = FrameReassembler()

        for (fragment in fragments.dropLast(1)) {
            assertTrue(reassembler.offer(fragment).isEmpty())
        }
        assertArrayEquals(message, reassembler.offer(fragments.last()).single())
    }

    @Test
    fun `reassembler can be reused sequentially for multiple messages`() {
        val reassembler = FrameReassembler()
        val first = "first message".toByteArray()
        val second = "a rather longer second message that needs a couple of fragments".toByteArray()

        for (fragment in FrameWriter.split(first, maxFragmentSize = 8).dropLast(1)) {
            assertTrue(reassembler.offer(fragment).isEmpty())
        }
        assertArrayEquals(first, reassembler.offer(FrameWriter.split(first, maxFragmentSize = 8).last()).single())

        for (fragment in FrameWriter.split(second, maxFragmentSize = 12).dropLast(1)) {
            assertTrue(reassembler.offer(fragment).isEmpty())
        }
        assertArrayEquals(second, reassembler.offer(FrameWriter.split(second, maxFragmentSize = 12).last()).single())
    }

    @Test
    fun `empty message round trips`() {
        val fragments = FrameWriter.split(ByteArray(0), maxFragmentSize = 64)
        val reassembler = FrameReassembler()
        assertArrayEquals(ByteArray(0), reassembler.offer(fragments[0]).single())
    }

    @Test
    fun `bytes past one message's end are kept for the next one`() {
        val first = "one".toByteArray()
        val second = "two, a bit longer".toByteArray()
        val wire = FrameWriter.split(first, maxFragmentSize = 64).single() + FrameWriter.split(second, maxFragmentSize = 64).single()
        val reassembler = FrameReassembler()

        // First write: all of message one plus the start of message two.
        assertArrayEquals(first, reassembler.offer(wire.copyOfRange(0, 10)).single())
        assertArrayEquals(second, reassembler.offer(wire.copyOfRange(10, wire.size)).single())
    }

    @Test
    fun `several frames packed into one write all come out at once`() {
        val frames = (1..50).map { "m$it".toByteArray() }
        val wire = frames.map { FrameWriter.split(it, maxFragmentSize = 64).single() }.reduce { a, b -> a + b }
        val out = FrameReassembler().offer(wire)
        assertEquals(frames.map { it.toList() }, out.map { it.toList() })
    }

    @Test
    fun `an announced length above the cap is rejected before anything is buffered`() {
        val reassembler = FrameReassembler(maxMessageSize = 1024)
        val header = ByteBuffer.allocate(4).putInt(1025).array()
        assertThrows(FrameTooLargeException::class.java) { reassembler.offer(header) }
    }

    @Test
    fun `a negative announced length is rejected`() {
        val reassembler = FrameReassembler()
        assertThrows(FrameTooLargeException::class.java) { reassembler.offer(byteArrayOf(-1, -1, -1, -1, 0)) }
    }

    @Test
    fun `a message exactly at the cap still round trips`() {
        val message = Random(3).nextBytes(MAX_FRAME_BYTES)
        val reassembler = FrameReassembler()
        var result: ByteArray? = null
        for (fragment in FrameWriter.split(message, maxFragmentSize = 4096)) {
            reassembler.offer(fragment).singleOrNull()?.let { result = it }
        }
        assertArrayEquals(message, result)
    }
}
