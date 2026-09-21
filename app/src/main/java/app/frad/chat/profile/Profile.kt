package app.frad.chat.profile

import android.content.Context
import kotlin.random.Random

/**
 * The user's local, freely-editable display name. Unlike
 * [app.frad.chat.crypto.Identity.peerId], a pseudonym is *not* required to be
 * unique — several people nearby can all call themselves "Alex". What actually
 * disambiguates a peer for chatting/contacts/blocking is still the cryptographic
 * peer id; [displayName] just shows a short tag derived from it alongside the
 * pseudonym so two "Alex"es remain visually distinct without either of them
 * having to pick a different name.
 */
class Profile(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    var pseudonym: String
        get() {
            val stored = prefs.getString(KEY_PSEUDONYM, null)
            if (stored != null) return stored
            val generated = defaultPseudonym()
            prefs.edit().putString(KEY_PSEUDONYM, generated).apply()
            return generated
        }
        set(value) {
            val trimmed = value.trim().take(MAX_LENGTH)
            prefs.edit().putString(KEY_PSEUDONYM, trimmed.ifEmpty { defaultPseudonym() }).apply()
        }

    private fun defaultPseudonym(): String = "Guest${Random.nextInt(1000, 10000)}"

    /** Set only via [app.frad.chat.wideradius.Geohash.encode] truncated to
     *  [app.frad.chat.wideradius.Geohash.MAX_PRECISION] or coarser (or typed in
     *  directly) — the raw coordinate it may have been derived from is never itself
     *  stored. Null until the user opts into the wide-range layer at all. */
    var coarseGeohash: String?
        get() = prefs.getString(KEY_GEOHASH, null)
        set(value) { prefs.edit().putString(KEY_GEOHASH, value?.trim()?.lowercase()?.ifEmpty { null }).apply() }

    /** How wide an area [coarseGeohash] should be truncated to when deriving a wide-range
     *  DHT rendezvous topic — see [app.frad.chat.wideradius.Geohash.precisionForRadiusKm]. */
    var searchRadiusKm: Double
        get() = prefs.getFloat(KEY_RADIUS_KM, DEFAULT_RADIUS_KM.toFloat()).toDouble()
        set(value) { prefs.edit().putFloat(KEY_RADIUS_KM, value.toFloat()).apply() }

    /** The user-configurable wide-range bootstrap/relay node list (each entry a full
     *  multiaddr, e.g. from a [p2p-go/cmd/bootstrap][app.frad.chat.wideradius] operator) —
     *  never shipped with real defaults baked in, see the root README. Empty by default. */
    var bootstrapNodes: List<String>
        get() = prefs.getString(KEY_BOOTSTRAP_NODES, null)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) { prefs.edit().putString(KEY_BOOTSTRAP_NODES, value.joinToString("\n")).apply() }

    /** Whether local BLE discovery/chat should keep running in the background - via
     *  [app.frad.chat.ble.LocalBleService] as a foreground service with a persistent
     *  notification, never silently - instead of only while the app is open. Defaults to true:
     *  an app whose whole purpose is meeting nearby people is of little use if both people
     *  happen to have it open on-screen at the same moment, so out-of-the-box FRAD favors
     *  actually being reachable over the more conservative "explicit opt-in every session"
     *  stance the app used to default to. Still a real, visible, one-tap-to-disable setting -
     *  not a silent background broadcast - and the manual "Become visible"/"Stop being visible"
     *  toggle in the Radar tab always works session-locally regardless of this setting. */
    var alwaysVisible: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_VISIBLE, true)
        set(value) { prefs.edit().putBoolean(KEY_ALWAYS_VISIBLE, value).apply() }

    companion object {
        private const val PREFS_FILE = "frad_profile"
        private const val KEY_PSEUDONYM = "pseudonym"
        private const val KEY_GEOHASH = "coarse_geohash"
        private const val KEY_RADIUS_KM = "search_radius_km"
        private const val KEY_BOOTSTRAP_NODES = "bootstrap_nodes"
        private const val KEY_ALWAYS_VISIBLE = "always_visible"
        private const val DEFAULT_RADIUS_KM = 75.0
        const val MAX_LENGTH = 24

        /** Short, stable suffix derived from a peer's long-term id, so that two people who
         *  both picked the same pseudonym still show up distinctly, e.g. "Alex#9F21A0". */
        fun displayTag(peerId: String): String = peerId.filter { it.isLetterOrDigit() }.take(6).uppercase()

        fun displayName(pseudonym: String, peerId: String): String = "$pseudonym#${displayTag(peerId)}"
    }
}
