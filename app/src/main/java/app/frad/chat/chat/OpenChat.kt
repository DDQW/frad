package app.frad.chat.chat

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import app.frad.chat.contacts.ChatHistoryStore
import app.frad.chat.contacts.ContactStore

/**
 * What both controllers do with a chat once it's open, independent of the transport: the
 * transcript (persisted only for saved contacts - see [ChatHistoryStore]), message ids and
 * delivery receipts, the typing indicator, and asking before a file is transferred (see
 * [ChatEnvelope.FileRequest]). Lives on the owning controller's serial dispatcher, like
 * everything else there.
 *
 * @param canReceiveFile whether the controller could take a file right now (transport available,
 *   no other transfer running); requests arriving otherwise are declined automatically.
 */
internal class OpenChat(
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val contactStore: ContactStore,
    private val historyStore: ChatHistoryStore,
    private val canReceiveFile: () -> Boolean,
) {
    private var lastTypingSentAtMillis = 0L
    private var typingTimeout: Job? = null

    /** Our [ChatEnvelope.FileRequest]s waiting for the peer's answer (null: chat ended). */
    private val awaitingReply = HashMap<String, CompletableDeferred<Boolean?>>()

    /** The peer's request shown to the user, and when it expires unanswered. */
    private var incomingRequest: FileOffer? = null
    private var incomingRequestExpiry: Job? = null

    /** The request the user (or the saved-contact rule) said yes to, until the transport's own
     *  offer for it arrives - see [takeAcceptedFile]. */
    private var acceptedFile: FileOffer? = null
    private var acceptedAtMillis = 0L

    enum class FileAnswer { ACCEPTED, DECLINED, NO_ANSWER }

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
            remoteInterests = peer.profile.interests,
        )
    }

    fun end() {
        typingTimeout?.cancel()
        typingTimeout = null
        lastTypingSentAtMillis = 0L
        awaitingReply.values.forEach { it.complete(null) }
        awaitingReply.clear()
        incomingRequestExpiry?.cancel()
        incomingRequestExpiry = null
        incomingRequest = null
        acceptedFile = null
    }

    /**
     * Asks the peer whether they want [offer] and waits for the answer (at most
     * [FILE_REPLY_TIMEOUT_MILLIS]). Claims the transfer id, so the caller mustn't again.
     */
    suspend fun requestToSend(chat: ChatConnection, offer: FileOffer): FileAnswer {
        chat.claimTransferId(offer.transferId)
        val reply = CompletableDeferred<Boolean?>()
        awaitingReply[offer.transferId] = reply
        return try {
            chat.send(ChatEnvelope.FileRequest(offer))
            when (withTimeoutOrNull(FILE_REPLY_TIMEOUT_MILLIS) { reply.await() }) {
                true -> FileAnswer.ACCEPTED
                false -> FileAnswer.DECLINED
                null -> FileAnswer.NO_ANSWER
            }
        } finally {
            awaitingReply.remove(offer.transferId)
        }
    }

    /** The user's answer to the request shown in [ChatUiState.Chatting.incomingFile]. */
    suspend fun answerFileRequest(chat: ChatConnection, accept: Boolean) {
        val offer = incomingRequest ?: return
        clearIncomingRequest()
        if (accept) accept(offer)
        chat.send(ChatEnvelope.FileReply(offer.transferId, accept))
    }

    /** The offer for [transferId] if it was accepted (and not too long ago) - consumed, so one
     *  yes lets exactly one file through. Anything else must be refused by the caller. */
    fun takeAcceptedFile(transferId: String): FileOffer? {
        val offer = acceptedFile?.takeIf { it.transferId == transferId } ?: return null
        acceptedFile = null
        return offer.takeIf { acceptStillValid() }
    }

    private fun acceptStillValid(): Boolean =
        System.currentTimeMillis() - acceptedAtMillis <= FILE_REPLY_TIMEOUT_MILLIS + FILE_START_GRACE_MILLIS

    private fun accept(offer: FileOffer) {
        acceptedFile = offer
        acceptedAtMillis = System.currentTimeMillis()
    }

    private suspend fun onFileRequest(chat: ChatConnection, offer: FileOffer) {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (!chat.claimTransferId(offer.transferId)) return // a repeated id would repeat its key
        // One file at a time: another request still on screen, or accepted and about to arrive.
        val busy = incomingRequest != null || (acceptedFile != null && acceptStillValid())
        if (offer.sizeBytes > MAX_TRANSFER_FILE_BYTES || busy || !canReceiveFile()) {
            chat.send(ChatEnvelope.FileReply(offer.transferId, false))
            return
        }
        if (contactStore.isSaved(current.remotePeerId)) {
            accept(offer)
            chat.send(ChatEnvelope.FileReply(offer.transferId, true))
            return
        }
        incomingRequest = offer
        state.value = current.copy(incomingFile = offer)
        incomingRequestExpiry = scope.launch {
            delay(FILE_REPLY_TIMEOUT_MILLIS)
            clearIncomingRequest() // the sender has given up by now
        }
    }

    private fun clearIncomingRequest() {
        incomingRequestExpiry?.cancel()
        incomingRequestExpiry = null
        incomingRequest = null
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.incomingFile != null) state.value = current.copy(incomingFile = null)
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
            is ChatEnvelope.FileRequest -> onFileRequest(chat, envelope.offer)
            is ChatEnvelope.FileReply -> awaitingReply[envelope.transferId]?.complete(envelope.accepted)
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

    companion object {
        private const val TYPING_RESEND_MILLIS = 3_000L
        private const val TYPING_SHOWN_MILLIS = 5_000L

        /** How long a file request waits for the other person to decide. */
        const val FILE_REPLY_TIMEOUT_MILLIS = 120_000L

        /** After a yes, how long the sender may take to actually start the transfer. */
        private const val FILE_START_GRACE_MILLIS = 30_000L
    }
}
