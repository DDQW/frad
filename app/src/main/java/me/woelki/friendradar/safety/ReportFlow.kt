package me.woelki.friendradar.safety

import android.content.Context

/**
 * There is no central server to send a report to — FRAD has no operator
 * who could act on it. "Reporting" here means: always block the peer immediately
 * (so they can't reach this device again), and keep a small local note of why,
 * purely for the user's own reference (e.g. if they want to recall later why a
 * particular id is blocked). Nothing leaves the device.
 */
class ReportFlow(context: Context, private val blockList: BlockList = BlockList(context)) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun report(peerId: String, deviceFingerprint: String, reason: String) {
        blockList.block(peerId, deviceFingerprint)
        prefs.edit().putString(peerId, reason).apply()
    }

    fun reasonFor(peerId: String): String? = prefs.getString(peerId, null)

    private companion object {
        const val PREFS_FILE = "friendradar_reports"
    }
}
