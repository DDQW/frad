package app.frad.chat.contacts

import android.content.Context
import android.util.Log
import java.io.File
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.MessageKind
import app.frad.chat.crypto.SealedBox
import app.frad.chat.crypto.StorageCipher
import app.frad.chat.profile.Profile
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device transcript of past messages with a saved contact, keyed by peer id.
 * Only ever written for peers the user has explicitly saved via [ContactStore] -
 * a chat with anyone else leaves no trace once it ends, matching the app's
 * no-retention-by-default design. [app.frad.chat.chat.OpenChat] checks that before
 * writing, and [ContactStore.remove] clears history here too.
 *
 * Every transcript is encrypted with the Keystore-held key ([StorageCipher]); transcripts
 * written in plain JSON by older versions are re-encrypted the first time they're read.
 * Messages older than the user's retention setting ([Profile.historyRetentionDays]) are
 * dropped whenever a transcript is read or [pruneExpired] runs, and each transcript keeps
 * at most [MAX_MESSAGES_PER_PEER] messages.
 */
class ChatHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val profile = Profile(context)
    private val box: SealedBox by lazy { StorageCipher.box }

    fun messagesFor(peerId: String): List<ChatMessage> = synchronized(lock) {
        try {
            val stored = read(peerId) ?: return emptyList()
            keep(peerId, stored, ChatHistoryRetention.apply(stored, cutoffMillis(), MAX_MESSAGES_PER_PEER))
        } catch (e: KeystoreBusy) {
            emptyList()
        }
    }

    fun append(peerId: String, message: ChatMessage): Unit = synchronized(lock) {
        try {
            val messages = (read(peerId) ?: emptyList()) + message
            keep(peerId, messages, ChatHistoryRetention.apply(messages, cutoffMillis(), MAX_MESSAGES_PER_PEER), changed = true)
        } catch (e: Exception) {
            // Never overwrite a history we merely couldn't open right now; this one message
            // isn't saved, the chat itself goes on.
            Log.w(TAG, "Couldn't save a message to the history", e)
        }
    }

    /** Stores [kept] if it differs from [all] (or [changed] says [all] itself is new), deleting
     *  the received files of the messages that were dropped. */
    private fun keep(peerId: String, all: List<ChatMessage>, kept: List<ChatMessage>, changed: Boolean = false): List<ChatMessage> {
        if (kept.size == all.size) {
            if (changed) write(peerId, all)
            return all
        }
        val keptPaths = kept.mapNotNullTo(HashSet()) { it.localPath }
        all.forEach { message ->
            val path = message.localPath ?: return@forEach
            if (path !in keptPaths) runCatching { File(path).delete() }
        }
        write(peerId, kept)
        return kept
    }

    /** Records that the peer confirmed receiving our message [id] (see ChatEnvelope.Ack). */
    fun markDelivered(peerId: String, id: String): Unit = synchronized(lock) {
        val messages = try { read(peerId) } catch (e: KeystoreBusy) { null } ?: return
        if (messages.none { it.fromMe && it.id == id && !it.delivered }) return
        write(peerId, messages.map { if (it.fromMe && it.id == id) it.copy(delivered = true) else it })
    }

    /** Writes [messages] as this peer's history, but only if nothing is stored yet - used
     *  when a contact is saved mid-chat, to capture the messages already sent/received in
     *  that same session before the save happened. */
    fun backfillIfEmpty(peerId: String, messages: List<ChatMessage>): Unit = synchronized(lock) {
        if (messages.isEmpty() || prefs.contains(peerId)) return
        write(peerId, ChatHistoryRetention.apply(messages, cutoffMillis(), MAX_MESSAGES_PER_PEER))
    }

    fun clear(peerId: String): Unit = synchronized(lock) {
        prefs.edit().remove(peerId).apply()
    }

    /** Applies the retention setting to every stored transcript - run at start-up and whenever
     *  the setting is tightened, so expired messages don't linger until a chat is reopened. */
    fun pruneExpired(): Unit = synchronized(lock) {
        prefs.all.keys.forEach { messagesFor(it) }
    }

    private fun cutoffMillis(): Long? =
        profile.historyRetentionDays.takeIf { it > 0 }?.let { System.currentTimeMillis() - it * DAY_MILLIS }

    /** @return null if nothing is stored, or what's stored can't be read any more (e.g. the
     *  Keystore key is gone after the data was moved to another phone) - then it's dropped.
     *  @throws KeystoreBusy if the Keystore failed only for now: the history stays as it is. */
    private fun read(peerId: String): List<ChatMessage>? {
        val raw = prefs.getString(peerId, null) ?: return null
        return try {
            if (SealedBox.isSealed(raw)) {
                ChatMessageJson.decode(box.openString(raw))
            } else {
                ChatMessageJson.decode(raw).also { write(peerId, it) } // written before encryption existed
            }
        } catch (e: Exception) {
            if (SealedBox.isUnrecoverable(e)) {
                Log.w(TAG, "Dropping unreadable history", e)
                clear(peerId)
                null
            } else {
                throw KeystoreBusy(e)
            }
        }
    }

    private fun write(peerId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty()) {
            clear(peerId)
        } else {
            prefs.edit().putString(peerId, box.sealString(ChatMessageJson.encode(messages))).apply()
        }
    }

    companion object {
        const val MAX_MESSAGES_PER_PEER = 2_000
        private const val PREFS_FILE = "frad_chat_history"
        private const val TAG = "ChatHistoryStore"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** Shared by every instance (the BLE and wide-range controllers and the UI each have
         *  one, on different threads): a transcript is read, changed and written back whole. */
        private val lock = Any()
    }
}

