package app.frad.chat.safety

import android.content.Context

/**
 * Locally-stored set of blocked identifiers — both peer ids (see
 * [app.frad.chat.crypto.Identity.peerId] / [app.frad.chat.crypto.ChatSession.remotePeerId])
 * and device fingerprints (see [DeviceFingerprint]) live in the same set, so a block matches on
 * either: someone who resets their identity keypair but keeps the same physical device still gets
 * caught by their device fingerprint (M5). Purely on-device — there is no server to sync a block
 * list with, so blocking someone on one device does not protect a different install of the app
 * the same person might use.
 */
class BlockList(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** True if any of [ids] (typically a peer id and/or a device fingerprint) is blocked. */
    fun isBlocked(vararg ids: String): Boolean = blockedIds().let { blocked -> ids.any { it in blocked } }

    /** Blocks all of [ids] together — call with both a peer id and a device fingerprint so a
     *  block outlives an identity reset. */
    fun block(vararg ids: String) {
        prefs.edit().putStringSet(KEY_BLOCKED, blockedIds() + ids).apply()
    }

    fun unblock(peerId: String) {
        prefs.edit().putStringSet(KEY_BLOCKED, blockedIds() - peerId).apply()
    }

    fun blockedIds(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    private companion object {
        const val PREFS_FILE = "frad_blocklist"
        const val KEY_BLOCKED = "blocked_peer_ids"
    }
}
