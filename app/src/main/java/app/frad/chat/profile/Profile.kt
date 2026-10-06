package app.frad.chat.profile

import android.content.Context
import kotlin.random.Random

enum class Gender { MALE, FEMALE }

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
            val cleaned = TextSanitizer.pseudonym(value)
            prefs.edit().putString(KEY_PSEUDONYM, cleaned.ifEmpty { defaultPseudonym() }).apply()
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

    /** Wide-range: besides the servers set here, also use the public ones - FRAD's official list
     *  and servers learned from other servers and phones (see wideradius/NodeDirectory). On by
     *  default, so wide-range works without anyone having to find a server first. */
    var usePublicNodes: Boolean
        get() = prefs.getBoolean(KEY_USE_PUBLIC_NODES, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_PUBLIC_NODES, value).apply() }

    /** Wide-range only: route every connection through the configured bootstrap/relay nodes,
     *  so chat partners and other DHT participants never learn this device's IP address -
     *  which would otherwise pin it down far more precisely than the shared geohash cell does.
     *  Off by default: everything gets slower and depends on the relays, and peers without a
     *  relay reservation of their own can't be reached. See p2p-go's Config.RelayOnly. */
    var wideRangeRelayOnly: Boolean
        get() = prefs.getBoolean(KEY_RELAY_ONLY, false)
        set(value) { prefs.edit().putBoolean(KEY_RELAY_ONLY, value).apply() }

    /** Require fingerprint/face or the phone's PIN to open FRAD, and keep its screens out of the
     *  recent-apps overview and screenshots - see [app.frad.chat.AppLock]. */
    var appLock: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(value) { prefs.edit().putBoolean(KEY_APP_LOCK, value).apply() }

    /** Don't send the profile photo with the profile; it's only swapped once both people in a
     *  chat agree (see [app.frad.chat.chat.ChatEnvelope.PhotoRequest]). */
    var photoOnRequest: Boolean
        get() = prefs.getBoolean(KEY_PHOTO_ON_REQUEST, false)
        set(value) { prefs.edit().putBoolean(KEY_PHOTO_ON_REQUEST, value).apply() }

    /** Keep (redacted) crash reports on the phone for the user to share - see
     *  [app.frad.chat.diagnostics.CrashReports]. Off unless the user turns it on. */
    var crashReports: Boolean
        get() = prefs.getBoolean(KEY_CRASH_REPORTS, false)
        set(value) { prefs.edit().putBoolean(KEY_CRASH_REPORTS, value).apply() }

    /** Shown to matches (see [Interest]); at most [Interest.MAX_PER_PROFILE]. */
    var interests: Set<Interest>
        get() = prefs.getString(KEY_INTERESTS, null)?.split(',')?.mapNotNull(Interest::fromKey)?.toSet() ?: emptySet()
        set(value) {
            prefs.edit().putString(KEY_INTERESTS, value.take(Interest.MAX_PER_PROFILE).joinToString(",") { it.key }).apply()
        }

    /** When being visible (browsing) should end by itself, in epoch millis; 0 = until switched
     *  off. Checked by the controllers while browsing - see the Radar tab's "Stay visible". */
    var visibleUntilMillis: Long
        get() = prefs.getLong(KEY_VISIBLE_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_VISIBLE_UNTIL, value).apply() }

    /** Saved chats with contacts are deleted after this many days; 0 keeps them until the
     *  contact or the chat is deleted. See [app.frad.chat.contacts.ChatHistoryStore]. */
    var historyRetentionDays: Int
        get() = prefs.getInt(KEY_HISTORY_RETENTION_DAYS, 0)
        set(value) { prefs.edit().putInt(KEY_HISTORY_RETENTION_DAYS, value.coerceAtLeast(0)).apply() }

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

    /** Shown to whoever you match with, alongside the pseudonym (see [ProfileEnvelope]). Chosen by
     *  the user during onboarding (see [onboarded]) - never made up: null until then. */
    var gender: Gender?
        get() = prefs.getString(KEY_GENDER, null)?.let { runCatching { Gender.valueOf(it) }.getOrNull() }
        set(value) { prefs.edit().putString(KEY_GENDER, value?.name).apply() }

    /** Required (asked during onboarding, at least [MIN_AGE]) - it decides which age group you can
     *  be matched with, see [isAdult]. Whether the number itself is shown to matches is
     *  [shareAge]. Null until set, or if a stored value is outside the allowed range. */
    var age: Int?
        get() = prefs.getInt(KEY_AGE, -1).takeIf { it in MIN_AGE..MAX_AGE }
        set(value) { prefs.edit().putInt(KEY_AGE, value?.coerceIn(MIN_AGE, MAX_AGE) ?: -1).apply() }

    /** Whether matches see your [age]; the age group ([isAdult]) is always exchanged regardless. */
    var shareAge: Boolean
        get() = prefs.getBoolean(KEY_SHARE_AGE, true)
        set(value) { prefs.edit().putBoolean(KEY_SHARE_AGE, value).apply() }

    /** Adults are only ever matched with adults and minors with minors - see
     *  [app.frad.chat.chat.ChatConnection]. Self-declared, like everything in a no-account app. */
    val isAdult: Boolean get() = (age ?: 0) >= ADULT_AGE

    /** Whether the first-run setup (pseudonym, gender, age) has been completed. Nothing is
     *  advertised or exchanged with anyone before that. */
    val onboarded: Boolean get() = prefs.getBoolean(KEY_ONBOARDED, false) && gender != null && age != null

    fun completeOnboarding(pseudonym: String, gender: Gender, age: Int, shareAge: Boolean) {
        require(age >= MIN_AGE) { "FRAD is for people aged $MIN_AGE and over" }
        this.pseudonym = pseudonym
        this.gender = gender
        this.age = age
        this.shareAge = shareAge
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** Optional short free-text description, shown to whoever you match with. */
    var bio: String
        get() = prefs.getString(KEY_BIO, null) ?: ""
        set(value) { prefs.edit().putString(KEY_BIO, TextSanitizer.clean(value, MAX_BIO_LENGTH, allowNewlines = true)).apply() }

    companion object {
        private const val PREFS_FILE = "frad_profile"
        private const val KEY_PSEUDONYM = "pseudonym"
        private const val KEY_GEOHASH = "coarse_geohash"
        private const val KEY_RADIUS_KM = "search_radius_km"
        private const val KEY_BOOTSTRAP_NODES = "bootstrap_nodes"
        private const val KEY_ALWAYS_VISIBLE = "always_visible"
        private const val KEY_RELAY_ONLY = "wide_range_relay_only"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_HISTORY_RETENTION_DAYS = "history_retention_days"
        private const val KEY_VISIBLE_UNTIL = "visible_until"
        private const val KEY_INTERESTS = "interests"
        private const val KEY_PHOTO_ON_REQUEST = "photo_on_request"
        private const val KEY_USE_PUBLIC_NODES = "use_public_nodes"
        private const val KEY_CRASH_REPORTS = "crash_reports"
        private const val KEY_GENDER = "gender"
        private const val KEY_AGE = "age"
        private const val KEY_SHARE_AGE = "share_age"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_BIO = "bio"
        private const val DEFAULT_RADIUS_KM = 75.0
        const val MAX_LENGTH = 24
        const val MAX_BIO_LENGTH = 140
        const val MIN_AGE = 16
        const val ADULT_AGE = 18
        const val MAX_AGE = 120

        /** Short, stable suffix derived from a peer's long-term id, so that two people who
         *  both picked the same pseudonym still show up distinctly, e.g. "Alex#9F21A0". */
        fun displayTag(peerId: String): String = peerId.filter { it.isLetterOrDigit() }.take(6).uppercase()

        fun displayName(pseudonym: String, peerId: String): String = "$pseudonym#${displayTag(peerId)}"
    }
}
