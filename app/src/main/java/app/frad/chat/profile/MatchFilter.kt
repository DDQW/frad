package app.frad.chat.profile

/**
 * Who the user wants to be matched with (Profile, "Who to meet"). Nothing about a stranger is
 * known before the encrypted handshake, so this is checked right after it: a chat with someone
 * outside the filter never opens - an incoming one is declined, an outgoing one ended.
 *
 * Someone who doesn't share their age (or gender) isn't filtered out on that account.
 */
data class MatchFilter(val minAge: Int? = null, val maxAge: Int? = null, val gender: Gender? = null) {
    val isActive: Boolean get() = minAge != null || maxAge != null || gender != null

    fun accepts(profile: RemoteProfile): Boolean {
        val age = profile.age
        if (age != null && minAge != null && age < minAge) return false
        if (age != null && maxAge != null && age > maxAge) return false
        if (gender != null && profile.gender != null && profile.gender != gender) return false
        return true
    }
}
