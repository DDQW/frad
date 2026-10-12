package app.frad.chat.safety

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One blocked person: every identifier that should keep matching them - their long-term peer id
 * (see [app.frad.chat.crypto.Identity.peerId]) and the device fingerprint they presented (see
 * [DeviceFingerprint]), so a block outlives them resetting their identity (M5) - plus what the user
 * needs to recognise the entry later. [key] identifies the entry for [BlockList.unblock].
 */
data class BlockEntry(
    val ids: List<String>,
    val pseudonym: String?,
    val blockedAtMillis: Long,
    /** Set when the block came from a report (see [ReportFlow]). */
    val reason: String?,
) {
    val key: String get() = ids.first()
}

/**
 * Locally-stored list of blocked people. Purely on-device — there is no server to sync a block
 * list with, so blocking someone on one device does not protect a different install of the app
 * the same person might use.
 */
class BlockList(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val legacyReasonPrefs = context.getSharedPreferences(LEGACY_REPORTS_FILE, Context.MODE_PRIVATE)

    /** True if [id] (a peer id or a device fingerprint) belongs to any blocked person. */
    fun isBlocked(id: String): Boolean = entries().any { id in it.ids }

    /** Blocks the person behind [peerId] and [deviceFingerprint] as one entry. */
    fun block(peerId: String, deviceFingerprint: String, pseudonym: String?, reason: String? = null) = synchronized(LOCK) {
        val others = entries().filterNot { peerId in it.ids || deviceFingerprint in it.ids }
        val entry = BlockEntry(listOf(peerId, deviceFingerprint), pseudonym, System.currentTimeMillis(), reason)
        save(listOf(entry) + others)
    }

    /** Removes the whole entry - both its peer id and device fingerprint, so the person is
     *  actually reachable again. */
    fun unblock(key: String) = synchronized(LOCK) {
        save(entries().filterNot { it.key == key })
    }

    /** Newest first. */
    fun entries(): List<BlockEntry> = synchronized(LOCK) {
        migrateLegacy()
        BlockEntryJson.decode(prefs.getString(KEY_ENTRIES, null) ?: "[]")
    }

    private fun save(entries: List<BlockEntry>) {
        prefs.edit().putString(KEY_ENTRIES, BlockEntryJson.encode(entries.sortedByDescending { it.blockedAtMillis })).apply()
    }

    /** Builds before 0.3.26 kept a flat set of ids (peer ids and fingerprints mixed, nothing
     *  saying which belonged together) and report reasons in a separate file keyed by peer id.
     *  Each old id becomes its own entry, keeping its reason if it had one. */
    private fun migrateLegacy() {
        val legacy = prefs.getStringSet(KEY_LEGACY_IDS, null) ?: return
        val reasons = legacyReasonPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        val migrated = legacy.map { BlockEntry(listOf(it), pseudonym = null, blockedAtMillis = 0L, reason = reasons[it]) }
        val existing = BlockEntryJson.decode(prefs.getString(KEY_ENTRIES, null) ?: "[]")
        prefs.edit()
            .putString(KEY_ENTRIES, BlockEntryJson.encode(existing + migrated))
            .remove(KEY_LEGACY_IDS)
            .apply()
        legacyReasonPrefs.edit().clear().apply()
    }

    private companion object {
        val LOCK = Any()
        const val PREFS_FILE = "frad_blocklist"
        const val KEY_ENTRIES = "entries_v2"
        const val KEY_LEGACY_IDS = "blocked_peer_ids"
        const val LEGACY_REPORTS_FILE = "frad_reports"
    }
}

/** Pure JSON (de)serialization for [BlockEntry], unit-testable without Android storage. */
internal object BlockEntryJson {
    fun encode(entries: List<BlockEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
                .put("ids", JSONArray(entry.ids))
                .put("at", entry.blockedAtMillis)
            entry.pseudonym?.let { obj.put("name", it) }
            entry.reason?.let { obj.put("reason", it) }
            array.put(obj)
        }
        return array.toString()
    }

    fun decode(raw: String): List<BlockEntry> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            val idsArray = obj.optJSONArray("ids") ?: return@mapNotNull null
            val ids = (0 until idsArray.length()).map { idsArray.getString(it) }
            if (ids.isEmpty()) return@mapNotNull null
            BlockEntry(
                ids = ids,
                pseudonym = obj.optString("name", "").ifEmpty { null },
                blockedAtMillis = obj.optLong("at", 0L),
                reason = obj.optString("reason", "").ifEmpty { null },
            )
        }
    }
}
