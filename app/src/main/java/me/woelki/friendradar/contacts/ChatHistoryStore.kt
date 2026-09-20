package me.woelki.friendradar.contacts

import android.content.Context
import me.woelki.friendradar.ble.ChatMessage
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
        return decode(raw)
    }

    fun append(peerId: String, message: ChatMessage) {
        prefs.edit().putString(peerId, encode(messagesFor(peerId) + message)).apply()
    }

    /** Writes [messages] as this peer's history, but only if nothing is stored yet - used
     *  when a contact is saved mid-chat, to capture the messages already sent/received in
     *  that same session before the save happened. */
    fun backfillIfEmpty(peerId: String, messages: List<ChatMessage>) {
        if (messages.isEmpty() || prefs.contains(peerId)) return
        prefs.edit().putString(peerId, encode(messages)).apply()
    }

    fun clear(peerId: String) {
        prefs.edit().remove(peerId).apply()
    }

    private fun encode(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put("fromMe", message.fromMe)
                    .put("text", message.text)
                    .put("atMillis", message.atMillis),
            )
        }
        return array.toString()
    }

    private fun decode(raw: String): List<ChatMessage> {
        val array = JSONArray(raw)
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            ChatMessage(fromMe = obj.getBoolean("fromMe"), text = obj.getString("text"), atMillis = obj.getLong("atMillis"))
        }
    }

    private companion object {
        const val PREFS_FILE = "friendradar_chat_history"
    }
}
