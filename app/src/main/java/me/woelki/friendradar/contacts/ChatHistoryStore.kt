package me.woelki.friendradar.contacts

import android.content.Context
import me.woelki.friendradar.ble.ChatMessage
import me.woelki.friendradar.ble.MessageKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device transcript of past messages with a saved contact, keyed by peer id.
 * Only ever written for peers the user has explicitly saved via [ContactStore] -
 * a chat with anyone else leaves no trace once it ends, matching the app's
 * no-retention-by-default design. [me.woelki.friendradar.ble.BleChatController]
 * checks that before writing, and [ContactStore.remove] clears history here too.
 */
class ChatHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun messagesFor(peerId: String): List<ChatMessage> {
        val raw = prefs.getString(peerId, null) ?: return emptyList()
        return ChatMessageJson.decode(raw)
    }

    fun append(peerId: String, message: ChatMessage) {
        prefs.edit().putString(peerId, ChatMessageJson.encode(messagesFor(peerId) + message)).apply()
    }

    /** Writes [messages] as this peer's history, but only if nothing is stored yet - used
     *  when a contact is saved mid-chat, to capture the messages already sent/received in
     *  that same session before the save happened. */
    fun backfillIfEmpty(peerId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty() || prefs.contains(peerId)) return
        prefs.edit().putString(peerId, ChatMessageJson.encode(messages)).apply()
    }

    fun clear(peerId: String) {
        prefs.edit().remove(peerId).apply()
    }

    private companion object {
        const val PREFS_FILE = "friendradar_chat_history"
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
            )
        }
    }
}
