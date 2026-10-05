package app.frad.chat.chat

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import app.frad.chat.contacts.ChatHistoryStore
import app.frad.chat.contacts.ContactStore

/**
 * What both controllers do with a chat once it's open, independent of the transport: the
 * transcript (persisted only for saved contacts - see [ChatHistoryStore]), message ids and
 * delivery receipts, and the typing indicator. Lives on the owning controller's serial
 * dispatcher, like everything else there.
 */
internal class OpenChat(
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val contactStore: ContactStore,
    private val historyStore: ChatHistoryStore,
) {
    private var lastTypingSentAtMillis = 0L
    private var typingTimeout: Job? = null

    /** Shows the now-open chat with [peer], including any saved history with them. */
    fun start(peer: RemotePeer, ourPublicKey: ByteArray) {
        state.value = ChatUiState.Chatting(
            remotePeerId = peer.peerId,
            remoteDeviceFingerprint = peer.deviceFingerprint,
            remotePseudonym = peer.profile.pseudonym,
            remoteGender = peer.profile.gender,
            remoteAge = peer.profile.age,
            remoteBio = peer.profile.bio,
            remotePhoto = peer.profile.photo,
            messages = if (contactStore.isSaved(peer.peerId)) historyStore.messagesFor(peer.peerId) else emptyList(),
            safetyNumber = SafetyNumber.of(ourPublicKey, peer.staticKey),
        )
    }

    fun end() {
        typingTimeout?.cancel()
        typingTimeout = null
        lastTypingSentAtMillis = 0L
    }

    /** Adds [message] to the open chat's transcript, persisting it for saved contacts. */
    fun append(message: ChatMessage) {
        val current = state.value as? ChatUiState.Chatting ?: return
        state.value = current.copy(messages = current.messages + message)
        if (contactStore.isSaved(current.remotePeerId)) historyStore.append(current.remotePeerId, message)
    }

    suspend fun sendText(chat: ChatConnection, text: String) {
        val id = UUID.randomUUID().toString()
        append(ChatMessage(fromMe = true, text = text, atMillis = System.currentTimeMillis(), id = id))
        lastTypingSentAtMillis = 0L // the next keystroke may announce typing again right away
        chat.send(ChatEnvelope.Text(text, id))
    }

    /** Throttled: tells the peer we're typing at most every [TYPING_RESEND_MILLIS]. */
    suspend fun sendTyping(chat: ChatConnection) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAtMillis < TYPING_RESEND_MILLIS) return
        lastTypingSentAtMillis = now
        chat.send(ChatEnvelope.Typing)
    }

    /** Handles the envelopes every transport treats alike; anything else (file offers, unknown
     *  kinds) is returned for the controller to deal with. */
    suspend fun onReceived(chat: ChatConnection, envelope: ChatEnvelope): ChatEnvelope? {
        when (envelope) {
            is ChatEnvelope.Text -> {
                setPeerTyping(false)
                append(ChatMessage(fromMe = false, text = envelope.text, atMillis = System.currentTimeMillis()))
                envelope.id?.let { chat.send(ChatEnvelope.Ack(it)) }
            }
            is ChatEnvelope.Ack -> markDelivered(envelope.id)
            ChatEnvelope.Typing -> {
                setPeerTyping(true)
                typingTimeout?.cancel()
                typingTimeout = scope.launch {
                    delay(TYPING_SHOWN_MILLIS)
                    setPeerTyping(false)
                }
            }
            else -> return envelope
        }
        return null
    }

    private fun markDelivered(id: String) {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.messages.none { it.fromMe && it.id == id }) return
        state.value = current.copy(messages = current.messages.map { if (it.fromMe && it.id == id) it.copy(delivered = true) else it })
        if (contactStore.isSaved(current.remotePeerId)) historyStore.markDelivered(current.remotePeerId, id)
    }

    private fun setPeerTyping(typing: Boolean) {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.peerTyping != typing) state.value = current.copy(peerTyping = typing)
    }

    private companion object {
        const val TYPING_RESEND_MILLIS = 3_000L
        const val TYPING_SHOWN_MILLIS = 5_000L
    }
}
