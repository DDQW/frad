package app.frad.chat.safety

/**
 * Rate-limits outgoing chat requests per target peer, so a single nearby person
 * can't be hit with repeated pairing requests in a tight loop (spam/harassment
 * mitigation). Not a general anti-abuse system — just a cheap, local first line
 * of defense; see the M5 milestone for anything heavier (e.g. [app.frad.chat.crypto.ProofOfWork] on
 * the wide-range layer).
 */
class Cooldown(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastRequestAtMillis = mutableMapOf<String, Long>()

    fun canRequest(peerId: String): Boolean {
        val last = lastRequestAtMillis[peerId] ?: return true
        return now() - last >= minIntervalMillis
    }

    /** Call once a request has actually been sent to this peer. */
    fun recordRequest(peerId: String) {
        lastRequestAtMillis[peerId] = now()
    }

    /** Every peer that [canRequest] currently refuses - for the random pick to skip them instead
     *  of picking one and then doing nothing. */
    fun coolingDown(): Set<String> {
        val cutoff = now() - minIntervalMillis
        lastRequestAtMillis.values.removeAll { it <= cutoff - minIntervalMillis } // forget long-expired entries
        return lastRequestAtMillis.filterValues { it > cutoff }.keys
    }

    /** Identities (long-term peer ids) of people a chat with just ended - see [justChatted]. */
    private val endedAtMillisByIdentity = mutableMapOf<String, Long>()

    /** Call when a chat with [peerId] ends. Session ids rotate, so the same person soon shows up
     *  under a new one; their identity, known once the handshake is done, doesn't change. */
    fun recordChatEnded(peerId: String) {
        endedAtMillisByIdentity[peerId] = now()
    }

    /** Whether a chat with [peerId] ended less than the interval ago - a new connection with them,
     *  either way round, is refused until then, however their session id looks now. */
    fun justChatted(peerId: String): Boolean {
        val cutoff = now() - minIntervalMillis
        endedAtMillisByIdentity.values.removeAll { it <= cutoff }
        return peerId in endedAtMillisByIdentity
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS = 30_000L
    }
}
