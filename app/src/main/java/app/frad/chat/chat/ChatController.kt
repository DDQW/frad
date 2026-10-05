package app.frad.chat.chat

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The behavior a discovery layer (BLE today, wide-range go-libp2p from M4) must provide to
 * drive [ChatUiState]. [app.frad.chat.ui.ChatViewModel] talks to whichever
 * [ChatController] is currently active through this interface only, so it never needs to know
 * which transport is underneath.
 */
interface ChatController {
    val state: StateFlow<ChatUiState>

    /** One-off messages for the user that aren't a state of their own - e.g. why tapping
     *  "Chat with someone nearby" did nothing. */
    val notices: SharedFlow<String>
    val transferStatus: StateFlow<String?>

    /** 0..1 while a file is being transferred, null while that isn't known (or nothing runs). */
    val transferProgress: StateFlow<Float?>
    val fileTransferAvailable: Boolean

    fun setBrowsing(enabled: Boolean)

    /** Whether FRAD is on screen - a layer may do less in the background (see BLE scanning). */
    fun setUiVisible(visible: Boolean) {}
    fun requestRandomChat()
    fun sendMessage(text: String)

    /** The user is typing in the open chat; the peer is told so (throttled). */
    fun notifyTyping()
    /** Asks the peer first (see [ChatEnvelope.FileRequest]) and sends only if they accept. */
    fun sendFile(bytes: ByteArray, fileName: String, mimeType: String)

    /** The user's answer to [ChatUiState.Chatting.incomingFile]. */
    fun answerIncomingFile(accept: Boolean)

    /** Stops the file transfer - or the request for one - that [transferStatus] describes. */
    fun cancelTransfer()
    fun endActiveConnection(reason: String)
    fun blockActivePeer()
    fun reportActivePeer(reason: String)

    /** The user has seen an [ChatUiState.Ended] message shown while not browsing - go back to
     *  [ChatUiState.Idle] so they can start again. */
    fun acknowledgeEnded()
}
