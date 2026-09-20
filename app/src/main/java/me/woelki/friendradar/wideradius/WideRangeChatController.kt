package me.woelki.friendradar.wideradius

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.woelki.friendradar.ble.FrameWriter
import me.woelki.friendradar.chat.ChatController
import me.woelki.friendradar.chat.ChatMessage
import me.woelki.friendradar.chat.ChatUiState
import me.woelki.friendradar.chat.MAX_TRANSFER_FILE_BYTES
import me.woelki.friendradar.chat.MessageKind
import me.woelki.friendradar.contacts.ChatHistoryStore
import me.woelki.friendradar.contacts.ContactStore
import me.woelki.friendradar.crypto.ChatSession
import me.woelki.friendradar.crypto.Identity
import me.woelki.friendradar.crypto.TransferCipher
import me.woelki.friendradar.crypto.readChunked
import me.woelki.friendradar.crypto.writeChunked
import me.woelki.friendradar.data.MediaFileStore
import me.woelki.friendradar.pairing.NearbyPeer
import me.woelki.friendradar.pairing.RandomMatcher
import me.woelki.friendradar.pairing.SignalStrength
import me.woelki.friendradar.profile.Profile
import me.woelki.friendradar.safety.BlockList
import me.woelki.friendradar.safety.Cooldown
import me.woelki.friendradar.safety.DeviceFingerprint
import me.woelki.friendradar.safety.ReportFlow
import org.json.JSONObject

/**
 * The M4 wide-range counterpart to [me.woelki.friendradar.ble.BleChatController]: the same
 * Noise handshake / envelope-multiplexing / safety-layer wiring, but discovery is DHT
 * rendezvous (see [WideRangeNode]) instead of BLE GATT scanning, and there's no physical MTU,
 * so a whole framed message is always written/read in one go instead of fragmented (still using
 * [FrameWriter]'s length-prefix format, just never split into more than one piece).
 *
 * Unlike BLE, which fully stops its radio while a chat is active, DHT advertise/find-peers keep
 * running in the background here even while chatting — stopping/restarting participation in the
 * DHT is a lot more disruptive than pausing a BLE radio, and an incoming chat stream while
 * already busy is simply rejected (see [onIncomingStream]), matching BLE's own
 * "already busy with another chat" guard.
 *
 * Requires [Profile.coarseGeohash] to be set before [setBrowsing] is turned on.
 */
