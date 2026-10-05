package app.frad.chat.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import app.frad.chat.crypto.ChatSession
import app.frad.chat.crypto.Identity
import app.frad.chat.profile.ProfileEnvelope
import app.frad.chat.profile.RemoteProfile

/** How a [ChatConnection] reaches its peer: one BLE GATT link or one libp2p stream. Frames go out
 *  whole and in order - splitting them for the wire is the transport's business. */
interface FrameTransport {
    suspend fun send(frame: ByteArray)
    fun close()
}

/** Who you're talking to, known once a [ChatConnection] has finished setting up. */
data class RemotePeer(
    val peerId: String,
    val deviceFingerprint: String,
    val profile: RemoteProfile,
    /** Their long-term public key, e.g. for [SafetyNumber]. */
    val staticKey: ByteArray,
)

/** What a frame fed to [ChatConnection.onFrame] amounted to, if anything the caller must act on. */
sealed interface ChatEvent {
    /** Setup finished: the chat is open. */
    data class Ready(val peer: RemotePeer) : ChatEvent

    /** An encrypted message arrived on an open chat. */
    data class Received(val envelope: ChatEnvelope) : ChatEvent

    /** The peer turned out to be on the block list (by identity or by device) before we revealed
     *  anything about ourselves; the caller should drop the connection. */
    data object Blocked : ChatEvent

    /** One side is an adult and the other isn't - see [ChatConnection]'s step 3. Neither side has
     *  seen the other's profile; the caller should drop the connection. */
    data object AgeGroupMismatch : ChatEvent
}

/**
 * The transport-independent part of one chat, shared by
 * [app.frad.chat.ble.BleChatController] and [app.frad.chat.wideradius.WideRangeChatController],
 * which only add discovery, the link itself and file transfer around it:
 *
 *  1. the Noise_XX handshake ([ChatSession]),
 *  2. a block-list check on the peer's now-revealed long-term identity - discovery only ever
 *     exposes rotating ids, so this is the first point blocking can be enforced,
 *  3. an exchange of device fingerprints for a second, identity-independent block check (M5),
 *     together with each side's age group: adults are only matched with adults and minors with
 *     minors, decided here - before any profile, photo or message is exchanged,
 *  4. only once all that passes, an exchange of profiles, so a blocked or mismatched peer never
 *     learns ours,
 *  5. after that, encrypted [ChatEnvelope]s.
 *
 * The Noise initiator is always the side that asked for the chat. Not thread-safe for
 * [onFrame]: callers feed frames from one serial dispatcher; [send] may be called concurrently
 * with it, since it never touches the setup state.
 */
