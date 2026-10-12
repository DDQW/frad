package app.frad.chat.safety

import java.time.ZoneId
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.MessageKind
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTranscriptTest {
    @Test
    fun `the transcript names the person, the reason and every message in order`() {
        val text = ReportTranscript.format(
            peerId = "abcdefghijkl",
            pseudonym = "Alex",
            reason = "Harassment or threats",
            reportedAtMillis = 0L,
            messages = listOf(
                ChatMessage(fromMe = false, text = "hey", atMillis = 0L),
                ChatMessage(fromMe = true, text = "hi", atMillis = 60_000L),
                ChatMessage(fromMe = false, text = "", atMillis = 120_000L, kind = MessageKind.FILE, fileName = "x.jpg", mimeType = "image/jpeg", sizeBytes = 10),
            ),
            zone = ZoneId.of("UTC"),
        )
        assertTrue(text, text.contains("Reason: Harassment or threats"))
        assertTrue(text, text.contains("Their FRAD id: abcdefghijkl"))
        assertTrue(text, text.contains("[1970-01-01 00:00:00] Them: hey\n[1970-01-01 00:01:00] Me: hi\n[1970-01-01 00:02:00] Them: [file: x.jpg, image/jpeg, 10 bytes]"))
    }
}
