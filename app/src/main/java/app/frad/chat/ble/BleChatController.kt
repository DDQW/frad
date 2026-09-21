package app.frad.chat.ble

import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.frad.chat.chat.ChatController
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.ChatUiState
import app.frad.chat.chat.MAX_TRANSFER_FILE_BYTES
import app.frad.chat.chat.MessageKind
import app.frad.chat.contacts.ChatHistoryStore
import app.frad.chat.contacts.ContactStore
import app.frad.chat.crypto.ChatSession
import app.frad.chat.crypto.Identity
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
import app.frad.chat.wifidirect.WfdCredentials
import app.frad.chat.wifidirect.WifiDirectTransferManager
import org.json.JSONObject

/**
 * Ties the BLE transport ([BlePeripheralServer] + [BleCentralClient]), the
 * Noise handshake ([ChatSession]), matching ([RandomMatcher]) and the safety
 * layer ([BlockList]/[Cooldown]/[ReportFlow]) into the single state machine the
 * UI drives. One chat at a time in M1 — see the milestone plan for why.
 */
class BleChatController(
    private val context: Context,
    private val identity: Identity,
    private val profile: Profile,
) : ChatController, BlePeripheralServer.Listener, BleCentralClient.Listener {

    private val blockList = BlockList(context)
    private val contactStore = ContactStore(context)
    private val historyStore = ChatHistoryStore(context)
    private val mediaFileStore = MediaFileStore(context)
    private val cooldown = Cooldown()
    private val reportFlow = ReportFlow(context, blockList)
    private val matcher = RandomMatcher()
    private val deviceFingerprint = DeviceFingerprint.compute(context)

    private val peripheral = BlePeripheralServer(context, this)
    private val central = BleCentralClient(context, this)

    // Wi-Fi Direct's "connect with a specific network name/passphrase" API, needed to make
    // sure a file transfer's socket ends up talking to the exact peer already in this chat
    // rather than matching against broadcast peer discovery, only exists from API 29 — see
    // the M3 milestone plan. Below that, file transfer is simply unavailable.
    private val transferManager: WifiDirectTransferManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiDirectTransferManager(context) else null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    override val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Non-null while a file send/receive is in flight; null the rest of the time. */
    private val _transferStatus = MutableStateFlow<String?>(null)
    override val transferStatus: StateFlow<String?> = _transferStatus.asStateFlow()

    override val fileTransferAvailable: Boolean get() = transferManager != null

    /** After the Noise handshake finishes, both sides immediately exchange two more encrypted
     *  messages — their device fingerprint, then their pseudonym — before the chat is considered
     *  open. The fingerprint round trip lets each side re-check the other against [blockList] by
     *  device (not just by identity key) before revealing anything human-readable; see M5. */
    private enum class HandshakeStep { EXPECT_MESSAGE_1, EXPECT_MESSAGE_2, EXPECT_MESSAGE_3, EXPECT_DEVICE_ID, EXPECT_PROFILE, READY }

    private class Connection(val session: ChatSession, val isOutbound: Boolean) {
        var step: HandshakeStep = if (session.isReady) HandshakeStep.READY
        else if (isOutbound) HandshakeStep.EXPECT_MESSAGE_2 else HandshakeStep.EXPECT_MESSAGE_1
        var remoteDeviceFingerprint: String? = null
    }

    // BLE callbacks (peripheral GATT server, central GATT client, scan results) can each
    // land on their own binder thread, so these shared maps need to tolerate concurrent
    // access; synchronizedMap is a coarse but adequate mitigation for M1's single-active-
    // connection scope. A later milestone should route everything through one serial
    // dispatcher instead of relying on this.
    // Keyed by sessionId (our own app-level id, stable for as long as a peer's peripheral keeps
    // running), not by the underlying BLE MAC address - Android can rotate a device's advertised
    // address independently of that, which previously made the same physical peer reappear under
    // a new key and pile up as a phantom extra "found" device instead of updating in place.
    private val discoveredBySessionId = java.util.Collections.synchronizedMap(mutableMapOf<String, NearbyPeer>())
    private val addressBySessionId = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val connections = java.util.Collections.synchronizedMap(mutableMapOf<String, Connection>())
    private var activeAddress: String? = null
    private var browsing = false

    /** Guards against a stuck [ChatUiState.Connecting]/[ChatUiState.Handshaking]: if a peer
     *  drops off mid-handshake, or a connection attempt never completes, this brings the UI
     *  back to browsing instead of hanging forever and forcing the user to restart the app. */
    private var connectionTimeoutJob: Job? = null

    private fun startAdvertisingSession() {
        peripheral.start(Random.nextBytes(GattProfile.MAX_ADVERTISED_SESSION_ID_BYTES))
    }

    private fun armConnectionTimeout(address: String) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MILLIS)
            if (activeAddress == address) {
                Log.w(TAG, "connection timed out, addr=$address state=${_state.value}")
                endActiveConnection("connection timed out")
            }
        }
    }

    private fun disarmConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    /** The user-facing "make me discoverable" toggle. Off by default and never
     *  persisted across app restarts — see the safety-by-design notes in the plan. */
    override fun setBrowsing(enabled: Boolean) {
        if (enabled == browsing) return
        browsing = enabled
        if (enabled) {
            discoveredBySessionId.clear()
            addressBySessionId.clear()
            startAdvertisingSession()
            central.startScanning()
            _state.value = ChatUiState.Browsing(emptyList())
        } else {
            central.stopScanning()
            peripheral.stop()
            endActiveConnection("stopped browsing")
            _state.value = ChatUiState.Idle
        }
    }

    /** Picks a random currently-visible peer and starts a chat with them.
     *
     *  Both sides of a chat run the same BLE stack, so in the rare case both users tap "chat"
     *  at almost the same instant and each pick the other, both dial out while tearing down
     *  their own peripheral — each connection attempt then fails since the peripheral it was
     *  aimed at is already gone (a classic BLE "glare"). That's self-healing: [armConnectionTimeout]
     *  below returns both sides to Browsing after 15s so a retry can succeed, since the exact
     *  timing won't line up identically twice. A previous version tried to pre-empt this by
     *  having whichever side's advertised session id sorted higher defer instead of connecting
     *  at all — but that comparison ran for *every* chat request, contested or not, so on
     *  ordinary, unilateral "chat with someone nearby" taps it deferred to a peer who was never
     *  trying to connect back roughly half the time, leaving the tapper stuck on "Connecting…"
     *  and the other device never even seeing a connection attempt. Always connecting is the
     *  better trade: the common case works every time, and the rare double-tap case just costs
     *  one 15s timeout instead of hanging indefinitely. */
    override fun requestRandomChat() {
        if (_state.value !is ChatUiState.Browsing) return
        val picked = matcher.pickRandomPeer(discoveredBySessionId.values.toList()) ?: return
        if (!cooldown.canRequest(picked.sessionId)) return
        val address = addressBySessionId[picked.sessionId] ?: return

        Log.d(TAG, "requestRandomChat -> picked sessionId=${picked.sessionId} addr=$address")
        cooldown.recordRequest(picked.sessionId)
        activeAddress = address
        _state.value = ChatUiState.Connecting(picked)
        armConnectionTimeout(address)

        central.stopScanning() // one conversation at a time
        peripheral.stop()
        central.connect(address)
    }

    override fun sendMessage(text: String) {
        val address = activeAddress ?: return
        val connection = connections[address] ?: return
        if (connection.step != HandshakeStep.READY) return

        sendEnvelope(address, connection, JSONObject().put("k", "txt").put("t", text).toString())
        appendMessage(ChatMessage(fromMe = true, text = text, atMillis = System.currentTimeMillis()))
    }

    /** Sends [bytes] to the active peer over Wi-Fi Direct once [transferManager] confirms the
     *  peer joined; the BLE channel only ever carries the small offer envelope (network name/
     *  passphrase/metadata), never the file itself. No-op if a transfer is already in flight,
     *  [fileTransferAvailable] is false, or [bytes] exceeds [MAX_TRANSFER_FILE_BYTES]. */
    override fun sendFile(bytes: ByteArray, fileName: String, mimeType: String) {
        val manager = transferManager ?: return
        val address = activeAddress ?: return
        val connection = connections[address] ?: return
        if (connection.step != HandshakeStep.READY) return
        if (_transferStatus.value != null) return
        if (bytes.size > MAX_TRANSFER_FILE_BYTES) return

        val remotePeerId = connection.session.remotePeerId()
        val transferKey = connection.session.deriveTransferKey()
        _transferStatus.value = "Sending $fileName…"
        scope.launch {
            val result = manager.hostAndSendFile(bytes, transferKey) { credentials ->
                sendEnvelope(address, connection, encodeFileOffer(credentials, fileName, mimeType, bytes.size.toLong()))
            }
            _transferStatus.value = null
            result.onSuccess {
                val path = mediaFileStore.write(remotePeerId, newMessageId(), bytes).absolutePath
                appendMessage(fileMessage(fromMe = true, fileName = fileName, mimeType = mimeType, sizeBytes = bytes.size.toLong(), localPath = path))
            }
        }
    }

    private fun receiveFile(connection: Connection, offer: FileOffer) {
        val manager = transferManager
        if (manager == null || _transferStatus.value != null || offer.sizeBytes > MAX_TRANSFER_FILE_BYTES) return

        val remotePeerId = connection.session.remotePeerId()
        val transferKey = connection.session.deriveTransferKey()
        _transferStatus.value = "Receiving ${offer.fileName}…"
        scope.launch {
            val result = manager.joinAndReceiveFile(offer.credentials, transferKey, offer.sizeBytes)
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

    private fun newMessageId(): String = java.util.UUID.randomUUID().toString()

    private fun sendEnvelope(address: String, connection: Connection, json: String) {
        val ciphertext = connection.session.encryptMessage(json)
        if (connection.isOutbound) central.sendFrame(address, ciphertext) else peripheral.sendFrame(address, ciphertext)
    }

    private data class FileOffer(val credentials: WfdCredentials, val fileName: String, val mimeType: String, val sizeBytes: Long)

    private fun encodeFileOffer(credentials: WfdCredentials, fileName: String, mimeType: String, sizeBytes: Long): String =
        JSONObject()
            .put("k", "wfd")
            .put("ssid", credentials.networkName)
            .put("pass", credentials.passphrase)
            .put("name", fileName)
            .put("mime", mimeType)
            .put("size", sizeBytes)
            .toString()

    /** Chat history is only ever written to disk for peers the user chose to save as a
     *  contact - see [ChatHistoryStore]. */
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
        disarmConnectionTimeout()
        val address = activeAddress
        if (address != null) {
            val connection = connections[address]
            if (connection?.isOutbound == true) central.disconnect(address) else peripheral.disconnectDevice(address)
            connections.remove(address)
        }
        activeAddress = null
        _transferStatus.value = null
        _state.value = ChatUiState.Ended(reason)
        if (browsing) {
            // The peer list may now be stale — a peer's session id rotates each time its own
            // peripheral restarts (including right after a connection attempt like this one
            // fails on its end too), so leftover entries here would otherwise just accumulate
            // as phantom "one more device" duplicates rather than being replaced. Start clean
            // and let scanning repopulate it.
            discoveredBySessionId.clear()
            addressBySessionId.clear()
            peripheral.stop()
            startAdvertisingSession()
            central.startScanning()
            _state.value = ChatUiState.Browsing(emptyList())
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

    // ---- BlePeripheralServer.Listener (inbound / "someone connected to us") ----

    override fun onCentralConnected(deviceAddress: String) {
        // Normally activeAddress is only set once we're already talking to someone, so any
        // other inbound connection is "busy, go away". But requestRandomChat also sets it
        // (state Connecting) for the side that's deferring to the other's connection attempt
        // instead of dialing out itself (see its comment) — that's this connection arriving,
        // not a second one, even though the address the remote connects in on isn't guaranteed
        // to be the exact address we originally discovered them at while scanning.
        val awaitingInboundHandshake = _state.value is ChatUiState.Connecting
        Log.d(TAG, "onCentralConnected addr=$deviceAddress activeAddress=$activeAddress awaitingInboundHandshake=$awaitingInboundHandshake")
        if (activeAddress != null && !awaitingInboundHandshake) {
            peripheral.disconnectDevice(deviceAddress) // already busy with another chat
            return
        }
        activeAddress = deviceAddress
        connections[deviceAddress] = Connection(ChatSession(isInitiator = false, identity = identity), isOutbound = false)
        armConnectionTimeout(deviceAddress)
        _state.value = ChatUiState.Handshaking
    }

    override fun onCentralDisconnected(deviceAddress: String) {
        Log.d(TAG, "onCentralDisconnected addr=$deviceAddress")
        if (deviceAddress == activeAddress) endActiveConnection("peer disconnected")
    }

    override fun onFrameReceived(deviceAddress: String, frame: ByteArray) = handleFrame(deviceAddress, frame)

    // ---- BleCentralClient.Listener (outbound / "we connected to someone") ----

    override fun onPeerDiscovered(deviceAddress: String, sessionId: ByteArray, rssi: Int) {
        val sessionIdHex = sessionId.joinToString("") { "%02x".format(it) }
        // Always refreshed to the latest address seen for this session id, in case the
        // underlying BLE address rotated since we last heard from this same peer.
        addressBySessionId[sessionIdHex] = deviceAddress
        discoveredBySessionId[sessionIdHex] = NearbyPeer(
            sessionId = sessionIdHex,
            lastSeenAtMillis = System.currentTimeMillis(),
            signalStrength = SignalStrength.Ble(rssi),
        )
        if (_state.value is ChatUiState.Browsing) {
            _state.value = ChatUiState.Browsing(discoveredBySessionId.values.toList())
        }
    }

    override fun onConnected(deviceAddress: String) {
        Log.d(TAG, "onConnected (outbound) addr=$deviceAddress - sending handshake message 1")
        val connection = Connection(ChatSession(isInitiator = true, identity = identity), isOutbound = true)
        connections[deviceAddress] = connection
        armConnectionTimeout(deviceAddress)
        _state.value = ChatUiState.Handshaking
        central.sendFrame(deviceAddress, connection.session.startHandshake())
    }

    override fun onDisconnected(deviceAddress: String) {
        Log.d(TAG, "onDisconnected (outbound) addr=$deviceAddress")
        if (deviceAddress == activeAddress) endActiveConnection("peer disconnected")
    }

    // ---- shared handshake/chat frame routing ----

    private fun handleFrame(deviceAddress: String, frame: ByteArray) {
        val connection = connections[deviceAddress] ?: return
        Log.d(TAG, "handleFrame addr=$deviceAddress step=${connection.step} bytes=${frame.size}")
        when (connection.step) {
            HandshakeStep.EXPECT_MESSAGE_1 -> {
                val message2 = connection.session.respondToHandshake(frame)
                peripheral.sendFrame(deviceAddress, message2)
                connection.step = HandshakeStep.EXPECT_MESSAGE_3
            }
            HandshakeStep.EXPECT_MESSAGE_2 -> {
                val message3 = connection.session.completeHandshake(frame)
                central.sendFrame(deviceAddress, message3)
                advanceToDeviceIdExchange(deviceAddress, connection)
            }
            HandshakeStep.EXPECT_MESSAGE_3 -> {
                connection.session.finishHandshake(frame)
                advanceToDeviceIdExchange(deviceAddress, connection)
            }
            HandshakeStep.EXPECT_DEVICE_ID -> {
                val remoteFingerprint = connection.session.decryptMessage(frame)
                if (blockList.isBlocked(remoteFingerprint)) {
                    if (connection.isOutbound) central.disconnect(deviceAddress) else peripheral.disconnectDevice(deviceAddress)
                    connections.remove(deviceAddress)
                    endActiveConnection("blocked peer")
                    return
                }
                connection.remoteDeviceFingerprint = remoteFingerprint
                connection.step = HandshakeStep.EXPECT_PROFILE
                val ciphertext = connection.session.encryptMessage(ProfileEnvelope.encode(context, profile))
                if (connection.isOutbound) central.sendFrame(deviceAddress, ciphertext) else peripheral.sendFrame(deviceAddress, ciphertext)
            }
            HandshakeStep.EXPECT_PROFILE -> {
                val remoteProfile = ProfileEnvelope.decode(connection.session.decryptMessage(frame))
                connection.step = HandshakeStep.READY
                disarmConnectionTimeout()
                val remotePeerId = connection.session.remotePeerId()
                _state.value = ChatUiState.Chatting(
                    remotePeerId = remotePeerId,
                    remoteDeviceFingerprint = connection.remoteDeviceFingerprint!!,
                    remotePseudonym = remoteProfile.pseudonym,
                    remoteGender = remoteProfile.gender,
                    remoteAge = remoteProfile.age,
                    remoteBio = remoteProfile.bio,
                    remotePhoto = remoteProfile.photo,
                    messages = if (contactStore.isSaved(remotePeerId)) historyStore.messagesFor(remotePeerId) else emptyList(),
                )
            }
            HandshakeStep.READY -> {
                val envelope = JSONObject(connection.session.decryptMessage(frame))
                when (envelope.getString("k")) {
                    "wfd" -> receiveFile(
                        connection,
                        FileOffer(
                            credentials = WfdCredentials(networkName = envelope.getString("ssid"), passphrase = envelope.getString("pass")),
                            fileName = envelope.getString("name"),
                            mimeType = envelope.getString("mime"),
                            sizeBytes = envelope.getLong("size"),
                        ),
                    )
                    else -> appendMessage(ChatMessage(fromMe = false, text = envelope.getString("t"), atMillis = System.currentTimeMillis()))
                }
            }
        }
    }

    /** The Noise handshake is done and transport keys are ready. Before showing any chat UI,
     *  check the peer's now-revealed long-term identity against the block list — discovery only
     *  ever exposes rotating session ids, so this is the first point blocking can be enforced —
     *  then trade device fingerprints for a second, identity-independent block check (M5), and
     *  only once both pass, trade pseudonyms, so a blocked peer never learns ours. */
    private fun advanceToDeviceIdExchange(deviceAddress: String, connection: Connection) {
        val remotePeerId = connection.session.remotePeerId()
        if (blockList.isBlocked(remotePeerId)) {
            if (connection.isOutbound) central.disconnect(deviceAddress) else peripheral.disconnectDevice(deviceAddress)
            connections.remove(deviceAddress)
            endActiveConnection("blocked peer")
            return
        }
        connection.step = HandshakeStep.EXPECT_DEVICE_ID
        val ciphertext = connection.session.encryptMessage(deviceFingerprint)
        if (connection.isOutbound) central.sendFrame(deviceAddress, ciphertext) else peripheral.sendFrame(deviceAddress, ciphertext)
    }

    private companion object {
        const val TAG = "BleChatController"
        const val CONNECTION_TIMEOUT_MILLIS = 15_000L
    }
}
