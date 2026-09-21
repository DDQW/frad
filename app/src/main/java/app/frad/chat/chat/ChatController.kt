package app.frad.chat.chat

import kotlinx.coroutines.flow.StateFlow

/**
 * The behavior a discovery layer (BLE today, wide-range go-libp2p from M4) must provide to
 * drive [ChatUiState]. [app.frad.chat.ui.ChatViewModel] talks to whichever
 * [ChatController] is currently active through this interface only, so it never needs to know
 * which transport is underneath.
 */
interface ChatController {
    val state: StateFlow<ChatUiState>
    val transferStatus: StateFlow<String?>
    val fileTransferAvailable: Boolean

    fun setBrowsing(enabled: Boolean)
    fun requestRandomChat()
    fun sendMessage(text: String)
    fun sendFile(bytes: ByteArray, fileName: String, mimeType: String)
    fun endActiveConnection(reason: String)
    fun blockActivePeer()
    fun reportActivePeer(reason: String)
}
