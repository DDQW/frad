package app.frad.chat.chat

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatEnvelopeJsonTest {

    private fun roundTrip(envelope: ChatEnvelope) = ChatEnvelopeJson.decode(ChatEnvelopeJson.encode(envelope))

    private val offer = FileOffer(transferId = "abc123", fileName = "photo.jpg", mimeType = "image/jpeg", sizeBytes = 1234)

    @Test
    fun `every known kind round trips`() {
        val envelopes = listOf(
            ChatEnvelope.Text("hello"),
            ChatEnvelope.Text("with receipt", id = "m-1"),
            ChatEnvelope.Ack("m-1"),
            ChatEnvelope.Typing,
            ChatEnvelope.WfdOffer(offer, networkName = "DIRECT-ab", passphrase = "secret"),
            ChatEnvelope.WideOffer(offer),
            ChatEnvelope.FileRequest(offer),
            ChatEnvelope.FileReply("abc123", accepted = true),
            ChatEnvelope.FileReply("abc123", accepted = false),
        )
        for (envelope in envelopes) assertEquals(envelope, roundTrip(envelope))
    }

    @Test
    fun `keeps the wire format older builds already send for text`() {
        assertEquals(ChatEnvelope.Text("hi"), ChatEnvelopeJson.decode("""{"k":"txt","t":"hi"}"""))
    }

    @Test
    fun `an unknown kind is ignored instead of failing`() {
        assertEquals(ChatEnvelope.Unknown("reaction"), ChatEnvelopeJson.decode("""{"k":"reaction","to":"x"}"""))
    }

    @Test
    fun `an offer without a transfer id is ignored, not accepted`() {
        // Such an offer can't get a unique key - see ChatSession.deriveTransferKey. It costs the
        // file, not the chat.
        assertEquals(
            ChatEnvelope.Unknown("wide-transfer"),
            ChatEnvelopeJson.decode("""{"k":"wide-transfer","name":"a","mime":"b","size":1}"""),
        )
    }

    @Test
    fun `an offer with an implausible transfer id or size is ignored`() {
        assertEquals(
            ChatEnvelope.Unknown("wide-transfer"),
            ChatEnvelopeJson.decode("""{"k":"wide-transfer","tid":"${"x".repeat(65)}","name":"a","mime":"b","size":1}"""),
        )
        assertEquals(
            ChatEnvelope.Unknown("wfd"),
            ChatEnvelopeJson.decode("""{"k":"wfd","tid":"abc","name":"a","mime":"b","size":-1,"ssid":"s","pass":"p"}"""),
        )
    }

    @Test
    fun `overlong file names are clamped`() {
        val json = JSONObject()
            .put("k", "wide-transfer").put("tid", "abc").put("name", "n".repeat(10_000)).put("mime", "m").put("size", 1)
            .toString()
        val decoded = ChatEnvelopeJson.decode(json) as ChatEnvelope.WideOffer
        assertEquals(255, decoded.offer.fileName.length)
    }

    @Test
    fun `malformed json is a protocol error`() {
        assertThrows(JSONException::class.java) { ChatEnvelopeJson.decode("not json") }
        assertThrows(JSONException::class.java) { ChatEnvelopeJson.decode("""{"k":"txt"}""") }
    }

    @Test
    fun `a malformed file reply is ignored rather than ending the chat`() {
        assertEquals(ChatEnvelope.Unknown("file-reply"), ChatEnvelopeJson.decode("""{"k":"file-reply","tid":""}"""))
        assertEquals(ChatEnvelope.Unknown("file-reply"), ChatEnvelopeJson.decode("""{"k":"file-reply","tid":"abc"}"""))
        assertEquals(ChatEnvelope.Unknown("file-req"), ChatEnvelopeJson.decode("""{"k":"file-req","tid":"abc","name":"x","mime":"y","size":-1}"""))
    }
}
