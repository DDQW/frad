package app.frad.chat.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgesTest {
    @Test
    fun `contact details in an outgoing message are noticed`() {
        assertEquals(setOf(Nudges.Outgoing.PHONE_NUMBER), Nudges.beforeSending("call me 0171 2345678"))
        assertEquals(setOf(Nudges.Outgoing.PHONE_NUMBER), Nudges.beforeSending("+49 171 234 56 78"))
        assertEquals(setOf(Nudges.Outgoing.EMAIL), Nudges.beforeSending("write to anna.b@example.org"))
        assertEquals(setOf(Nudges.Outgoing.BANK_DETAILS), Nudges.beforeSending("DE89 3704 0044 0532 0130 00"))
        assertEquals(setOf(Nudges.Outgoing.STREET_ADDRESS), Nudges.beforeSending("I live at Hauptstraße 12"))
        assertEquals(setOf(Nudges.Outgoing.STREET_ADDRESS), Nudges.beforeSending("meet me at 5, Baker street 221b"))
    }

    @Test
    fun `everyday text is left alone`() {
        for (text in listOf("hi!", "see you at 10:30", "I'm 24 and from Berlin", "on 12.03.2026 maybe", "score was 3-1", "Room 101")) {
            assertTrue(text, Nudges.beforeSending(text).isEmpty())
            assertTrue(text, Nudges.onReceived(text).isEmpty())
        }
    }

    @Test
    fun `lures in an incoming message are marked`() {
        assertEquals(setOf(Nudges.Incoming.LINK), Nudges.onReceived("look at https://example.com/x"))
        assertEquals(setOf(Nudges.Incoming.LINK), Nudges.onReceived("go to free-prizes.xyz now"))
        assertEquals(setOf(Nudges.Incoming.MONEY), Nudges.onReceived("can you send me a gift card?"))
        assertEquals(setOf(Nudges.Incoming.OTHER_APP), Nudges.onReceived("add me on Snapchat"))
        assertEquals(setOf(Nudges.Incoming.OTHER_APP, Nudges.Incoming.MONEY), Nudges.onReceived("Telegram me, I need Geld"))
    }
}