class ChatConnection(
    val isInitiator: Boolean,
    identity: Identity,
    private val transport: FrameTransport,
    /** The device fingerprint to present to a peer, given their static key - see
     *  [app.frad.chat.safety.DeviceFingerprint.forPeer]. */
    private val deviceFingerprintFor: (remoteStaticKey: ByteArray) -> String,
    private val localIsAdult: Boolean,
    private val localProfile: () -> String,
    private val isBlocked: (String) -> Boolean,
) {
    private enum class Step { EXPECT_MESSAGE_1, EXPECT_MESSAGE_2, EXPECT_MESSAGE_3, EXPECT_DEVICE_ID, EXPECT_PROFILE, READY, BLOCKED }

    private val session = ChatSession(isInitiator, identity)
    private var step = if (isInitiator) Step.EXPECT_MESSAGE_2 else Step.EXPECT_MESSAGE_1
    private var remoteDeviceFingerprint: String? = null

    /** Encrypting takes the next nonce, so encrypt-and-send must be one step: otherwise two
     *  concurrent senders could put ciphertexts on the wire in the opposite order of their nonces,
     *  and the peer's strictly sequential decryption would fail. */
    private val sendMutex = Mutex()

    /** Every transfer id used in this chat, ours and the peer's - see [claimTransferId]. */
    private val seenTransferIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Non-null once setup has finished. */
    @Volatile var remotePeer: RemotePeer? = null
        private set

    val isReady: Boolean get() = remotePeer != null

    /** Initiator only: sends the first handshake message. */
    suspend fun start() {
        check(isInitiator) { "Only the initiator starts the handshake" }
        sendRaw(session.startHandshake())
    }

    /**
     * Advances setup with, or decrypts, one frame from the peer.
     *
     * @throws Exception (truncated handshake message, failed authentication, malformed JSON, ...)
     *   if the peer broke the protocol - the caller should drop the connection.
     */
    suspend fun onFrame(frame: ByteArray): ChatEvent? = when (step) {
        Step.EXPECT_MESSAGE_1 -> {
            val message2 = session.respondToHandshake(frame)
            step = Step.EXPECT_MESSAGE_3
            sendRaw(message2)
            null
        }
        Step.EXPECT_MESSAGE_2 -> {
            session.readResponse(frame)
            // Check the responder before message 3, which would reveal our own identity to them.
            if (isBlocked(session.remotePeerId())) {
                step = Step.BLOCKED
                ChatEvent.Blocked
            } else {
                sendRaw(session.finishAsInitiator())
                afterHandshake()
            }
        }
        Step.EXPECT_MESSAGE_3 -> {
            session.finishHandshake(frame)
            afterHandshake()
        }
        Step.EXPECT_DEVICE_ID -> {
            val announcement = JSONObject(session.decryptMessage(frame))
            val fingerprint = announcement.getString(KEY_FINGERPRINT)
            val remoteIsAdult = announcement.getBoolean(KEY_ADULT)
            if (isBlocked(fingerprint)) {
                step = Step.BLOCKED
                ChatEvent.Blocked
            } else if (remoteIsAdult != localIsAdult) {
                step = Step.BLOCKED
                ChatEvent.AgeGroupMismatch
            } else {
                remoteDeviceFingerprint = fingerprint
                step = Step.EXPECT_PROFILE
                sendEncrypted(localProfile())
                null
            }
        }
        Step.EXPECT_PROFILE -> {
            val profile = ProfileEnvelope.decode(session.decryptMessage(frame))
            val peer = RemotePeer(session.remotePeerId(), remoteDeviceFingerprint!!, profile, session.remoteStaticKey())
            step = Step.READY
            remotePeer = peer
            ChatEvent.Ready(peer)
        }
        Step.READY -> ChatEvent.Received(ChatEnvelopeJson.decode(session.decryptMessage(frame)))
        Step.BLOCKED -> null
    }

    private suspend fun afterHandshake(): ChatEvent? {
        if (isBlocked(session.remotePeerId())) {
            step = Step.BLOCKED
            return ChatEvent.Blocked
        }
        step = Step.EXPECT_DEVICE_ID
        val announcement = JSONObject()
            .put(KEY_FINGERPRINT, deviceFingerprintFor(session.remoteStaticKey()))
            .put(KEY_ADULT, localIsAdult)
        sendEncrypted(announcement.toString())
        return null
    }

    /** Sends [envelope] on an open chat. */
    suspend fun send(envelope: ChatEnvelope) {
        check(isReady) { "Chat not open yet" }
        sendEncrypted(ChatEnvelopeJson.encode(envelope))
    }

    /** See [ChatSession.deriveTransferKey]. */
    fun transferKey(transport: String, transferId: String, outgoing: Boolean): ByteArray =
        session.deriveTransferKey(transport, transferId, outgoing)

    /** Records [transferId] as used in this chat; false if it already was, in which case its key
     *  (see [ChatSession.deriveTransferKey]) would repeat and the transfer must be refused. */
    fun claimTransferId(transferId: String): Boolean = seenTransferIds.add(transferId)

    fun close() = transport.close()

    private suspend fun sendRaw(frame: ByteArray) {
        sendMutex.withLock { transport.send(frame) }
    }

    private suspend fun sendEncrypted(plaintext: String) {
        sendMutex.withLock { transport.send(session.encryptMessage(plaintext)) }
    }

    private companion object {
        const val KEY_FINGERPRINT = "fp"
        const val KEY_ADULT = "adult"
    }
}
