package app.frad.chat.crypto

import app.frad.chat.crypto.noise.Primitives
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRendezvousTest {
    private val alice = Primitives.generateKeyPair()
    private val bob = Primitives.generateKeyPair()
    private val carol = Primitives.generateKeyPair()

    @Test
    fun `both contacts derive the same secret and topic`() {
        val ab = ContactRendezvous.secret(alice.first, bob.second)
        val ba = ContactRendezvous.secret(bob.first, alice.second)
        assertArrayEquals(ab, ba)
        assertEquals(ContactRendezvous.topic(ab, 20_000), ContactRendezvous.topic(ba, 20_000))
    }

    @Test
    fun `topics differ per pair of people and per day`() {
        val ab = ContactRendezvous.secret(alice.first, bob.second)
        val ac = ContactRendezvous.secret(alice.first, carol.second)
        assertNotEquals(ContactRendezvous.topic(ab, 20_000), ContactRendezvous.topic(ac, 20_000))
        assertNotEquals(ContactRendezvous.topic(ab, 20_000), ContactRendezvous.topic(ab, 20_001))
        assertTrue(ContactRendezvous.topic(ab, 1).startsWith(ContactRendezvous.TOPIC_PREFIX))
    }

    @Test
    fun `near midnight the neighbouring day is looked at too`() {
        val secret = ContactRendezvous.secret(alice.first, bob.second)
        val day = 20_000L
        val midday = day * 86_400_000 + 43_200_000
        assertEquals(1, ContactRendezvous.topicsAround(secret, midday).size)
        val justBeforeMidnight = (day + 1) * 86_400_000 - 60_000
        assertEquals(listOf(ContactRendezvous.topic(secret, day), ContactRendezvous.topic(secret, day + 1)), ContactRendezvous.topicsAround(secret, justBeforeMidnight))
    }
}
