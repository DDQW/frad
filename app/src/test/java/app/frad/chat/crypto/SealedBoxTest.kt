package app.frad.chat.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedBoxTest {
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `a sealed value opens to the original`() {
        val box = SealedBox(newKey())
        val sealed = box.sealString("hello, contact")
        assertTrue(SealedBox.isSealed(sealed))
        assertEquals("hello, contact", box.openString(sealed))
    }

    @Test
    fun `the same value seals differently every time`() {
        val box = SealedBox(newKey())
        assertNotEquals(box.sealString("same"), box.sealString("same"))
    }

    @Test
    fun `binary content survives`() {
        val box = SealedBox(newKey())
        val bytes = ByteArray(300) { it.toByte() }
        assertArrayEquals(bytes, box.open(box.seal(bytes)))
    }

    @Test(expected = AEADBadTagException::class)
    fun `another key can't open it`() {
        val sealed = SealedBox(newKey()).sealString("secret")
        SealedBox(newKey()).openString(sealed)
    }

    @Test(expected = AEADBadTagException::class)
    fun `a tampered value is rejected`() {
        val box = SealedBox(newKey())
        val sealed = box.sealString("secret")
        val raw = java.util.Base64.getDecoder().decode(sealed.removePrefix("k1:"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        box.open("k1:" + java.util.Base64.getEncoder().encodeToString(raw))
    }

    @Test
    fun `plain JSON from older versions is not mistaken for a sealed value`() {
        assertFalse(SealedBox.isSealed("""[{"fromMe":true,"text":"hi","atMillis":1}]"""))
    }
}
