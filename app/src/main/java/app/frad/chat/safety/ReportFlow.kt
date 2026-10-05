package app.frad.chat.safety

import android.content.Context

/**
 * There is no central server to send a report to — FRAD has no operator
 * who could act on it. "Reporting" here means: always block the peer immediately
 * (so they can't reach this device again), and keep a small local note of why,
 * purely for the user's own reference (shown with the entry in the block list).
 * Nothing leaves the device.
 */
class ReportFlow(context: Context, private val blockList: BlockList = BlockList(context)) {
    fun report(peerId: String, deviceFingerprint: String, pseudonym: String?, reason: String) {
        blockList.block(peerId, deviceFingerprint, pseudonym, reason)
    }
}
