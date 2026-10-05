package app.frad.chat.wideradius

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.frad.chat.ble.FrameTooLargeException
import app.frad.chat.ble.FrameWriter
import app.frad.chat.ble.MAX_FRAME_BYTES
import app.frad.chat.chat.ChatConnection
import app.frad.chat.chat.ChatController
import app.frad.chat.chat.ChatEnvelope
import app.frad.chat.chat.closingOnCancel
import app.frad.chat.chat.ChatEvent
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.ChatUiState
import app.frad.chat.chat.FileOffer
import app.frad.chat.chat.FrameTransport
import app.frad.chat.chat.OpenChat
import app.frad.chat.chat.MAX_MESSAGE_CHARS
import app.frad.chat.chat.MAX_TRANSFER_FILE_BYTES
import app.frad.chat.chat.MessageKind
import app.frad.chat.contacts.ChatHistoryStore
import app.frad.chat.contacts.ContactStore
import app.frad.chat.crypto.Identity
import app.frad.chat.crypto.ProofOfWork
import app.frad.chat.crypto.TransferCipher
import app.frad.chat.media.FileTypeCheck
import app.frad.chat.crypto.readChunked
import app.frad.chat.crypto.writeChunked
import app.frad.chat.data.MediaFileStore
import app.frad.chat.pairing.NearbyPeer
import app.frad.chat.pairing.RandomMatcher
import app.frad.chat.pairing.SignalStrength
import app.frad.chat.profile.Profile
import app.frad.chat.profile.ProfileEnvelope
import app.frad.chat.safety.BlockList
import app.frad.chat.safety.Cooldown
import app.frad.chat.safety.DeviceFingerprint
import app.frad.chat.safety.ReportFlow

/**
 * The M4 wide-range counterpart to [app.frad.chat.ble.BleChatController]: the same
 * [ChatConnection] protocol, but discovery is DHT rendezvous (see [WideRangeNode]) instead of BLE
 * GATT scanning, and there's no physical MTU, so a whole framed message is always written/read in
 * one go instead of fragmented (still using [FrameWriter]'s length-prefix format, just never split
 * into more than one piece).
 *
 * Unlike BLE, which fully stops its radio while a chat is active, DHT advertise/find-peers keep
 * running in the background here even while chatting — stopping/restarting participation in the
 * DHT is a lot more disruptive than pausing a BLE radio, and an incoming chat stream while
 * already busy is simply rejected (see [onIncomingStream]), matching BLE's own
 * "already busy with another chat" guard.
 *
 * Requires [Profile.coarseGeohash] to be set before [setBrowsing] is turned on.
 */
