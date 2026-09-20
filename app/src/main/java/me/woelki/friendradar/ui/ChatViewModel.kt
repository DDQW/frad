package me.woelki.friendradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import me.woelki.friendradar.ble.BleChatController
import me.woelki.friendradar.ble.ChatMessage
import me.woelki.friendradar.ble.ChatUiState
import me.woelki.friendradar.contacts.ChatHistoryStore
import me.woelki.friendradar.contacts.Contact
import me.woelki.friendradar.contacts.ContactStore
import me.woelki.friendradar.crypto.Identity
import me.woelki.friendradar.profile.Profile
import me.woelki.friendradar.safety.BlockList

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val identity = Identity.loadOrCreate(application)
    private val profile = Profile(application)
    private val contactStore = ContactStore(application)
    private val historyStore = ChatHistoryStore(application)
    private val blockList = BlockList(application)
    private val controller = BleChatController(application, identity, profile)

    val state: StateFlow<ChatUiState> = controller.state
    val myPeerId: String get() = identity.peerId

    var myPseudonym: String
        get() = profile.pseudonym
        set(value) { profile.pseudonym = value }

    fun setBrowsing(enabled: Boolean) = controller.setBrowsing(enabled)
    fun requestRandomChat() = controller.requestRandomChat()
    fun sendMessage(text: String) = controller.sendMessage(text)
    fun endChat() = controller.endActiveConnection("you left")
    fun blockActivePeer() = controller.blockActivePeer()
    fun reportActivePeer(reason: String) = controller.reportActivePeer(reason)

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
    }

    fun historyWith(peerId: String): List<ChatMessage> = historyStore.messagesFor(peerId)

    fun blockedPeerIds(): List<String> = blockList.blockedIds().toList()
    fun unblock(peerId: String) = blockList.unblock(peerId)
}
