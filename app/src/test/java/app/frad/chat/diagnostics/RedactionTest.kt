package app.frad.chat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactionTest {
    @Test
    fun `addresses, ids and keys are removed, code locations kept`() {
        val trace = """
            java.lang.IllegalStateException: no link for 4C:1A:2B:3D:4E:5F peer aB3x9Kq2LmN8pQr5StUvWxYz01234567
            	at app.frad.chat.ble.BleChatController.handleFrame(BleChatController.kt:712)
            	dial /ip4/203.0.113.7/tcp/4001/p2p/12D3KooWGzBsVqGyF3vV7z5dJqXz8u3Mf9pY2r6QkTn4hLwXcA1e failed
            	session 9f86d081884c7d659a2feaa0c55ad015 from anna@example.org
        """.trimIndent()
        val redacted = Redaction.redact(trace)
        for (secret in listOf("4C:1A:2B:3D:4E:5F", "aB3x9Kq2LmN8pQr5StUvWxYz01234567", "203.0.113.7", "12D3KooW", "9f86d081884c7d659a2feaa0c55ad015", "anna@example.org")) {
            assertFalse(secret, redacted.contains(secret))
        }
        assertEquals(true, redacted.contains("app.frad.chat.ble.BleChatController.handleFrame(BleChatController.kt:712)"))
    }
}