@OptIn(ExperimentalCoroutinesApi::class) // limitedParallelism
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
    private val deviceSecret = DeviceFingerprint.deviceSecret(context)

    // Everything this controller does runs on this one serial dispatcher (stream I/O itself
    // suspends onto Dispatchers.IO inside WideRangeByteStream/WideRangeNode, freeing it
    // meanwhile), so none of the state below needs locking of its own. The handler is the last
    // line of defense - a failure is logged, never allowed to take down the whole app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "unexpected error", e) },
    )

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    override val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val notices: SharedFlow<String> = _notices.asSharedFlow()

    private val openChat by lazy { OpenChat(_state, scope, contactStore, historyStore, canReceiveFile = { _transferStatus.value == null }) }

    private val _transferStatus = MutableStateFlow<String?>(null)
    override val transferStatus: StateFlow<String?> = _transferStatus.asStateFlow()
    private val _transferProgress = MutableStateFlow<Float?>(null)
    override val transferProgress: StateFlow<Float?> = _transferProgress.asStateFlow()

    override val fileTransferAvailable: Boolean get() = WideRangeNode.isSupported

    /** One chat stream and the chat running over it. */
    private class Link(val stream: WideRangeByteStream, val chat: ChatConnection) {
        /** An accepted offer whose transfer stream hasn't arrived yet... */
        var pendingIncomingOffer: FileOffer? = null

        /** ...or a transfer stream that overtook its offer (they travel separately), held briefly. */
        var earlyTransferStream: WideRangeByteStream? = null
        var earlyTransferStreamTimeout: Job? = null
    }

    /** Incoming chat requests still waiting for their [ProofOfWork] - checked outside the
     *  single chat slot, so requests that never prove their work can't occupy it. */
    private var pendingIncomingChecks = 0

    /** Recently accepted proof-of-work tokens, so one solved token can't be replayed for a
     *  burst of requests within its validity window. */
    private val seenProofOfWork = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > SEEN_PROOF_OF_WORK_CAPACITY
    }

    private val discoveredByPeerId = mutableMapOf<String, NearbyPeer>()
    private var activeLink: Link? = null
    private var browsing = false
    private var discoveryJob: Job? = null
    private var incomingStreamJob: Job? = null
    private var findPeersJob: Job? = null

    /** Starting the node suspends for a while (dialing bootstrap peers); without this, a quick
     *  off/on toggle during that time would run a second start next to the first - two libp2p
     *  hosts, duplicate collectors. Starts and stops run one at a time, in order. */
    private val nodeLifecycle = Mutex()

    /** Whether [node] is started; only read/written while holding [nodeLifecycle]. */
    private var nodeRunning = false

    /** The wide-range counterpart to BLE's connection timeout: without it, a peer that opens a
     *  chat stream and then never finishes the handshake would hold [activeLink] - and with it
     *  every other incoming chat request, see [onIncomingStream] - for as long as it likes. */
    private var handshakeTimeoutJob: Job? = null

    private fun armHandshakeTimeout(stillPending: () -> Boolean) {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MILLIS)
            if (stillPending()) {
                Log.w(TAG, "chat setup timed out, state=${_state.value}")
                endActive("connection timed out")
            }
        }
    }

    private fun disarmHandshakeTimeout() {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
    }

    private fun newLink(stream: WideRangeByteStream, isInitiator: Boolean): Link {
        val transport = object : FrameTransport {
            override suspend fun send(frame: ByteArray) {
                stream.write(lengthPrefixed(frame)).getOrThrow()
            }

            override fun close() = stream.close()
        }
        val chat = ChatConnection(
            isInitiator = isInitiator,
            identity = identity,
            transport = transport,
            deviceFingerprintFor = { DeviceFingerprint.forPeer(deviceSecret, it) },
            localIsAdult = profile.isAdult,
            localProfile = { ProfileEnvelope.encode(context, profile) },
            // Also refuses whoever we just finished chatting with, on both sides - see Cooldown.justChatted.
            isBlocked = { blockList.isBlocked(it) || cooldown.justChatted(it) },
        )
        return Link(stream, chat)
    }

    /** Our own cell's rendezvous topic first, then its (up to) 8 neighbours'. Advertising in all
     *  of them while searching only our own means two people on either side of a cell border
     *  still find each other - the neighbour's search hits our advertisement in its cell - at the
     *  cost of a few more provider records rather than nine lookups every [FIND_PEERS_INTERVAL_MILLIS]. */
    private fun rendezvousTopics(): List<String> {
        val geohash = profile.coarseGeohash ?: error("No coarse location set")
        val precision = Geohash.precisionForRadiusKm(profile.searchRadiusKm).coerceAtMost(geohash.length)
        val cell = geohash.take(precision)
        return (listOf(cell) + Geohash.neighbors(cell)).map { "frad/wideradius/v1/$it" }
    }

    override fun setBrowsing(enabled: Boolean) {
        scope.launch {
            if (enabled == browsing) return@launch
            if (enabled && !WideRangeNode.isSupported) {
                _state.value = ChatUiState.Ended("Wide-range networking isn't built into this app")
                return@launch
            }
            if (enabled && profile.coarseGeohash == null) {
                _state.value = ChatUiState.Ended("Set your area in Profile before going wide-range")
                return@launch
            }
            if (enabled && profile.wideRangeRelayOnly && profile.bootstrapNodes.isEmpty()) {
                _state.value = ChatUiState.Ended("\"Hide my IP address\" needs at least one bootstrap/relay node in Profile")
                return@launch
            }

            browsing = enabled
            if (enabled) {
                discoveredByPeerId.clear()
                _state.value = ChatUiState.Browsing(emptyList())
                val topics = rendezvousTopics()
                val ownTopic = topics.first()
                nodeLifecycle.withLock {
                    if (!browsing) return@launch // turned off again while waiting for a stop
                    // Still up from before an off/on toggle that never got to stop it: restart
                    // cleanly rather than starting a second host next to it.
                    if (nodeRunning) {
                        node.stop()
                        nodeRunning = false
                    }
                    val started = node.start(
                        WideRangeConfig(
                            identitySeed = Random.nextBytes(32),
                            bootstrapPeers = profile.bootstrapNodes,
                            rendezvousTopic = ownTopic,
                            relayOnly = profile.wideRangeRelayOnly,
                        ),
                    )
                    if (started.isFailure) {
                        browsing = false
                        _state.value = ChatUiState.Ended("Couldn't start wide-range networking: ${started.exceptionOrNull()?.message}")
                        return@launch
                    }
                    nodeRunning = true
                    if (!browsing) {
                        // Turned off again while the node was still starting.
                        node.stop()
                        nodeRunning = false
                        return@launch
                    }
                    topics.forEach { node.startAdvertising(it) }
                    startDiscoveryCollectors(ownTopic)
                }
            } else {
                stopDiscoveryCollectors()
                endActive("stopped browsing")
                _state.value = ChatUiState.Idle
                nodeLifecycle.withLock {
                    if (!browsing && nodeRunning) {
                        node.stop()
                        nodeRunning = false
                    }
                }
            }
        }
    }

    private fun stopDiscoveryCollectors() {
        discoveryJob?.cancel(); discoveryJob = null
        incomingStreamJob?.cancel(); incomingStreamJob = null
        findPeersJob?.cancel(); findPeersJob = null
    }

    private fun startDiscoveryCollectors(topic: String) {
        // Never two collectors on the same flows: the second would close every stream the first
        // one accepted (see onIncomingStream).
        stopDiscoveryCollectors()
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
                forgetStalePeers()
                // A time-limited visibility (Profile.visibleUntilMillis) ran out - never mid-chat.
                val until = profile.visibleUntilMillis
                if (until > 0 && System.currentTimeMillis() >= until && activeLink == null) {
                    profile.visibleUntilMillis = 0
                    setBrowsing(false)
                    _notices.tryEmit("Your visibility time is up - you're not visible any more.")
                    break
                }
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

    /** A lookup only ever reports who's there, never who left: drop peers no lookup has
     *  reported for a while, so the count shown and [requestRandomChat]'s pick stay current. */
    private fun forgetStalePeers() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MILLIS
        if (discoveredByPeerId.values.removeAll { it.lastSeenAtMillis < cutoff } && _state.value is ChatUiState.Browsing) {
            _state.value = ChatUiState.Browsing(discoveredByPeerId.values.toList())
        }
    }

    /** A random peer that isn't cooling down from a recent request, or null - after telling the
     *  user why nothing happens. */
    private fun pickPeer(candidates: List<NearbyPeer>): NearbyPeer? {
        if (candidates.isEmpty()) {
            _notices.tryEmit("Nobody's around yet - keep FRAD open for a moment.")
            return null
        }
        return matcher.pickRandomPeer(candidates, excluding = cooldown.coolingDown()) ?: run {
            _notices.tryEmit("You've asked everyone around in the last ${Cooldown.DEFAULT_MIN_INTERVAL_MILLIS / 1000} seconds - try again in a moment.")
            null
        }
    }

    override fun requestRandomChat() {
        scope.launch {
            if (_state.value !is ChatUiState.Browsing) return@launch
            val picked = pickPeer(discoveredByPeerId.values.toList()) ?: return@launch
            cooldown.recordRequest(picked.sessionId)

            val connecting = ChatUiState.Connecting(picked)
            _state.value = connecting
            armHandshakeTimeout { _state.value === connecting }
            val proofOfWork = withContext(Dispatchers.Default) {
                ProofOfWork.solve(responderId = picked.sessionId, initiatorId = node.localPeerId, nowSeconds = nowSeconds())
            }
            if (_state.value !== connecting) return@launch
            val stream = node.openStream(picked.sessionId, CHAT_PROTOCOL_ID).getOrElse {
                if (_state.value === connecting) endActive("couldn't reach that peer")
                return@launch
            }
            // Gave up waiting meanwhile (timeout), or an incoming chat got there first.
            if (_state.value !== connecting || activeLink != null) {
                stream.close()
                return@launch
            }
            val link = newLink(stream, isInitiator = true)
            activeLink = link
            _state.value = ChatUiState.Handshaking
            armHandshakeTimeout { activeLink === link && !link.chat.isReady }
            startReadLoop(link)
            try {
                // The request opens with the proof of work, before the Noise handshake proper.
                stream.write(lengthPrefixed(proofOfWork)).getOrThrow()
                link.chat.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "couldn't send handshake", e)
                if (activeLink === link) endActive("peer disconnected")
            }
        }
    }

    override fun sendMessage(text: String) {
        if (text.length > MAX_MESSAGE_CHARS) return
        scope.launch {
            val link = activeLink ?: return@launch
            if (!link.chat.isReady) return@launch
            try {
                openChat.sendText(link.chat, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                endLink(link, "peer disconnected")
            }
        }
    }

    override fun notifyTyping() {
        scope.launch {
            val link = activeLink ?: return@launch
            if (!link.chat.isReady) return@launch
            runCatching { openChat.sendTyping(link.chat) }
        }
    }

    /** Opens a *second* libp2p stream to the peer for the file bytes themselves - the chat
     *  stream only ever carries the small offer envelope, same division of labor
     *  [app.frad.chat.ble.BleChatController] gives BLE (offer) vs. Wi-Fi Direct
     *  (bytes). Direct vs. relayed dialing for that second stream is entirely
     *  [WideRangeNode]/libp2p's own business - never decided here. */
    override fun sendFile(bytes: ByteArray, fileName: String, mimeType: String) {
        if (bytes.size > MAX_TRANSFER_FILE_BYTES) return
        scope.launch {
            val link = activeLink ?: return@launch
            val remotePeerId = link.chat.remotePeer?.peerId ?: return@launch
            if (_transferStatus.value != null) return@launch
            val job = coroutineContext[Job]
            transferJob = job
            try {
                val offer = FileOffer(TransferCipher.newTransferId(), fileName, mimeType, bytes.size.toLong())
                if (!askToSend(link, offer)) return@launch
                val transferKey = link.chat.transferKey(WIDE_TRANSFER_KEY_INFO, offer.transferId, outgoing = true)
                _transferStatus.value = "Sending $fileName…"
                val sent = try {
                    val transferStream = node.openStream(link.stream.remotePeerId, TRANSFER_PROTOCOL_ID).getOrThrow()
                    try {
                        closingOnCancel(transferStream::close) {
                            link.chat.send(ChatEnvelope.WideOffer(offer))
                            writeChunked(bytes, TransferCipher(transferKey), onProgress = progressOf(offer.sizeBytes)) {
                                transferStream.write(it).getOrThrow()
                            }
                        }
                    } finally {
                        transferStream.close()
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "sending file failed", e)
                    false
                }
                if (sent) {
                    val path = mediaFileStore.write(remotePeerId, newMessageId(), bytes).absolutePath
                    openChat.append(fileMessage(fromMe = true, fileName = fileName, mimeType = mimeType, sizeBytes = bytes.size.toLong(), localPath = path))
                } else if (activeLink === link) {
                    _notices.tryEmit("$fileName couldn't be sent.")
                }
            } finally {
                transferEnded(job)
            }
        }
    }

    /** The running file transfer (or request for one), for [cancelTransfer]. */
    private var transferJob: Job? = null

    private fun progressOf(total: Long): (Long) -> Unit = { done ->
        _transferProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    private fun transferEnded(job: Job?) {
        if (transferJob !== job) return
        transferJob = null
        _transferStatus.value = null
        _transferProgress.value = null
    }

    override fun cancelTransfer() {
        scope.launch {
            val job = transferJob ?: return@launch
            transferEnded(job)
            job.cancel()
            _notices.tryEmit("Transfer cancelled.")
        }
    }

    /** Asks the peer (see [OpenChat.requestToSend]); true once they've accepted and the chat is
     *  still the same one. Leaves [_transferStatus] set while waiting, cleared otherwise. */
    private suspend fun askToSend(link: Link, offer: FileOffer): Boolean {
        _transferStatus.value = "Waiting for them to accept ${offer.fileName}…"
        val answer = try {
            openChat.requestToSend(link.chat, offer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "file request failed", e)
            OpenChat.FileAnswer.NO_ANSWER
        } finally {
            _transferStatus.value = null
        }
        if (activeLink !== link) return false
        when (answer) {
            OpenChat.FileAnswer.ACCEPTED -> return true
            OpenChat.FileAnswer.DECLINED -> _notices.tryEmit("${offer.fileName} wasn't sent - they declined it.")
            OpenChat.FileAnswer.NO_ANSWER -> _notices.tryEmit("${offer.fileName} wasn't sent - no answer.")
        }
        return false
    }

    override fun answerIncomingFile(accept: Boolean) {
        scope.launch {
            val link = activeLink ?: return@launch
            if (link.chat.isReady) runCatching { openChat.answerFileRequest(link.chat, accept) }
        }
    }

    private fun receiveFile(link: Link, stream: WideRangeByteStream, offer: FileOffer) {
        val remotePeerId = link.chat.remotePeer?.peerId
        if (remotePeerId == null || _transferStatus.value != null || offer.sizeBytes > MAX_TRANSFER_FILE_BYTES) {
            stream.close()
            return
        }
        val transferKey = link.chat.transferKey(WIDE_TRANSFER_KEY_INFO, offer.transferId, outgoing = false)
        _transferStatus.value = "Receiving ${offer.fileName}…"
        transferJob = scope.launch {
            val job = coroutineContext[Job]
            try {
                val bytes = try {
                    closingOnCancel(stream::close) {
                        readChunked(offer.sizeBytes, TransferCipher(transferKey), onProgress = progressOf(offer.sizeBytes)) {
                            stream.readExactly(it).getOrThrow()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "receiving file failed", e)
                    null
                } finally {
                    stream.close()
                }
                if (bytes != null) {
                    val path = mediaFileStore.write(remotePeerId, newMessageId(), bytes).absolutePath
                    openChat.append(fileMessage(fromMe = false, fileName = offer.fileName, mimeType = FileTypeCheck.verifiedMimeType(bytes, offer.mimeType), sizeBytes = offer.sizeBytes, localPath = path))
                } else if (activeLink === link) {
                    _notices.tryEmit("${offer.fileName} couldn't be received.")
                }
            } finally {
                transferEnded(job)
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

    private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    /** Files exchanged with someone who isn't a saved contact don't outlive the chat - the same
     *  rule chat history follows (see [ChatHistoryStore]). */
    private fun forgetMediaUnlessSaved(peerId: String?) {
        if (peerId != null && !contactStore.isSaved(peerId)) mediaFileStore.delete(peerId)
    }

    override fun endActiveConnection(reason: String) {
        scope.launch { endActive(reason) }
    }

    /** [endActiveConnection], for callers already on [scope]'s dispatcher. */
    private fun endActive(reason: String) {
        transferJob?.let { job -> transferEnded(job); job.cancel() }
        activeLink?.chat?.remotePeer?.peerId?.let(cooldown::recordChatEnded)
        openChat.end()
        activeLink?.let { link ->
            link.earlyTransferStreamTimeout?.cancel()
            link.earlyTransferStream?.close()
            link.earlyTransferStream = null
        }
        forgetMediaUnlessSaved(activeLink?.chat?.remotePeer?.peerId)
        disarmHandshakeTimeout()
        activeLink?.chat?.close()
        activeLink = null
        _transferStatus.value = null
        _state.value = ChatUiState.Ended(reason)
        if (browsing) {
            _state.value = ChatUiState.Browsing(discoveredByPeerId.values.toList())
        }
    }

    /** Ends [link]'s chat if it's still the active one; otherwise just closes [link] - never
     *  whatever other chat may have started since. */
    private fun endLink(link: Link, reason: String) {
        if (activeLink === link) endActive(reason) else link.chat.close()
    }

    override fun blockActivePeer() {
        scope.launch {
            val current = _state.value
            if (current is ChatUiState.Chatting) blockList.block(current.remotePeerId, current.remoteDeviceFingerprint, current.remotePseudonym)
            endActive("blocked")
        }
    }

    override fun reportActivePeer(reason: String) {
        scope.launch {
            val current = _state.value
            if (current is ChatUiState.Chatting) reportFlow.report(current.remotePeerId, current.remoteDeviceFingerprint, current.remotePseudonym, reason)
            endActive("reported")
        }
    }

    override fun acknowledgeEnded() {
        scope.launch { if (_state.value is ChatUiState.Ended) _state.value = ChatUiState.Idle }
    }

    // ---- incoming libp2p streams ----

    private fun onIncomingStream(protocolId: String, stream: WideRangeByteStream) {
        when (protocolId) {
            CHAT_PROTOCOL_ID -> {
                // Busy with (or setting up) another chat, or too many requests already being checked.
                if (activeLink != null || _state.value !is ChatUiState.Browsing || pendingIncomingChecks >= MAX_PENDING_INCOMING_CHECKS) {
                    stream.close()
                    return
                }
                pendingIncomingChecks++
                scope.launch {
                    try {
                        acceptIncomingChat(stream)
                    } finally {
                        pendingIncomingChecks--
                    }
                }
            }
            TRANSFER_PROTOCOL_ID -> {
                val link = activeLink
                // Only the peer we're chatting with may deliver a file, and only one it offered.
                if (link == null || stream.remotePeerId != link.stream.remotePeerId) {
                    stream.close()
                    return
                }
                val offer = link.pendingIncomingOffer
                if (offer != null) {
                    link.pendingIncomingOffer = null
                    receiveFile(link, stream, offer)
                    return
                }
                if (link.earlyTransferStream != null) {
                    stream.close()
                    return
                }
                link.earlyTransferStream = stream
                link.earlyTransferStreamTimeout = scope.launch {
                    delay(EARLY_TRANSFER_STREAM_MILLIS)
                    if (link.earlyTransferStream === stream) {
                        link.earlyTransferStream = null
                        stream.close()
                    }
                }
            }
            else -> stream.close()
        }
    }

    // ---- read loop (the wide-range analogue of BLE's GATT callbacks) ----

    private fun startReadLoop(link: Link) {
        scope.launch {
            while (isActive) {
                val frame = readFrame(link.stream).getOrElse {
                    if (it is FrameTooLargeException) Log.w(TAG, "dropping peer: ${it.message}")
                    if (activeLink === link) endActive("peer disconnected")
                    return@launch
                }
                // Timed out or otherwise ended while this frame was in flight - don't let it
                // resurrect a chat that's already gone.
                if (activeLink !== link) {
                    link.chat.close()
                    return@launch
                }
                // Every frame comes from a peer that may be buggy or hostile - a truncated handshake
                // message, a ciphertext that fails authentication or a malformed envelope ends this
                // one chat, never anything more.
                val event = try {
                    link.chat.onFrame(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "dropping connection after bad frame", e)
                    endLink(link, "connection error")
                    return@launch
                }
                // onFrame may have suspended (sending a reply) - the chat may be gone by now.
                if (activeLink !== link) {
                    link.chat.close()
                    return@launch
                }
                when (event) {
                    null -> Unit
                    ChatEvent.Blocked -> {
                        endLink(link, "blocked peer")
                        return@launch
                    }
                    ChatEvent.AgeGroupMismatch -> {
                        endLink(link, "Not a match: FRAD only connects adults with adults and minors with minors.")
                        return@launch
                    }
                    is ChatEvent.Ready -> {
                        disarmHandshakeTimeout()
                        openChat.start(event.peer, identity.publicKey)
                    }
                    is ChatEvent.Received -> when (val envelope = openChat.onReceived(link.chat, event.envelope)) {
                        null -> Unit
                        is ChatEnvelope.WideOffer -> {
                            // Only what the user accepted, with the metadata they saw when accepting.
                            val accepted = openChat.takeAcceptedFile(envelope.offer.transferId)
                            val early = link.earlyTransferStream
                            if (accepted != null && early != null) {
                                link.earlyTransferStream = null
                                link.earlyTransferStreamTimeout?.cancel()
                                receiveFile(link, early, accepted)
                            } else if (accepted != null) {
                                link.pendingIncomingOffer = accepted
                            } else {
                                Log.w(TAG, "ignoring file offer nobody accepted")
                            }
                        }
                        else -> Log.d(TAG, "ignoring unsupported envelope ${envelope::class.simpleName}")
                    }
                }
            }
        }
    }

    /** An incoming chat request must open with a valid [ProofOfWork] token within
     *  [PROOF_OF_WORK_TIMEOUT_MILLIS]; only then does it take the chat slot, show up in the UI and
     *  get a Noise handshake. A spammer's requests never even flash "Setting up…" on screen. */
    private suspend fun acceptIncomingChat(stream: WideRangeByteStream) {
        // The read itself blocks in Go and can't be cancelled - closing the stream is what ends it.
        val watchdog = scope.launch {
            delay(PROOF_OF_WORK_TIMEOUT_MILLIS)
            stream.close()
        }
        val token = readFrame(stream).getOrNull()
        watchdog.cancel()
        if (token == null || !acceptProofOfWork(stream.remotePeerId, token)) {
            stream.close()
            return
        }
        if (activeLink != null || _state.value !is ChatUiState.Browsing) {
            stream.close() // got busy meanwhile
            return
        }
        val link = newLink(stream, isInitiator = false)
        activeLink = link
        _state.value = ChatUiState.Handshaking
        armHandshakeTimeout { activeLink === link && !link.chat.isReady }
        startReadLoop(link)
    }

    private fun acceptProofOfWork(initiatorId: String, token: ByteArray): Boolean {
        if (!ProofOfWork.verify(token, responderId = node.localPeerId, initiatorId = initiatorId, nowSeconds = nowSeconds())) {
            Log.w(TAG, "rejecting chat request without a valid proof of work")
            return false
        }
        val key = initiatorId + "|" + token.joinToString("") { "%02x".format(it) }
        if (seenProofOfWork.containsKey(key)) {
            Log.w(TAG, "rejecting a replayed proof of work")
            return false
        }
        seenProofOfWork[key] = Unit
        return true
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun lengthPrefixed(bytes: ByteArray): ByteArray = FrameWriter.split(bytes, maxFragmentSize = bytes.size + 4).single()

    /** Fails with [FrameTooLargeException] before allocating anything if the (still
     *  unauthenticated) peer announces a length outside 0..[MAX_FRAME_BYTES]. */
    private suspend fun readFrame(stream: WideRangeByteStream): Result<ByteArray> = runCatching {
        val length = ByteBuffer.wrap(stream.readExactly(4).getOrThrow()).order(ByteOrder.BIG_ENDIAN).int
        if (length !in 0..MAX_FRAME_BYTES) throw FrameTooLargeException(length, MAX_FRAME_BYTES)
        stream.readExactly(length).getOrThrow()
    }

    companion object {
        const val CHAT_PROTOCOL_ID = "/frad/chat/1.0.0"
        const val TRANSFER_PROTOCOL_ID = "/frad/transfer/1.0.0"
        private const val TAG = "WideRangeChatController"
        private const val WIDE_TRANSFER_KEY_INFO = "frad-wide-transfer-v2"
        private const val FIND_PEERS_INTERVAL_MILLIS = 30_000L
        private const val PEER_TTL_MILLIS = 3 * FIND_PEERS_INTERVAL_MILLIS
        private const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
        /** How long a transfer stream may wait for the offer it belongs to. */
        private const val EARLY_TRANSFER_STREAM_MILLIS = 15_000L
        private const val SEEN_PROOF_OF_WORK_CAPACITY = 512
        private const val PROOF_OF_WORK_TIMEOUT_MILLIS = 10_000L
        private const val MAX_PENDING_INCOMING_CHECKS = 4
    }
}
