package me.woelki.frad.pairing

import kotlin.random.Random

/** How strongly a discovered peer's presence signal came through — transport-specific,
 *  since BLE has a real RSSI concept and wide-range DHT discovery does not. */
sealed interface SignalStrength {
    data class Ble(val rssiDbm: Int) : SignalStrength
    data object Unknown : SignalStrength
}

/** A peer currently visible over some discovery transport and opted in to receiving chat
 *  requests. [sessionId] is only unique within the transport that discovered it. */
data class NearbyPeer(
    val sessionId: String,
    val lastSeenAtMillis: Long,
    val signalStrength: SignalStrength = SignalStrength.Unknown,
)

/**
 * Picks who to propose a chat with when the user taps "find someone nearby".
 * All matching happens locally on-device from whatever the current discovery
 * transport has currently observed — there is no server involved in
 * "randomness" here.
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
