package app.frad.chat.contacts

import android.content.Context
import java.util.Base64

/** A peer saved on-device, keyed by their long-term peer id (see [app.frad.chat.crypto.Identity.peerId]).
 *  [staticKey] is their long-term public key, kept so the two can find each other again (see
 *  [app.frad.chat.crypto.ContactRendezvous]); null for contacts saved before that existed. */
data class Contact(val peerId: String, val alias: String, val savedAtMillis: Long, val staticKey: ByteArray? = null)

/**
 * On-device address book of peers the user chose to save after chatting with them.
 * Purely local — like [app.frad.chat.safety.BlockList], there is no server to
 * sync this with, so a saved contact only means something on this specific install.
 */
class ContactStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isSaved(peerId: String): Boolean = peerId in savedIds()

    fun save(peerId: String, alias: String, staticKey: ByteArray? = null) {
        val edit = prefs.edit()
            .putStringSet(KEY_IDS, savedIds() + peerId)
            .putString(aliasKey(peerId), alias)
            .putLong(savedAtKey(peerId), System.currentTimeMillis())
        if (staticKey != null) edit.putString(keyKey(peerId), Base64.getEncoder().encodeToString(staticKey))
        edit.apply()
    }

    /** Remembers [staticKey] for an already saved contact (they chatted again after an update). */
    fun rememberKey(peerId: String, staticKey: ByteArray) {
        if (isSaved(peerId) && !prefs.contains(keyKey(peerId))) {
            prefs.edit().putString(keyKey(peerId), Base64.getEncoder().encodeToString(staticKey)).apply()
        }
    }

    fun remove(peerId: String) {
        prefs.edit()
            .putStringSet(KEY_IDS, savedIds() - peerId)
            .remove(aliasKey(peerId))
            .remove(savedAtKey(peerId))
            .remove(keyKey(peerId))
            .apply()
    }

    fun all(): List<Contact> = savedIds().map { peerId ->
        Contact(
            peerId = peerId,
            alias = prefs.getString(aliasKey(peerId), peerId) ?: peerId,
            savedAtMillis = prefs.getLong(savedAtKey(peerId), 0L),
            staticKey = prefs.getString(keyKey(peerId), null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() },
        )
    }.sortedByDescending { it.savedAtMillis }

    private fun savedIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
    private fun aliasKey(peerId: String) = "alias_$peerId"
    private fun savedAtKey(peerId: String) = "saved_at_$peerId"
    private fun keyKey(peerId: String) = "static_key_$peerId"

    private companion object {
        const val PREFS_FILE = "frad_contacts"
        const val KEY_IDS = "contact_peer_ids"
    }
}
