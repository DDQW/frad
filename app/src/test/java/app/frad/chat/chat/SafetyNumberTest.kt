package app.frad.chat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {
    private val alice = ByteArray(32) { 1 }
    private val bob = ByteArray(32) { 2 }
    private val mallory = ByteArray(32) { 3 }

    @Test
    fun `both sides compute the same number`() {
        assertEquals(SafetyNumber.of(alice, bob), SafetyNumber.of(bob, alice))
    }

    @Test
    fun `a different key gives a different number`() {
        assertNotEquals(SafetyNumber.of(alice, bob), SafetyNumber.of(alice, mallory))
    }

    @Test
    fun `it is twelve groups of five digits`() {
        val number = SafetyNumber.of(alice, bob)
        assertTrue(number.matches(Regex("""(\d{5} ){11}\d{5}""")))
    }
}
