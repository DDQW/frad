package app.frad.chat.contacts

import app.frad.chat.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryRetentionTest {
    private fun message(at: Long) = ChatMessage(fromMe = true, text = "m$at", atMillis = at)

    @Test
    fun `without a cutoff everything up to the cap is kept`() {
        val messages = (1L..5L).map(::message)
        assertEquals(messages, ChatHistoryRetention.apply(messages, cutoffMillis = null, maxMessages = 10))
    }

    @Test
    fun `messages older than the cutoff are dropped`() {
        val messages = (1L..5L).map(::message)
        assertEquals((3L..5L).map(::message), ChatHistoryRetention.apply(messages, cutoffMillis = 3L, maxMessages = 10))
    }

    @Test
    fun `the cap keeps the newest messages`() {
        val messages = (1L..5L).map(::message)
        assertEquals((4L..5L).map(::message), ChatHistoryRetention.apply(messages, cutoffMillis = null, maxMessages = 2))
    }
}
