package app.frad.chat.pairing

import kotlin.random.Random

/** How strongly a discovered peer's presence signal came through — transport-specific,
 *  since BLE has a real RSSI concept and wide-range DHT discovery does not. */
sealed interface SignalStrength {
    data class Ble(val rssiDbm: Int) : SignalStrength
    data object Unknown : SignalStrength
}

/** A peer currently visible over some discovery transport and opted in to receiving chat
 *  requests. [sessionId] is only unique within the transport that discovered it. */
/**
 * How close a discovered peer is, in three deliberately coarse bands - enough for "someone is
 * right here" vs. "somewhere around", never a distance precise enough to walk up to a person.
 */
enum class ProximityBand {
    VERY_CLOSE,
    NEARBY,
    FURTHER;

    companion object {
        fun of(signal: SignalStrength): ProximityBand = when (signal) {
            is SignalStrength.Ble -> when {
                signal.rssiDbm >= -60 -> VERY_CLOSE
                signal.rssiDbm >= -78 -> NEARBY
                else -> FURTHER
            }
            SignalStrength.Unknown -> FURTHER
        }
    }
}

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
