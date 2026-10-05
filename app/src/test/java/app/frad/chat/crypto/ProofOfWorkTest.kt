package app.frad.chat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofOfWorkTest {
    private val now = 1_800_000_000L
    private val bits = 12 // keeps the test fast; the protocol uses ProofOfWork.DIFFICULTY_BITS

    @Test
    fun `a solved token verifies for exactly the pair it was solved for`() {
        val token = ProofOfWork.solve("responder", "initiator", now, bits)
        assertEquals(ProofOfWork.TOKEN_BYTES, token.size)
        assertTrue(ProofOfWork.verify(token, "responder", "initiator", now, bits))

        // Can't be pointed at someone else, or used by someone else.
        assertFalse(ProofOfWork.verify(token, "someone-else", "initiator", now, bits))
        assertFalse(ProofOfWork.verify(token, "responder", "someone-else", now, bits))
    }

    @Test
    fun `tokens expire and can't come from the far future`() {
        val token = ProofOfWork.solve("r", "i", now, bits)
        assertTrue(ProofOfWork.verify(token, "r", "i", now + ProofOfWork.MAX_CLOCK_SKEW_SECONDS, bits))
        assertFalse(ProofOfWork.verify(token, "r", "i", now + ProofOfWork.MAX_CLOCK_SKEW_SECONDS + 1, bits))
        assertFalse(ProofOfWork.verify(token, "r", "i", now - ProofOfWork.MAX_CLOCK_SKEW_SECONDS - 1, bits))
    }

    @Test
    fun `a tampered or malformed token is rejected`() {
        val token = ProofOfWork.solve("r", "i", now, bits)
        val tampered = token.copyOf().also { it[15] = (it[15] + 1).toByte() }
        // A different nonce almost never also meets the target (probability 2^-12 here).
        assertFalse(ProofOfWork.verify(tampered, "r", "i", now, bits))
        assertFalse(ProofOfWork.verify(ByteArray(3), "r", "i", now, bits))
        assertFalse(ProofOfWork.verify(ByteArray(0), "r", "i", now, bits))
    }

    @Test
    fun `an unsolved token fails at the real difficulty`() {
        val lazy = java.nio.ByteBuffer.allocate(ProofOfWork.TOKEN_BYTES).putLong(now).putLong(0).array()
        assertFalse(ProofOfWork.verify(lazy, "r", "i", now))
    }
}
