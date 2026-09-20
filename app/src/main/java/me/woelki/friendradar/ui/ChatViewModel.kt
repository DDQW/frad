package me.woelki.friendradar.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.woelki.friendradar.ble.BleChatController
import me.woelki.friendradar.chat.ChatController
import me.woelki.friendradar.chat.ChatMessage
import me.woelki.friendradar.chat.ChatUiState
import me.woelki.friendradar.chat.MAX_TRANSFER_FILE_BYTES
import me.woelki.friendradar.contacts.ChatHistoryStore
import me.woelki.friendradar.contacts.Contact
import me.woelki.friendradar.contacts.ContactStore
import me.woelki.friendradar.crypto.Identity
import me.woelki.friendradar.data.MediaFileStore
import me.woelki.friendradar.profile.Profile
import me.woelki.friendradar.safety.BlockList

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val identity = Identity.loadOrCreate(application)
    private val profile = Profile(application)
    private val contactStore = ContactStore(application)
    private val historyStore = ChatHistoryStore(application)
    private val mediaFileStore = MediaFileStore(application)
    private val blockList = BlockList(application)
    private val controller: ChatController = BleChatController(application, identity, profile)

    val state: StateFlow<ChatUiState> = controller.state
    val transferStatus: StateFlow<String?> = controller.transferStatus
    val fileTransferAvailable: Boolean get() = controller.fileTransferAvailable
    val myPeerId: String get() = identity.peerId

    private val _errorEvent = MutableStateFlow<String?>(null)
    val errorEvent: StateFlow<String?> = _errorEvent.asStateFlow()
    fun consumeErrorEvent() { _errorEvent.value = null }

    var myPseudonym: String
        get() = profile.pseudonym
        set(value) { profile.pseudonym = value }

    fun setBrowsing(enabled: Boolean) = controller.setBrowsing(enabled)
    fun requestRandomChat() = controller.requestRandomChat()
    fun sendMessage(text: String) = controller.sendMessage(text)
    fun endChat() = controller.endActiveConnection("you left")
    fun blockActivePeer() = controller.blockActivePeer()
    fun reportActivePeer(reason: String) = controller.reportActivePeer(reason)

    /** Reads [uri] fully into memory (files this small are the whole point of the 25 MB cap)
     *  and hands it to [BleChatController.sendFile]; surfaces [errorEvent] instead of sending
     *  if the file can't be read or is over the cap. */
    fun sendFile(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            _errorEvent.value = "Couldn't read that file."
            return
        }
        if (bytes.size > MAX_TRANSFER_FILE_BYTES) {
            _errorEvent.value = "That file is too large to send (max ${MAX_TRANSFER_FILE_BYTES / (1024 * 1024)} MB)."
            return
        }
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val fileName = displayNameOf(resolver, uri) ?: "file"
        controller.sendFile(bytes, fileName, mimeType)
    }

    private fun displayNameOf(resolver: ContentResolver, uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return null
    }

    fun contacts(): List<Contact> = contactStore.all()
    fun isContactSaved(peerId: String): Boolean = contactStore.isSaved(peerId)

    fun saveContact(peerId: String, alias: String) {
        contactStore.save(peerId, alias)
        // Backfill this session's messages (sent/received before the save happened, so
        // BleChatController hadn't started persisting them yet) into their history.
        val current = state.value
        if (current is ChatUiState.Chatting && current.remotePeerId == peerId) {
            historyStore.backfillIfEmpty(peerId, current.messages)
        }
    }

    fun removeContact(peerId: String) {
        contactStore.remove(peerId)
        historyStore.clear(peerId)
        mediaFileStore.delete(peerId)
    }

    fun historyWith(peerId: String): List<ChatMessage> = historyStore.messagesFor(peerId)

    fun blockedPeerIds(): List<String> = blockList.blockedIds().toList()
    fun unblock(peerId: String) = blockList.unblock(peerId)
}
