package app.frad.chat.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePaddingTest {
    @Test
    fun `padded frames are a multiple of the block size in bytes`() {
        for (text in listOf("", "a", "x".repeat(63), "x".repeat(64), "x".repeat(65), "äöü€ — 😀")) {
            val json = ChatEnvelopeJson.encode(ChatEnvelope.Text(text, id = "m"))
            val padded = FramePadding.pad(json)
            assertEquals(0, padded.toByteArray(Charsets.UTF_8).size % FramePadding.BLOCK)
            assertEquals(json, FramePadding.unpad(padded))
        }
    }

    @Test
    fun `a typing notice and a short text look the same size`() {
        val typing = FramePadding.pad(ChatEnvelopeJson.encode(ChatEnvelope.Typing))
        val text = FramePadding.pad(ChatEnvelopeJson.encode(ChatEnvelope.Text("hi", id = "0123456789")))
        assertEquals(typing.length, text.length)
    }

    @Test
    fun `padded JSON still decodes, even without unpadding`() {
        val json = ChatEnvelopeJson.encode(ChatEnvelope.Text("hello", id = "m-1"))
        assertEquals(ChatEnvelope.Text("hello", id = "m-1"), ChatEnvelopeJson.decode(FramePadding.pad(json)))
    }
}