/** Which messages a transcript keeps - pure, so it's testable without a device. */
internal object ChatHistoryRetention {
    /** Drops what's older than [cutoffMillis], what has disappeared by [nowMillis] (see
     *  [ChatMessage.expiresAtMillis]), and all but the last [maxMessages]. */
    fun apply(
        messages: List<ChatMessage>,
        cutoffMillis: Long?,
        maxMessages: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<ChatMessage> {
        val fresh = messages.filter { message ->
            (cutoffMillis == null || message.atMillis >= cutoffMillis) &&
                (message.expiresAtMillis == null || message.expiresAtMillis > nowMillis)
        }
        return if (fresh.size > maxMessages) fresh.takeLast(maxMessages) else fresh
    }
}

/** Pure JSON (de)serialization for [ChatMessage], split out from [ChatHistoryStore] so it's
 *  unit-testable without a real on-device `SharedPreferences`/`Context`. */
internal object ChatMessageJson {
    fun encode(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { message ->
            val obj = JSONObject()
                .put("fromMe", message.fromMe)
                .put("text", message.text)
                .put("atMillis", message.atMillis)
                .put("kind", message.kind.name)
            if (message.id != null) obj.put("id", message.id)
            if (message.delivered) obj.put("delivered", true)
            if (message.expiresAtMillis != null) obj.put("expiresAt", message.expiresAtMillis)
            if (message.kind == MessageKind.FILE) {
                obj.put("fileName", message.fileName)
                    .put("mimeType", message.mimeType)
                    .put("sizeBytes", message.sizeBytes)
                    .put("localPath", message.localPath)
            }
            array.put(obj)
        }
        return array.toString()
    }

    /** [MessageKind] defaults to `TEXT` for a missing `"kind"` key so transcripts saved by M2,
     *  before file messages existed, keep loading unchanged. */
    fun decode(raw: String): List<ChatMessage> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            ChatMessage(
                fromMe = obj.getBoolean("fromMe"),
                text = obj.getString("text"),
                atMillis = obj.getLong("atMillis"),
                kind = if (obj.has("kind")) MessageKind.valueOf(obj.getString("kind")) else MessageKind.TEXT,
                fileName = if (obj.has("fileName")) obj.getString("fileName") else null,
                mimeType = if (obj.has("mimeType")) obj.getString("mimeType") else null,
                sizeBytes = obj.optLong("sizeBytes", 0L),
                localPath = if (obj.has("localPath")) obj.getString("localPath") else null,
                id = if (obj.has("id")) obj.getString("id") else null,
                delivered = obj.optBoolean("delivered", false),
                expiresAtMillis = if (obj.has("expiresAt")) obj.getLong("expiresAt") else null,
            )
        }
    }
}

private class KeystoreBusy(cause: Throwable) : Exception("Keystore failed reading history", cause)
