package app.frad.chat.wideradius

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import app.frad.chat.profile.Profile

/**
 * Where wide-range finds its servers (bootstrap/relay nodes) - deliberately never one place only,
 * so FRAD can't be switched off by taking down any single server, list or website:
 *
 *  1. the servers the user set themselves (Profile.bootstrapNodes, frad://node links) - always
 *     tried first;
 *  2. the official list, `nodes/nodes.txt` in FRAD's repository, maintained by a crawler that
 *     walks the server network - fetched through two independent CDNs, at most every 6 hours,
 *     and kept, so a phone that fetched it once doesn't depend on it any more;
 *  3. servers learned from the servers themselves and from other phones (they swap lists - see
 *     p2p-go's /frad/nodes/1.0.0 and [app.frad.chat.chat.ChatEnvelope.Nodes]), kept for a week.
 *
 * Nothing here is trusted: every address is only a candidate the libp2p node checks by actually
 * connecting (and the Go side re-validates every list it's handed).
 */
class NodeDirectory(
    context: Context,
    private val profile: Profile,
    private val fetch: (String) -> String? = ::httpsGet,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Servers to bootstrap from: the user's own first, then (if allowed) official and learned. */
    fun candidates(max: Int = MAX_CANDIDATES): List<String> = synchronized(LOCK) {
        val public = if (profile.usePublicNodes) official() + learned() else emptyList()
        NodeLists.diverse(profile.bootstrapNodes.filter(NodeLists::isValid) + public, max)
    }

    /** What this phone tells others about (its libp2p node's peers, its chat partners): public
     *  servers only - the official and learned ones. Servers the user set themselves may be
     *  private (a group's own server) and aren't handed to strangers. */
    fun publicForSharing(): List<String> = synchronized(LOCK) {
        if (profile.usePublicNodes) NodeLists.diverse(official() + learned(), MAX_SHARED) else emptyList()
    }

    /** Fetches the official list if it's older than [OFFICIAL_REFRESH_MILLIS] (and allowed).
     *  Blocking network I/O - call off the main thread. @return whether a fresh list came in. */
    fun refreshOfficialIfDue(): Boolean {
        if (!profile.usePublicNodes) return false
        if (now() - prefs.getLong(KEY_OFFICIAL_AT, 0L) < OFFICIAL_REFRESH_MILLIS) return false
        for (url in OFFICIAL_LIST_URLS) {
            val text = runCatching { fetch(url) }.getOrNull() ?: continue
            if (!text.lineSequence().any { it.startsWith("#") || NodeLists.isValid(it.trim()) }) continue // not a node list at all
            synchronized(LOCK) {
                prefs.edit()
                    .putString(KEY_OFFICIAL, NodeLists.parse(text).joinToString("\n"))
                    .putLong(KEY_OFFICIAL_AT, now())
                    .apply()
            }
            return true
        }
        return false
    }

    /** Adds servers someone told us about; only well-formed ones, at most [MAX_LEARNED] kept. */
    fun addLearned(addresses: List<String>) = synchronized(LOCK) {
        if (!profile.usePublicNodes) return@synchronized
        val fresh = addresses.map(String::trim).filter(NodeLists::isValid)
        if (fresh.isEmpty()) return@synchronized
        val seen = learnedWithTimes().toMutableMap()
        fresh.forEach { seen[it] = now() }
        val kept = seen.entries
            .filter { now() - it.value < LEARNED_TTL_MILLIS }
            .sortedByDescending { it.value }
            .take(MAX_LEARNED)
        prefs.edit().putString(KEY_LEARNED, JSONObject(kept.associate { it.key to it.value }).toString()).apply()
    }

    private fun official(): List<String> = prefs.getString(KEY_OFFICIAL, null)?.lines()?.filter(NodeLists::isValid) ?: emptyList()

    private fun learned(): List<String> = learnedWithTimes()
        .filter { now() - it.value < LEARNED_TTL_MILLIS }
        .entries.sortedByDescending { it.value }
        .map { it.key }

    private fun learnedWithTimes(): Map<String, Long> {
        val raw = prefs.getString(KEY_LEARNED, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getLong(it) }
        }.getOrDefault(emptyMap())
    }

    companion object {
        /** The same file through two independent CDNs - see nodes/README.md. */
        val OFFICIAL_LIST_URLS = listOf(
            "https://raw.githubusercontent.com/DDQW/frad/master/nodes/nodes.txt",
            "https://cdn.jsdelivr.net/gh/DDQW/frad@master/nodes/nodes.txt",
        )
        const val OFFICIAL_REFRESH_MILLIS = 6L * 60 * 60 * 1000
        const val LEARNED_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_LEARNED = 128
        private const val MAX_CANDIDATES = 24
        private const val MAX_SHARED = 32
        private const val PREFS_FILE = "frad_node_directory"
        private const val KEY_OFFICIAL = "official"
        private const val KEY_OFFICIAL_AT = "official_at"
        private const val KEY_LEARNED = "learned"
        private val LOCK = Any()

        private const val MAX_LIST_BYTES = 64 * 1024

        /** GET over HTTPS only, 10 s timeouts, at most [MAX_LIST_BYTES]; null on anything else. */
        fun httpsGet(url: String): String? {
            require(url.startsWith("https://"))
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_LIST_BYTES) return null
                    }
                    out.toString("UTF-8")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** Parsing and combining server lists - pure, so it's testable. */
object NodeLists {
    private const val MAX_LINES = 128
    private const val MAX_ADDRS_PER_PEER = 2

    fun isValid(address: String): Boolean = NodeLinks.looksLikeNodeAddress(address)

    /** One multiaddr per line, `#` comments and blanks skipped, only well-formed ones kept. */
    fun parse(text: String): List<String> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .filter(::isValid)
        .distinct()
        .take(MAX_LINES)
        .toList()

    /** [addresses] in order, without duplicates, at most [MAX_ADDRS_PER_PEER] per server (peer
     *  id) so one server can't crowd out the rest, and at most [max] in all. */
    fun diverse(addresses: List<String>, max: Int): List<String> {
        val perPeer = HashMap<String, Int>()
        return addresses.distinct().filter { address ->
            val peer = address.substringAfterLast("/p2p/")
            val count = perPeer.getOrDefault(peer, 0)
            perPeer[peer] = count + 1
            count < MAX_ADDRS_PER_PEER
        }.take(max)
    }
}
