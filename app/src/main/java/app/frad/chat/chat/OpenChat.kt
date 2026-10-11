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
import app.frad.chat.profile.MatchFilter
import app.frad.chat.profile.ProfileEnvelope

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
    /** Our profile photo, for a photo swap (null: we have none). */
    private val ownPhoto: () -> ByteArray? = { null },
    /** Whether our profile keeps the photo back for a swap (Profile.photoOnRequest). */
    private val photoOnRequest: () -> Boolean = { false },
    /** Who the user wants to meet (Profile.matchFilter); saved contacts always get through. */
    private val matchFilter: () -> MatchFilter = { MatchFilter() },
    /** Ends the chat for one of [EndReason]'s reasons - the controller says why in the UI. */
    private val endChat: (EndReason) -> Unit = {},
) {
    enum class EndReason { THEY_DECLINED, WE_DECLINED, NO_ANSWER, FILTERED }

    /** Ends a chat request nobody answered (see [ChatUiState.Chatting.acceptance]). */
    private var acceptTimeout: Job? = null

    /** Removes disappearing messages (see [ChatMessage.expiresAtMillis]) while the chat is open. */
    private var expiryJob: Job? = null

    /** Whether our photo went to the peer during this chat already (in the profile or a swap). */
    private var ourPhotoSent = false

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

    /**
     * Shows the now-open chat with [peer], including any saved history with them - as a request
     * first: whoever was asked ([ChatConnection.isInitiator] false) sees the profile and accepts
     * or declines, the asker waits. Saved contacts are accepted straight away, and a stranger
     * outside [matchFilter] never gets shown at all.
     */
    suspend fun start(peer: RemotePeer, ourPublicKey: ByteArray, chat: ChatConnection) {
        val contact = contactStore.isSaved(peer.peerId)
        if (!contact && !matchFilter().accepts(peer.profile)) {
            if (!chat.isInitiator) chat.send(ChatEnvelope.ChatDecline)
            endChat(EndReason.FILTERED)
            return
        }
        val acceptance = when {
            chat.isInitiator -> Acceptance.WAITING_FOR_THEM
            contact -> null
            else -> Acceptance.THEY_ASK
        }
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
            remotePhotoHidden = peer.profile.photoHidden,
            remoteStaticKey = peer.staticKey,
            acceptance = acceptance,
        )
        if (acceptance == null) chat.send(ChatEnvelope.ChatAccept)
        expiryJob = scope.launch {
            while (true) {
                delay(EXPIRY_CHECK_MILLIS)
                removeExpired()
            }
        }
        if (acceptance != null) {
            acceptTimeout = scope.launch {
                delay(ACCEPT_TIMEOUT_MILLIS)
                val current = state.value as? ChatUiState.Chatting ?: return@launch
                if (current.acceptance == null) return@launch
                if (current.acceptance == Acceptance.THEY_ASK) runCatching { chat.send(ChatEnvelope.ChatDecline) }
                endChat(EndReason.NO_ANSWER)
            }
        }
        // Contacts saved before keys were kept get theirs now (see ContactRendezvous).
        contactStore.rememberKey(peer.peerId, peer.staticKey)
        // Unless kept back, our photo already went out with the profile.
        ourPhotoSent = !photoOnRequest() && ownPhoto() != null
    }

    /** The user's answer to a chat request ([Acceptance.THEY_ASK]). */
    suspend fun answerChatRequest(chat: ChatConnection, accept: Boolean) {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.acceptance != Acceptance.THEY_ASK) return
        acceptTimeout?.cancel()
        acceptTimeout = null
        if (accept) {
            state.value = current.copy(acceptance = null)
            chat.send(ChatEnvelope.ChatAccept)
        } else {
            runCatching { chat.send(ChatEnvelope.ChatDecline) }
            endChat(EndReason.WE_DECLINED)
        }
    }

    /** They said yes to our request (explicitly, or an older version by simply chatting). */
    private fun accepted() {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.acceptance != Acceptance.WAITING_FOR_THEM) return
        acceptTimeout?.cancel()
        acceptTimeout = null
        state.value = current.copy(acceptance = null)
    }

    /** Asks the peer to swap photos (see [ChatEnvelope.PhotoRequest]). */
    suspend fun requestPhotoSwap(chat: ChatConnection) {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.photoSwap != null || current.acceptance != null) return
        state.value = current.copy(photoSwap = PhotoSwap.WE_ASKED)
        chat.send(ChatEnvelope.PhotoRequest)
    }

    /** The user's answer to the peer's [ChatEnvelope.PhotoRequest]. */
    suspend fun answerPhotoSwap(chat: ChatConnection, accept: Boolean) {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.photoSwap != PhotoSwap.THEY_ASKED) return
        state.value = current.copy(photoSwap = null)
        sendOurPhoto(chat, accept)
    }

    private suspend fun sendOurPhoto(chat: ChatConnection, accept: Boolean) {
        val photo = if (accept) ownPhoto() else null
        if (photo != null) ourPhotoSent = true
        chat.send(ChatEnvelope.PhotoReply(photo?.let(ProfileEnvelope::encodePhoto)))
    }

    private suspend fun onPhotoReply(chat: ChatConnection, base64: String?) {
        val current = state.value as? ChatUiState.Chatting ?: return
        val photo = base64?.let(ProfileEnvelope::decodePhoto)
        val weAsked = current.photoSwap == PhotoSwap.WE_ASKED
        state.value = if (photo != null) {
            current.copy(remotePhoto = photo, remotePhotoHidden = false, photoSwap = null)
        } else {
            current.copy(photoSwap = null)
        }
        // They said yes to our request: now ours goes to them, once.
        if (weAsked && photo != null && !ourPhotoSent) sendOurPhoto(chat, accept = true)
    }

    fun end() {
        acceptTimeout?.cancel()
        acceptTimeout = null
        expiryJob?.cancel()
        expiryJob = null
        ourPhotoSent = false
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
        if (offer.sizeBytes > MAX_TRANSFER_FILE_BYTES || busy || !canReceiveFile() || current.acceptance != null) {
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

    /** Sets how long messages last from now on, for both sides (see [ChatEnvelope.DisappearTimer]). */
    suspend fun setDisappearTimer(chat: ChatConnection, seconds: Long) {
        val current = state.value as? ChatUiState.Chatting ?: return
        if (current.acceptance != null) return
        val clamped = seconds.coerceIn(0, ChatEnvelopeJson.MAX_DISAPPEAR_SECONDS)
        state.value = current.copy(disappearSeconds = clamped)
        chat.send(ChatEnvelope.DisappearTimer(clamped))
    }

    private fun removeExpired() {
        val current = state.value as? ChatUiState.Chatting ?: return
        val now = System.currentTimeMillis()
        if (current.messages.none { it.expiresAtMillis != null && it.expiresAtMillis <= now }) return
        val (gone, kept) = current.messages.partition { it.expiresAtMillis != null && it.expiresAtMillis <= now }
        state.value = current.copy(messages = kept)
        if (contactStore.isSaved(current.remotePeerId)) {
            historyStore.messagesFor(current.remotePeerId) // prunes the stored copy and its files
        } else {
            gone.forEach { message -> message.localPath?.let { runCatching { java.io.File(it).delete() } } }
        }
    }

    /** Adds [message] to the open chat's transcript, persisting it for saved contacts - with an
     *  expiry if the chat has disappearing messages on. */
    fun append(message: ChatMessage) {
        val current = state.value as? ChatUiState.Chatting ?: return
        val stamped = if (current.disappearSeconds > 0 && message.expiresAtMillis == null) {
            message.copy(expiresAtMillis = message.atMillis + current.disappearSeconds * 1000)
        } else {
            message
        }
        state.value = current.copy(messages = current.messages + stamped)
        if (contactStore.isSaved(current.remotePeerId)) historyStore.append(current.remotePeerId, stamped)
    }

    suspend fun sendText(chat: ChatConnection, text: String) {
        if ((state.value as? ChatUiState.Chatting)?.acceptance != null) return
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
            is ChatEnvelope.Text, is ChatEnvelope.Ack, ChatEnvelope.Typing, is ChatEnvelope.FileRequest,
            ChatEnvelope.PhotoRequest, is ChatEnvelope.PhotoReply, ChatEnvelope.ChatAccept -> accepted()
            else -> Unit
        }
        when (envelope) {
            ChatEnvelope.ChatAccept -> Unit
            is ChatEnvelope.DisappearTimer -> {
                val current = state.value as? ChatUiState.Chatting
                if (current != null && current.acceptance == null) state.value = current.copy(disappearSeconds = envelope.seconds)
            }
            ChatEnvelope.ChatDecline -> {
                if ((state.value as? ChatUiState.Chatting)?.acceptance == Acceptance.WAITING_FOR_THEM) endChat(EndReason.THEY_DECLINED)
            }
            is ChatEnvelope.Text -> {
                setPeerTyping(false)
                append(ChatMessage(fromMe = false, text = envelope.text, atMillis = System.currentTimeMillis()))
                envelope.id?.let { chat.send(ChatEnvelope.Ack(it)) }
            }
            is ChatEnvelope.Ack -> markDelivered(envelope.id)
            is ChatEnvelope.FileRequest -> onFileRequest(chat, envelope.offer)
            is ChatEnvelope.FileReply -> awaitingReply[envelope.transferId]?.complete(envelope.accepted)
            ChatEnvelope.PhotoRequest -> {
                val current = state.value as? ChatUiState.Chatting
                when {
                    current == null -> Unit
                    // Not chatting yet: nothing of ours goes out before a yes.
                    current.acceptance != null -> chat.send(ChatEnvelope.PhotoReply(null))
                    // We show ours to everyone anyway - just send it again.
                    ownPhoto() == null || ourPhotoSent || current.photoSwap == PhotoSwap.WE_ASKED -> sendOurPhoto(chat, accept = true)
                    else -> state.value = current.copy(photoSwap = PhotoSwap.THEY_ASKED)
                }
            }
            is ChatEnvelope.PhotoReply -> onPhotoReply(chat, envelope.photo)
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
        /** How long a chat request waits for a yes before it ends. */
        const val ACCEPT_TIMEOUT_MILLIS = 60_000L
        private const val EXPIRY_CHECK_MILLIS = 1_000L
        private const val TYPING_RESEND_MILLIS = 3_000L
        private const val TYPING_SHOWN_MILLIS = 5_000L

        /** How long a file request waits for the other person to decide. */
        const val FILE_REPLY_TIMEOUT_MILLIS = 120_000L

        /** After a yes, how long the sender may take to actually start the transfer. */
        private const val FILE_START_GRACE_MILLIS = 30_000L
    }
}
