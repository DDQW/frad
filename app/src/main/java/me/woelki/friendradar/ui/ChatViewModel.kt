package me.woelki.friendradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import me.woelki.friendradar.ble.BleChatController
import me.woelki.friendradar.ble.ChatUiState
import me.woelki.friendradar.crypto.Identity

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val identity = Identity.loadOrCreate(application)
    private val controller = BleChatController(application, identity)

    val state: StateFlow<ChatUiState> = controller.state
    val myPeerId: String get() = identity.peerId

    fun setBrowsing(enabled: Boolean) = controller.setBrowsing(enabled)
    fun requestRandomChat() = controller.requestRandomChat()
    fun sendMessage(text: String) = controller.sendMessage(text)
    fun endChat() = controller.endActiveConnection("you left")
    fun blockActivePeer() = controller.blockActivePeer()
    fun reportActivePeer(reason: String) = controller.reportActivePeer(reason)
}
