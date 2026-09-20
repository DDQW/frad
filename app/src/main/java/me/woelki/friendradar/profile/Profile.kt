package me.woelki.friendradar.profile

import android.content.Context
import kotlin.random.Random

/**
 * The user's local, freely-editable display name. Unlike
 * [me.woelki.friendradar.crypto.Identity.peerId], a pseudonym is *not* required to be
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

    companion object {
        private const val PREFS_FILE = "friendradar_profile"
        private const val KEY_PSEUDONYM = "pseudonym"
        const val MAX_LENGTH = 24

        /** Short, stable suffix derived from a peer's long-term id, so that two people who
         *  both picked the same pseudonym still show up distinctly, e.g. "Alex#9F21A0". */
        fun displayTag(peerId: String): String = peerId.filter { it.isLetterOrDigit() }.take(6).uppercase()

        fun displayName(pseudonym: String, peerId: String): String = "$pseudonym#${displayTag(peerId)}"
    }
}
