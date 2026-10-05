package app.frad.chat.safety

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.MessageKind
import app.frad.chat.crypto.SealedBox
import app.frad.chat.crypto.StorageCipher
import app.frad.chat.profile.Profile

/**
 * There is no central server to send a report to — FRAD has no operator who could act on it.
 * "Reporting" here means: always block the peer immediately (so they can't reach this device
 * again), and keep a record of why and of what was said - the transcript, encrypted on this phone
 * (see [ReportEvidence]) - that the user can look at and export themselves, e.g. for the police.
 * Nothing leaves the device unless the user exports it.
 */
class ReportFlow(
    context: Context,
    private val blockList: BlockList = BlockList(context),
    private val evidence: ReportEvidence = ReportEvidence(context),
) {
    fun report(peerId: String, deviceFingerprint: String, pseudonym: String?, reason: String, messages: List<ChatMessage> = emptyList()) {
        blockList.block(peerId, deviceFingerprint, pseudonym, reason)
        evidence.save(peerId, ReportTranscript.format(peerId, pseudonym, reason, System.currentTimeMillis(), messages))
    }
}

/** Reports' transcripts, sealed with the Keystore key, keyed by the reported peer id (the
 *  [BlockEntry.key] of the block that came with the report). */
class ReportEvidence(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val box: SealedBox by lazy { StorageCipher.box }

    fun save(peerId: String, transcript: String) {
        prefs.edit().putString(peerId, box.sealString(transcript)).apply()
    }

    /** Null if there's none, or it can't be decrypted any more. */
    fun transcriptFor(peerId: String): String? =
        prefs.getString(peerId, null)?.let { runCatching { box.openString(it) }.getOrNull() }

    fun delete(peerId: String) {
        prefs.edit().remove(peerId).apply()
    }

    private companion object {
        const val PREFS_FILE = "frad_report_evidence"
    }
}

/** The plain-text record a report keeps and exports. */
internal object ReportTranscript {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(
        peerId: String,
        pseudonym: String?,
        reason: String,
        reportedAtMillis: Long,
        messages: List<ChatMessage>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        fun time(millis: Long) = timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))
        appendLine("FRAD chat report")
        appendLine("Reported: ${time(reportedAtMillis)} ($zone)")
        appendLine("Person: ${Profile.displayName(pseudonym ?: "Unknown", peerId)}")
        appendLine("Their FRAD id: $peerId")
        appendLine("Reason: $reason")
        appendLine()
        if (messages.isEmpty()) {
            appendLine("(no messages)")
        } else {
            appendLine("Chat:")
            messages.forEach { message ->
                val who = if (message.fromMe) "Me" else "Them"
                val content = when (message.kind) {
                    MessageKind.TEXT -> message.text
                    MessageKind.FILE -> "[file: ${message.fileName ?: "unnamed"}, ${message.mimeType ?: "unknown type"}, ${message.sizeBytes} bytes]"
                }
                appendLine("[${time(message.atMillis)}] $who: $content")
            }
        }
    }
}
