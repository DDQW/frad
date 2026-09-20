package me.woelki.friendradar.contacts

import me.woelki.friendradar.chat.ChatMessage
import me.woelki.friendradar.chat.MessageKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageJsonTest {

    @Test
    fun `a text message round trips through encode and decode`() {
        val message = ChatMessage(fromMe = true, text = "hey", atMillis = 1_000L)
        val decoded = ChatMessageJson.decode(ChatMessageJson.encode(listOf(message)))
        assertEquals(listOf(message), decoded)
    }

    @Test
    fun `a file message round trips through encode and decode`() {
        val message = ChatMessage(
            fromMe = false,
            text = "",
            atMillis = 2_000L,
            kind = MessageKind.FILE,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 12_345L,
            localPath = "/data/data/me.woelki.friendradar/files/wfd_media/peer/id",
        )
        val decoded = ChatMessageJson.decode(ChatMessageJson.encode(listOf(message)))
        assertEquals(listOf(message), decoded)
    }

    @Test
    fun `a transcript saved before M3, without a kind field, still decodes as a text message`() {
        val preM3Json = JSONArray().put(
            JSONObject().put("fromMe", true).put("text", "hi from M2").put("atMillis", 500L),
        ).toString()

        val decoded = ChatMessageJson.decode(preM3Json)

        assertEquals(listOf(ChatMessage(fromMe = true, text = "hi from M2", atMillis = 500L, kind = MessageKind.TEXT)), decoded)
    }
}
