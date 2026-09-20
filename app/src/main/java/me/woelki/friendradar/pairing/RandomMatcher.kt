package me.woelki.friendradar.pairing

import kotlin.random.Random

/** A peer currently visible over BLE and opted in to receiving chat requests. */
data class NearbyPeer(
    val sessionId: String,
    val rssi: Int,
    val lastSeenAtMillis: Long,
)

/**
 * Picks who to propose a chat with when the user taps "find someone nearby".
 * All matching happens locally on-device from whatever the BLE scanner has
 * currently observed — there is no server involved in "randomness" here.
 */
class RandomMatcher(private val random: Random = Random.Default) {

    /**
     * @param candidates currently-visible, opted-in nearby peers.
     * @param excluding peer session ids to skip (e.g. blocked peers, or a peer
     *   just declined), so a re-roll doesn't immediately re-offer the same person.
     */
    fun pickRandomPeer(candidates: List<NearbyPeer>, excluding: Set<String> = emptySet()): NearbyPeer? {
        val eligible = candidates.filter { it.sessionId !in excluding }
        if (eligible.isEmpty()) return null
        return eligible[random.nextInt(eligible.size)]
    }
}
