package me.woelki.friendradar.safety

import android.content.Context

/**
 * Locally-stored set of blocked peer ids (see [me.woelki.friendradar.crypto.Identity.peerId] /
 * [me.woelki.friendradar.crypto.ChatSession.remotePeerId]). Purely on-device — there is no
 * server to sync a block list with, so blocking someone on one device does not
 * protect a different install of the app the same person might use.
 */
class BlockList(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isBlocked(peerId: String): Boolean = peerId in blockedIds()

    fun block(peerId: String) {
        prefs.edit().putStringSet(KEY_BLOCKED, blockedIds() + peerId).apply()
    }

    fun unblock(peerId: String) {
        prefs.edit().putStringSet(KEY_BLOCKED, blockedIds() - peerId).apply()
    }

    fun blockedIds(): Set<String> = prefs.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()

    private companion object {
        const val PREFS_FILE = "friendradar_blocklist"
        const val KEY_BLOCKED = "blocked_peer_ids"
    }
}
