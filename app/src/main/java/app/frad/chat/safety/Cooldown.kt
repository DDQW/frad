package app.frad.chat.safety

/**
 * Rate-limits outgoing chat requests per target peer, so a single nearby person
 * can't be hit with repeated pairing requests in a tight loop (spam/harassment
 * mitigation). Not a general anti-abuse system — just a cheap, local first line
 * of defense; see the M5 milestone for anything heavier (e.g. proof-of-work on
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

    companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS = 30_000L
    }
}
