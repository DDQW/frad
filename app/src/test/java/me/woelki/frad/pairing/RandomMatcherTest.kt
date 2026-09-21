package me.woelki.frad.pairing

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomMatcherTest {

    private fun peer(id: String) = NearbyPeer(sessionId = id, lastSeenAtMillis = 0, signalStrength = SignalStrength.Ble(-60))

    @Test
    fun `returns null when there are no candidates`() {
        val matcher = RandomMatcher(Random(1))
        assertNull(matcher.pickRandomPeer(emptyList()))
    }

    @Test
    fun `returns null when every candidate is excluded`() {
        val matcher = RandomMatcher(Random(1))
        val candidates = listOf(peer("a"), peer("b"))
        assertNull(matcher.pickRandomPeer(candidates, excluding = setOf("a", "b")))
    }

    @Test
    fun `never returns an excluded peer`() {
        val matcher = RandomMatcher(Random(42))
        val candidates = listOf(peer("a"), peer("b"), peer("c"))
        repeat(50) {
            val picked = matcher.pickRandomPeer(candidates, excluding = setOf("a", "b"))
            assertEquals("c", picked?.sessionId)
        }
    }

    @Test
    fun `is deterministic for a seeded random source`() {
        val a = RandomMatcher(Random(7))
        val b = RandomMatcher(Random(7))
        val candidates = listOf(peer("a"), peer("b"), peer("c"), peer("d"))

        val picksA = (1..10).map { a.pickRandomPeer(candidates)?.sessionId }
        val picksB = (1..10).map { b.pickRandomPeer(candidates)?.sessionId }
        assertEquals(picksA, picksB)
    }

    @Test
    fun `picks from the full candidate set over many draws`() {
        val matcher = RandomMatcher(Random(123))
        val candidates = listOf(peer("a"), peer("b"), peer("c"))
        val seen = (1..200).mapNotNull { matcher.pickRandomPeer(candidates)?.sessionId }.toSet()
        assertTrue(seen == setOf("a", "b", "c"))
    }

    @Test
    fun `matching works across peers with no rssi concept, e_g_ wide-range discovery`() {
        val matcher = RandomMatcher(Random(1))
        val candidates = listOf(
            NearbyPeer(sessionId = "local", lastSeenAtMillis = 0, signalStrength = SignalStrength.Ble(-70)),
            NearbyPeer(sessionId = "wide", lastSeenAtMillis = 0, signalStrength = SignalStrength.Unknown),
        )
        val seen = (1..200).mapNotNull { matcher.pickRandomPeer(candidates)?.sessionId }.toSet()
        assertTrue(seen == setOf("local", "wide"))
    }
}