class WideRangeChatController(
    private val context: Context,
    private val identity: Identity,
    private val profile: Profile,
    private val node: WideRangeNode,
) : ChatController {

    private val blockList = BlockList(context)
    private val contactStore = ContactStore(context)
    private val historyStore = ChatHistoryStore(context)
    private val mediaFileStore = MediaFileStore(context)
    private val cooldown = Cooldown()
    private val reportFlow = ReportFlow(context, blockList)
    private val matcher = RandomMatcher()
    private val deviceFingerprint = DeviceFingerprint.compute(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    override val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _transferStatus = MutableStateFlow<String?>(null)
    override val transferStatus: StateFlow<String?> = _transferStatus.asStateFlow()

    override val fileTransferAvailable: Boolean get() = WideRangeNode.isSupported

    /** After the Noise handshake finishes, both sides immediately exchange two more encrypted
     *  messages — their device fingerprint, then their pseudonym — before the chat is considered
     *  open; identical sequencing to [me.woelki.friendradar.ble.BleChatController]. */
    private enum class HandshakeStep { EXPECT_MESSAGE_1, EXPECT_MESSAGE_2, EXPECT_MESSAGE_3, EXPECT_DEVICE_ID, EXPECT_PROFILE, READY }

    private class Connection(val session: ChatSession, val isOutbound: Boolean, val stream: WideRangeByteStream) {
        var step: HandshakeStep = if (session.isReady) HandshakeStep.READY
            else if (isOutbound) HandshakeStep.EXPECT_MESSAGE_2 else HandshakeStep.EXPECT_MESSAGE_1
        var pendingIncomingOffer: FileOffer? = null
        var remoteDeviceFingerprint: String? = null

        /** Guards writes to [stream]: the read loop (handshake responses, profile exchange)
         *  and [sendMessage]/[sendFile] (launched from the UI thread) can both want to write
         *  to the same chat stream at once - unlike BLE's fire-and-forget GATT writes, this
         *  is a real suspend I/O call, so concurrent writers must be serialized here. */
        val writeMutex = Mutex()
    }

    private data class FileOffer(val fileName: String, val mimeType: String, val sizeBytes: Long)

    private val discoveredByPeerId = java.util.Collections.synchronizedMap(mutableMapOf<String, NearbyPeer>())
    private var activeConnection: Connection? = null
    private var browsing = false
    private var discoveryJob: Job? = null
    private var incomingStreamJob: Job? = null
    private var findPeersJob: Job? = null

    private fun rendezvousTopic(): String {
        val geohash = profile.coarseGeohash ?: error("No coarse location set")
        val precision = Geohash.precisionForRadiusKm(profile.searchRadiusKm).coerceAtMost(geohash.length)
        return "frad/wideradius/v1/" + geohash.take(precision)
    }

    override fun setBrowsing(enabled: Boolean) {
        if (enabled == browsing) return
        if (enabled && !WideRangeNode.isSupported) {
            _state.value = ChatUiState.Ended("Wide-range networking isn't built into this app")
            return
        }
        if (enabled && profile.coarseGeohash == null) {
            _state.value = ChatUiState.Ended("Set your area in Profile before going wide-range")
            return
        }

        browsing = enabled
        if (enabled) {
            discoveredByPeerId.clear()
            _state.value = ChatUiState.Browsing(emptyList())
            val topic = rendezvousTopic()
            scope.launch {
                val started = node.start(
                    WideRangeConfig(
                        identitySeed = Random.nextBytes(32),
                        bootstrapPeers = profile.bootstrapNodes,
                        rendezvousTopic = topic,
                    ),
                )
                if (started.isFailure) {
                    browsing = false
                    _state.value = ChatUiState.Ended("Couldn't start wide-range networking: ${started.exceptionOrNull()?.message}")
                    return@launch
                }
                node.startAdvertising(topic)
                startDiscoveryCollectors(topic)
            }
        } else {
            discoveryJob?.cancel(); discoveryJob = null
            incomingStreamJob?.cancel(); incomingStreamJob = null
            findPeersJob?.cancel(); findPeersJob = null
            scope.launch { node.stop() }
            endActiveConnection("stopped browsing")
            _state.value = ChatUiState.Idle
        }
    }

    private fun startDiscoveryCollectors(topic: String) {
        discoveryJob = scope.launch {
            node.discoveredPeers().collect { peerId -> onPeerDiscovered(peerId) }
        }
        incomingStreamJob = scope.launch {
            node.incomingStreams().collect { (protocolId, stream) -> onIncomingStream(protocolId, stream) }
        }
        findPeersJob = scope.launch {
            // A single DHT lookup is one-shot, unlike BLE's continuous scan - re-run it on an
            // interval for as long as browsing stays on.
            while (isActive) {
                node.findPeersOnce(topic)
                delay(FIND_PEERS_INTERVAL_MILLIS)
            }
        }
    }

    private fun onPeerDiscovered(peerId: String) {
        discoveredByPeerId[peerId] = NearbyPeer(
            sessionId = peerId,
            lastSeenAtMillis = System.currentTimeMillis(),
            signalStrength = SignalStrength.Unknown,
        )
        if (_state.value is ChatUiState.Browsing) {
            _state.value = ChatUiState.Browsing(discoveredByPeerId.values.toList())
        }
    }

    override fun requestRandomChat() {
        if (_state.value !is ChatUiState.Browsing) return
        val picked = matcher.pickRandomPeer(discoveredByPeerId.values.toList()) ?: return
        if (!cooldown.canRequest(picked.sessionId)) return
        cooldown.recordRequest(picked.sessionId)

        _state.value = ChatUiState.Connecting(picked)
        scope.launch {
            val stream = node.openStream(picked.sessionId, CHAT_PROTOCOL_ID).getOrElse {
                endActiveConnection("couldn't reach that peer")
                return@launch
            }
            val connection = Connection(ChatSession(isInitiator = true, identity = identity), isOutbound = true, stream = stream)
            activeConnection = connection
            _state.value = ChatUiState.Handshaking
            startReadLoop(connection)
            sendRaw(connection, connection.session.startHandshake())
        }
    }

    override fun sendMessage(text: String) {
        val connection = activeConnection ?: return
        if (connection.step != HandshakeStep.READY) return
        val json = JSONObject().put("k", "txt").put("t", text).toString()
        scope.launch {
            runCatching { sendEncrypted(connection, json) }
                .onFailure { if (activeConnection === connection) endActiveConnection("peer disconnected") }
        }
        appendMessage(ChatMessage(fromMe = true, text = text, atMillis = System.currentTimeMillis()))
    }

    /** Opens a *second* libp2p stream to the peer for the file bytes themselves - the chat
     *  stream only ever carries the small offer envelope, same division of labor
     *  [me.woelki.friendradar.ble.BleChatController] gives BLE (offer) vs. Wi-Fi Direct
     *  (bytes). Direct vs. relayed dialing for that second stream is entirely
     *  [WideRangeNode]/libp2p's own business - never decided here. */
    override fun sendFile(bytes: ByteArray, fileName: String, mimeType: String) {
        val connection = activeConnection ?: return
        if (connection.step != HandshakeStep.READY) return
        if (_transferStatus.value != null) return
        if (bytes.size > MAX_TRANSFER_FILE_BYTES) return

        val remotePeerId = connection.session.remotePeerId()
        val remoteLibp2pPeerId = connection.stream.remotePeerId
        val transferKey = connection.session.deriveTransferKey(WIDE_TRANSFER_KEY_INFO)
        _transferStatus.value = "Sending $fileName…"
        scope.launch {
            val result = runCatching {
                val transferStream = node.openStream(remoteLibp2pPeerId, TRANSFER_PROTOCOL_ID).getOrThrow()
                try {
                    sendEncrypted(connection, encodeFileOffer(fileName, mimeType, bytes.size.toLong()))
                    writeChunked(bytes, TransferCipher(transferKey)) { transferStream.write(it).getOrThrow() }
                } finally {
                    transferStream.close()
                }
            }
            _transferStatus.value = null
            result.onSuccess {
                val path = mediaFileStore.write(remotePeerId, newMessageId(), bytes).absolutePath
                appendMessage(fileMessage(fromMe = true, fileName = fileName, mimeType = mimeType, sizeBytes = bytes.size.toLong(), localPath = path))
            }
        }
    }

    private fun receiveFile(connection: Connection, stream: WideRangeByteStream, offer: FileOffer) {
        if (_transferStatus.value != null || offer.sizeBytes > MAX_TRANSFER_FILE_BYTES) {
            stream.close()
            return
        }
        val remotePeerId = connection.session.remotePeerId()
        val transferKey = connection.session.deriveTransferKey(WIDE_TRANSFER_KEY_INFO)
        _transferStatus.value = "Receiving ${offer.fileName}…"
        scope.launch {
            val result = runCatching {
                readChunked(offer.sizeBytes, TransferCipher(transferKey)) { stream.readExactly(it).getOrThrow() }
            }
            stream.close()
            _transferStatus.value = null
            result.onSuccess { bytes ->
                val path = mediaFileStore.write(remotePeerId, newMessageId(), bytes).absolutePath
                appendMessage(fileMessage(fromMe = false, fileName = offer.fileName, mimeType = offer.mimeType, sizeBytes = offer.sizeBytes, localPath = path))
            }
        }
    }

    private fun fileMessage(fromMe: Boolean, fileName: String, mimeType: String, sizeBytes: Long, localPath: String) = ChatMessage(
        fromMe = fromMe,
        text = "",
        atMillis = System.currentTimeMillis(),
        kind = MessageKind.FILE,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        localPath = localPath,
    )

    private fun encodeFileOffer(fileName: String, mimeType: String, sizeBytes: Long): String =
        JSONObject().put("k", "wide-transfer").put("name", fileName).put("mime", mimeType).put("size", sizeBytes).toString()

    private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    /** Chat history is only ever written to disk for peers the user chose to save as a
     *  contact - see [ChatHistoryStore], same rule [me.woelki.friendradar.ble.BleChatController] follows. */
    private fun persistIfSaved(remotePeerId: String, message: ChatMessage) {
        if (contactStore.isSaved(remotePeerId)) historyStore.append(remotePeerId, message)
    }

    private fun appendMessage(message: ChatMessage) {
        val current = _state.value
        if (current is ChatUiState.Chatting) {
            _state.value = current.copy(messages = current.messages + message)
            persistIfSaved(current.remotePeerId, message)
        }
    }

    override fun endActiveConnection(reason: String) {
        activeConnection?.stream?.close()
        activeConnection = null
        _transferStatus.value = null
        _state.value = ChatUiState.Ended(reason)
        if (browsing) {
            _state.value = ChatUiState.Browsing(discoveredByPeerId.values.toList())
        }
    }

    override fun blockActivePeer() {
        val current = _state.value
        if (current is ChatUiState.Chatting) blockList.block(current.remotePeerId, current.remoteDeviceFingerprint)
        endActiveConnection("blocked")
    }

    override fun reportActivePeer(reason: String) {
        val current = _state.value
        if (current is ChatUiState.Chatting) reportFlow.report(current.remotePeerId, current.remoteDeviceFingerprint, reason)
        endActiveConnection("reported")
    }

    // ---- incoming libp2p streams ----

    private fun onIncomingStream(protocolId: String, stream: WideRangeByteStream) {
        when (protocolId) {
            CHAT_PROTOCOL_ID -> {
                if (activeConnection != null) {
                    stream.close() // already busy with another chat
                    return
                }
                val connection = Connection(ChatSession(isInitiator = false, identity = identity), isOutbound = false, stream = stream)
                activeConnection = connection
                _state.value = ChatUiState.Handshaking
                startReadLoop(connection)
            }
            TRANSFER_PROTOCOL_ID -> {
                val connection = activeConnection
                val offer = connection?.pendingIncomingOffer
                if (connection == null || offer == null) {
                    stream.close()
                    return
                }
                connection.pendingIncomingOffer = null
                receiveFile(connection, stream, offer)
            }
        }
    }

    // ---- read loop / frame routing (the wide-range analogue of BLE's GATT callbacks) ----

    private fun startReadLoop(connection: Connection) {
        scope.launch {
            while (isActive) {
                val frame = readFrame(connection.stream).getOrElse {
                    if (activeConnection === connection) endActiveConnection("peer disconnected")
                    return@launch
                }
                handleFrame(connection, frame)
            }
        }
    }

    private suspend fun handleFrame(connection: Connection, frame: ByteArray) {
        when (connection.step) {
            HandshakeStep.EXPECT_MESSAGE_1 -> {
                val message2 = connection.session.respondToHandshake(frame)
                connection.step = HandshakeStep.EXPECT_MESSAGE_3
                sendRaw(connection, message2)
            }
            HandshakeStep.EXPECT_MESSAGE_2 -> {
                val message3 = connection.session.completeHandshake(frame)
                sendRaw(connection, message3)
                advanceToDeviceIdExchange(connection)
            }
            HandshakeStep.EXPECT_MESSAGE_3 -> {
                connection.session.finishHandshake(frame)
                advanceToDeviceIdExchange(connection)
            }
            HandshakeStep.EXPECT_DEVICE_ID -> {
                val remoteFingerprint = connection.session.decryptMessage(frame)
                if (blockList.isBlocked(remoteFingerprint)) {
                    connection.stream.close()
                    if (activeConnection === connection) endActiveConnection("blocked peer")
                    return
                }
                connection.remoteDeviceFingerprint = remoteFingerprint
                connection.step = HandshakeStep.EXPECT_PROFILE
                sendEncrypted(connection, profile.pseudonym)
            }
            HandshakeStep.EXPECT_PROFILE -> {
                val remotePseudonym = connection.session.decryptMessage(frame)
                connection.step = HandshakeStep.READY
                val remotePeerId = connection.session.remotePeerId()
                _state.value = ChatUiState.Chatting(
                    remotePeerId = remotePeerId,
                    remoteDeviceFingerprint = connection.remoteDeviceFingerprint!!,
                    remotePseudonym = remotePseudonym,
                    messages = if (contactStore.isSaved(remotePeerId)) historyStore.messagesFor(remotePeerId) else emptyList(),
                )
            }
            HandshakeStep.READY -> {
                val envelope = JSONObject(connection.session.decryptMessage(frame))
                when (envelope.getString("k")) {
                    "wide-transfer" -> connection.pendingIncomingOffer = FileOffer(
                        fileName = envelope.getString("name"),
                        mimeType = envelope.getString("mime"),
                        sizeBytes = envelope.getLong("size"),
                    )
                    else -> appendMessage(ChatMessage(fromMe = false, text = envelope.getString("t"), atMillis = System.currentTimeMillis()))
                }
            }
        }
    }

    /** The Noise handshake is done and transport keys are ready. Before showing any chat UI,
     *  check the peer's now-revealed long-term identity against the block list - discovery only
     *  ever exposes the rotating libp2p peer id, so this is the first point blocking can be
     *  enforced - then trade device fingerprints for a second, identity-independent block check
     *  (M5), and only once both pass, trade pseudonyms, so a blocked peer never learns ours.
     *  Identical reasoning to [me.woelki.friendradar.ble.BleChatController.advanceToDeviceIdExchange]. */
    private suspend fun advanceToDeviceIdExchange(connection: Connection) {
        val remotePeerId = connection.session.remotePeerId()
        if (blockList.isBlocked(remotePeerId)) {
            connection.stream.close()
            if (activeConnection === connection) endActiveConnection("blocked peer")
            return
        }
        connection.step = HandshakeStep.EXPECT_DEVICE_ID
        sendEncrypted(connection, deviceFingerprint)
    }

    // ---- framing primitives (length-prefixed, same wire format as ble/Framing.kt, but a whole
    // message is always read/written in one go since there's no BLE-style MTU here) ----

    private suspend fun writeFrame(connection: Connection, message: ByteArray) {
        val framed = FrameWriter.split(message, maxFragmentSize = message.size + 4).single()
        connection.stream.write(framed).getOrThrow()
    }

    private suspend fun readFrame(stream: WideRangeByteStream): Result<ByteArray> = runCatching {
        val length = ByteBuffer.wrap(stream.readExactly(4).getOrThrow()).order(ByteOrder.BIG_ENDIAN).int
        stream.readExactly(length).getOrThrow()
    }

    private suspend fun sendRaw(connection: Connection, bytes: ByteArray) {
        connection.writeMutex.withLock { writeFrame(connection, bytes) }
    }

    private suspend fun sendEncrypted(connection: Connection, plaintext: String) {
        connection.writeMutex.withLock { writeFrame(connection, connection.session.encryptMessage(plaintext)) }
    }

    companion object {
        const val CHAT_PROTOCOL_ID = "/frad/chat/1.0.0"
        const val TRANSFER_PROTOCOL_ID = "/frad/transfer/1.0.0"
        private const val WIDE_TRANSFER_KEY_INFO = "frad-wide-transfer-v1"
        private const val FIND_PEERS_INTERVAL_MILLIS = 30_000L
    }
}
