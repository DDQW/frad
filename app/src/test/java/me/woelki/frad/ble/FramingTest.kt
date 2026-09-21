package me.woelki.frad.ble

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramingTest {

    @Test
    fun `single fragment round trips when the message fits in one write`() {
        val message = "hello".toByteArray()
        val fragments = FrameWriter.split(message, maxFragmentSize = 64)
        assertEquals(1, fragments.size)

        val reassembler = FrameReassembler()
        val result = reassembler.offer(fragments[0])
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
            val maybeComplete = reassembler.offer(fragment)
            if (maybeComplete != null) {
                result = maybeComplete
            }
        }
        assertArrayEquals(message, result)
    }

    @Test
    fun `returns null until the full message has arrived`() {
        val message = Random(2).nextBytes(100)
        val fragments = FrameWriter.split(message, maxFragmentSize = 20)
        val reassembler = FrameReassembler()

        for (fragment in fragments.dropLast(1)) {
            assertNull(reassembler.offer(fragment))
        }
        assertArrayEquals(message, reassembler.offer(fragments.last()))
    }

    @Test
    fun `reassembler can be reused sequentially for multiple messages`() {
        val reassembler = FrameReassembler()
        val first = "first message".toByteArray()
        val second = "a rather longer second message that needs a couple of fragments".toByteArray()

        for (fragment in FrameWriter.split(first, maxFragmentSize = 8).dropLast(1)) {
            assertNull(reassembler.offer(fragment))
        }
        assertArrayEquals(first, reassembler.offer(FrameWriter.split(first, maxFragmentSize = 8).last()))

        for (fragment in FrameWriter.split(second, maxFragmentSize = 12).dropLast(1)) {
            assertNull(reassembler.offer(fragment))
        }
        assertArrayEquals(second, reassembler.offer(FrameWriter.split(second, maxFragmentSize = 12).last()))
    }

    @Test
    fun `empty message round trips`() {
        val fragments = FrameWriter.split(ByteArray(0), maxFragmentSize = 64)
        val reassembler = FrameReassembler()
        assertArrayEquals(ByteArray(0), reassembler.offer(fragments[0]))
    }
}
