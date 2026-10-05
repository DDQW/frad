package app.frad.chat.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import app.frad.chat.chat.ChatConnection
import app.frad.chat.chat.ChatController
import app.frad.chat.chat.ChatEnvelope
import app.frad.chat.chat.ChatEvent
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.ChatUiState
import app.frad.chat.chat.FileOffer
import app.frad.chat.chat.FrameTransport
import app.frad.chat.chat.MAX_MESSAGE_CHARS
import app.frad.chat.chat.MAX_TRANSFER_FILE_BYTES
import app.frad.chat.chat.MessageKind
import app.frad.chat.contacts.ChatHistoryStore
import app.frad.chat.contacts.ContactStore
import app.frad.chat.crypto.Identity
import app.frad.chat.crypto.TransferCipher
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

/**
 * Ties the BLE transport ([BlePeripheralServer] + [BleCentralClient]), the shared chat protocol
 * ([ChatConnection]), matching ([RandomMatcher]) and the safety layer
 * ([BlockList]/[Cooldown]/[ReportFlow]) into the single state machine the UI drives. One chat at
 * a time in M1 — see the milestone plan for why.
 */
@OptIn(ExperimentalCoroutinesApi::class) // limitedParallelism
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
    private val deviceSecret = DeviceFingerprint.deviceSecret(context)

    private val peripheral = BlePeripheralServer(context, this)
    private val central = BleCentralClient(context, this)

    // Wi-Fi Direct's "connect with a specific network name/passphrase" API, needed to make
    // sure a file transfer's socket ends up talking to the exact peer already in this chat
    // rather than matching against broadcast peer discovery, only exists from API 29 — see
    // the M3 milestone plan. Below that, file transfer is simply unavailable.
    private val transferManager: WifiDirectTransferManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiDirectTransferManager(context) else null

    // Everything this controller does runs on this one serial dispatcher: BLE callbacks (which
    // arrive on arbitrary binder threads), calls from the UI and the transfer/timeout coroutines
    // all hop onto it first, so none of the state below needs locking of its own. The handler is
    // the last line of defense - a failure is logged, never allowed to take down the whole app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "unexpected error", e) },
    )

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    override val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** Non-null while a file send/receive is in flight; null the rest of the time. */
    private val _transferStatus = MutableStateFlow<String?>(null)
    override val transferStatus: StateFlow<String?> = _transferStatus.asStateFlow()

    override val fileTransferAvailable: Boolean get() = transferManager != null

    /** One GATT link, in either direction, and the chat running over it. */
    private class Link(val address: String, val isOutbound: Boolean, val chat: ChatConnection)

    // Keyed by sessionId (our own app-level id, stable for as long as a peer's peripheral keeps
    // running), not by the underlying BLE MAC address - Android can rotate a device's advertised
    // address independently of that, which previously made the same physical peer reappear under
    // a new key and pile up as a phantom extra "found" device instead of updating in place.
    private val discoveredBySessionId = mutableMapOf<String, NearbyPeer>()
    private val addressBySessionId = mutableMapOf<String, String>()
    private val links = mutableMapOf<String, Link>()
    private var activeAddress: String? = null
    private var browsing = false

    /** Why others can't find us right now, if advertising failed - see [onAdvertisingFailed]. */
    private var advertisingWarning: String? = null

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun bluetoothOn(): Boolean = bluetoothAdapter?.isEnabled == true

    /** Turning Bluetooth (or airplane mode) off tears down the GATT server, advertiser and scan
     *  underneath us; without this the app would keep claiming to be visible while being deaf,
     *  and stay that way after Bluetooth comes back. */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            scope.launch { onBluetoothStateChanged(newState) }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    /** Releases what [init] registered; the controller can't be used afterwards. */
    fun close() {
        runCatching { context.unregisterReceiver(bluetoothStateReceiver) }
        setBrowsing(false)
    }

    private fun browsingState(): ChatUiState =
        if (!bluetoothOn()) ChatUiState.Paused(BLUETOOTH_OFF_REASON)
        else ChatUiState.Browsing(discoveredBySessionId.values.toList(), advertisingWarning)

    private fun onBluetoothStateChanged(newState: Int) {
        if (!browsing) return
        when (newState) {
            BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                if (_state.value is ChatUiState.Paused) return
                Log.d(TAG, "Bluetooth going off - pausing")
                endActive("Bluetooth was turned off")
                central.stopScanning()
                peripheral.stop()
                _state.value = ChatUiState.Paused(BLUETOOTH_OFF_REASON)
            }
            BluetoothAdapter.STATE_ON -> {
                if (_state.value !is ChatUiState.Paused) return
                Log.d(TAG, "Bluetooth back on - resuming")
                startRadios()
            }
        }
    }

    /** (Re)starts advertising and scanning from a clean peer list. */
    private fun startRadios() {
        discoveredBySessionId.clear()
        addressBySessionId.clear()
        advertisingWarning = null
        peripheral.stop()
        startAdvertisingSession()
        central.startScanning()
        _state.value = browsingState()
    }

    /** Guards against a stuck [ChatUiState.Connecting]/[ChatUiState.Handshaking]: if a peer
     *  drops off mid-handshake, or a connection attempt never completes, this brings the UI
     *  back to browsing instead of hanging forever and forcing the user to restart the app. */
    private var connectionTimeoutJob: Job? = null

    /** Runs while browsing: [forgetStalePeers] and [rotateSessionId] on their own timers. */
    private var browsingJob: Job? = null

    private fun newLink(address: String, isOutbound: Boolean): Link {
        val transport = object : FrameTransport {
            override suspend fun send(frame: ByteArray) {
                if (isOutbound) central.sendFrame(address, frame) else peripheral.sendFrame(address, frame)
            }

            override fun close() {
                if (isOutbound) central.disconnect(address) else peripheral.disconnectDevice(address)
            }
        }
        val chat = ChatConnection(
            isInitiator = isOutbound,
            identity = identity,
            transport = transport,
            deviceFingerprintFor = { DeviceFingerprint.forPeer(deviceSecret, it) },
            localProfile = { ProfileEnvelope.encode(context, profile) },
            isBlocked = { blockList.isBlocked(it) },
        )
        return Link(address, isOutbound, chat).also { links[address] = it }
    }

    private fun activeLink(): Link? = activeAddress?.let { links[it] }

    private fun newSessionId(): ByteArray = Random.nextBytes(GattProfile.MAX_ADVERTISED_SESSION_ID_BYTES)

    private fun startAdvertisingSession() {
        peripheral.start(newSessionId())
    }

    /** The advertised session id is what scanners see; if it stayed the same for hours while
     *  "always visible" is on, it would link every one of Android's periodic Bluetooth address
     *  rotations together and make this phone trackable. Swapped regularly - but never while a
     *  chat is being set up or running, since the peer may still be dialing the old one. */
    private fun rotateSessionId() {
        if (activeAddress == null) peripheral.rotateSessionId(newSessionId())
    }

    private fun armConnectionTimeout(address: String) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MILLIS)
            if (activeAddress == address) {
                Log.w(TAG, "connection timed out, addr=$address state=${_state.value}")
                endActive("connection timed out")
            }
        }
    }

    private fun disarmConnectionTimeout() {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
    }

    /** The user-facing "make me discoverable" toggle. */
    override fun setBrowsing(enabled: Boolean) {
        scope.launch {
            if (enabled == browsing) return@launch
            browsing = enabled
            if (enabled) {
                if (bluetoothOn()) startRadios() else _state.value = ChatUiState.Paused(BLUETOOTH_OFF_REASON)
                browsingJob = scope.launch {
                    launch {
                        while (true) {
                            delay(STALE_PEER_CHECK_MILLIS)
                            forgetStalePeers()
                        }
                    }
                    launch {
                        while (true) {
                            delay(SESSION_ROTATION_MILLIS)
                            rotateSessionId()
                        }
                    }
                }
            } else {
                browsingJob?.cancel()
                browsingJob = null
                central.stopScanning()
                peripheral.stop()
                endActive("stopped browsing")
                _state.value = ChatUiState.Idle
            }
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
        scope.launch {
            if (_state.value !is ChatUiState.Browsing) return@launch
            val picked = matcher.pickRandomPeer(discoveredBySessionId.values.toList()) ?: return@launch
            if (!cooldown.canRequest(picked.sessionId)) return@launch
            val address = addressBySessionId[picked.sessionId] ?: return@launch

            Log.d(TAG, "requestRandomChat -> picked sessionId=${picked.sessionId} addr=$address")
            cooldown.recordRequest(picked.sessionId)
            activeAddress = address
            _state.value = ChatUiState.Connecting(picked)
            armConnectionTimeout(address)

            central.stopScanning() // one conversation at a time
            peripheral.stop()
            central.connect(address)
        }
    }

    /** A scan only ever reports who's advertising, never who stopped: drop peers not heard from
     *  for a while (walked away, went invisible, started a chat elsewhere), so the count shown and
     *  [requestRandomChat]'s pick stay current. */
    private fun forgetStalePeers() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MILLIS
        val stale = discoveredBySessionId.filterValues { it.lastSeenAtMillis < cutoff }.keys
        if (stale.isEmpty()) return
        stale.forEach {
            discoveredBySessionId.remove(it)
            addressBySessionId.remove(it)
        }
        if (_state.value is ChatUiState.Browsing) _state.value = browsingState()
    }

    override fun sendMessage(text: String) {
        if (text.length > MAX_MESSAGE_CHARS) return
        scope.launch {
            val link = activeLink() ?: return@launch
            if (!link.chat.isReady) return@launch
            link.chat.send(ChatEnvelope.Text(text))
            appendMessage(ChatMessage(fromMe = true, text = text, atMillis = System.currentTimeMillis()))
        }
    }

    /** Sends [bytes] to the active peer over Wi-Fi Direct once [transferManager] confirms the
     *  peer joined; the BLE channel only ever carries the small offer envelope (network name/
     *  passphrase/metadata), never the file itself. No-op if a transfer is already in flight,
     *  [fileTransferAvailable] is false, or [bytes] exceeds [MAX_TRANSFER_FILE_BYTES]. */
    override fun sendFile(bytes: ByteArray, fileName: String, mimeType: String) {
        val manager = transferManager ?: return
        if (bytes.size > MAX_TRANSFER_FILE_BYTES) return
        scope.launch {
            val link = activeLink() ?: return@launch
            val remotePeerId = link.chat.remotePeer?.peerId ?: return@launch
            if (_transferStatus.value != null) return@launch

            val offer = FileOffer(TransferCipher.newTransferId(), fileName, mimeType, bytes.size.toLong())
            link.chat.claimTransferId(offer.transferId)
            val transferKey = link.chat.transferKey(WFD_TRANSFER_KEY_INFO, offer.transferId, outgoing = true)
            _transferStatus.value = "Sending $fileName…"
            val result = manager.hostAndSendFile(bytes, transferKey) { credentials ->
                link.chat.send(ChatEnvelope.WfdOffer(offer, networkName = credentials.networkName, passphrase = credentials.passphrase))
            }
            _transferStatus.value = null
            result.onSuccess {
                val path = mediaFileStore.write(remotePeerId, newMessageId(), bytes).absolutePath
                appendMessage(fileMessage(fromMe = true, fileName = fileName, mimeType = mimeType, sizeBytes = bytes.size.toLong(), localPath = path))
            }
        }
    }

    private fun receiveFile(link: Link, envelope: ChatEnvelope.WfdOffer) {
        val offer = envelope.offer
        val manager = transferManager
        val remotePeerId = link.chat.remotePeer?.peerId ?: return
        if (manager == null || _transferStatus.value != null || offer.sizeBytes > MAX_TRANSFER_FILE_BYTES) return
        if (!link.chat.claimTransferId(offer.transferId)) {
            Log.w(TAG, "ignoring file offer reusing transfer id ${offer.transferId}")
            return
        }

        val transferKey = link.chat.transferKey(WFD_TRANSFER_KEY_INFO, offer.transferId, outgoing = false)
        val credentials = WfdCredentials(networkName = envelope.networkName, passphrase = envelope.passphrase)
        _transferStatus.value = "Receiving ${offer.fileName}…"
        scope.launch {
            val result = manager.joinAndReceiveFile(credentials, transferKey, offer.sizeBytes)
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

    /** Files exchanged with someone who isn't a saved contact don't outlive the chat - the same
     *  rule chat history follows (see [persistIfSaved]). */
    private fun forgetMediaUnlessSaved(peerId: String?) {
        if (peerId != null && !contactStore.isSaved(peerId)) mediaFileStore.delete(peerId)
    }

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
        scope.launch { endActive(reason) }
    }

    /** [endActiveConnection], for callers already on [scope]'s dispatcher. */
    private fun endActive(reason: String) {
        forgetMediaUnlessSaved(activeLink()?.chat?.remotePeer?.peerId)
        disarmConnectionTimeout()
        activeAddress?.let { address -> links.remove(address)?.chat?.close() ?: peripheral.disconnectDevice(address) }
        activeAddress = null
        _transferStatus.value = null
        _state.value = ChatUiState.Ended(reason)
        if (browsing && bluetoothOn()) {
            // The peer list may now be stale — a peer's session id rotates each time its own
            // peripheral restarts (including right after a connection attempt like this one
            // fails on its end too), so leftover entries here would otherwise just accumulate
            // as phantom "one more device" duplicates rather than being replaced. Start clean
            // and let scanning repopulate it.
            startRadios()
        }
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

    /** Drops one link from our side - a blocked peer or a protocol error - and, if it was the
     *  active chat, ends that too. */
    private fun abortLink(address: String, reason: String) {
        links.remove(address)?.chat?.close()
        if (address == activeAddress) endActive(reason)
    }

    // ---- BlePeripheralServer.Listener (inbound / "someone connected to us") ----

    override fun onCentralConnected(deviceAddress: String) {
        scope.launch {
            // Normally activeAddress is only set once we're already talking to someone, so any
            // other inbound connection is "busy, go away". But requestRandomChat also sets it
            // (state Connecting) while our own outbound attempt is still pending - an inbound
            // connection arriving then (most likely the very peer we picked, dialing us at the
            // same moment) takes over instead, and the outbound one is dropped in onConnected.
            val awaitingInboundHandshake = _state.value is ChatUiState.Connecting
            Log.d(TAG, "onCentralConnected addr=$deviceAddress activeAddress=$activeAddress awaitingInboundHandshake=$awaitingInboundHandshake")
            if (activeAddress != null && !awaitingInboundHandshake) {
                peripheral.disconnectDevice(deviceAddress) // already busy with another chat
                return@launch
            }
            activeAddress = deviceAddress
            newLink(deviceAddress, isOutbound = false)
            armConnectionTimeout(deviceAddress)
            _state.value = ChatUiState.Handshaking
        }
    }

    override fun onCentralDisconnected(deviceAddress: String) {
        scope.launch {
            Log.d(TAG, "onCentralDisconnected addr=$deviceAddress")
            if (deviceAddress == activeAddress) endActive("peer disconnected")
            links.remove(deviceAddress)
        }
    }

    override fun onFrameReceived(deviceAddress: String, frame: ByteArray) {
        scope.launch { handleFrame(deviceAddress, frame) }
    }

    override fun onAdvertisingStarted() {
        scope.launch {
            advertisingWarning = null
            if (_state.value is ChatUiState.Browsing) _state.value = browsingState()
        }
    }

    override fun onAdvertisingFailed(errorCode: Int) {
        scope.launch {
            if (!bluetoothOn()) return@launch // reported as Paused instead
            advertisingWarning = when (errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                    "This phone can't announce itself over Bluetooth, so others can't find you - you can still find them."
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                    "Too many apps are using Bluetooth announcements right now, so others can't find you."
                else -> "Others can't find you right now (Bluetooth announcement failed, code $errorCode)."
            }
            if (_state.value is ChatUiState.Browsing) _state.value = browsingState()
        }
    }

    override fun acknowledgeEnded() {
        scope.launch { if (_state.value is ChatUiState.Ended) _state.value = ChatUiState.Idle }
    }

    // ---- BleCentralClient.Listener (outbound / "we connected to someone") ----

    override fun onPeerDiscovered(deviceAddress: String, sessionId: ByteArray, rssi: Int) {
        scope.launch {
            val sessionIdHex = sessionId.joinToString("") { "%02x".format(it) }
            // Always refreshed to the latest address seen for this session id, in case the
            // underlying BLE address rotated since we last heard from this same peer.
            addressBySessionId[sessionIdHex] = deviceAddress
            discoveredBySessionId[sessionIdHex] = NearbyPeer(
                sessionId = sessionIdHex,
                lastSeenAtMillis = System.currentTimeMillis(),
                signalStrength = SignalStrength.Ble(rssi),
            )
            if (_state.value is ChatUiState.Browsing) _state.value = browsingState()
        }
    }

    override fun onConnected(deviceAddress: String) {
        scope.launch {
            if (deviceAddress != activeAddress) {
                // Timed out meanwhile, or an inbound chat took over (see onCentralConnected).
                Log.d(TAG, "onConnected (outbound) addr=$deviceAddress no longer wanted - disconnecting")
                central.disconnect(deviceAddress)
                return@launch
            }
            Log.d(TAG, "onConnected (outbound) addr=$deviceAddress - sending handshake message 1")
            val link = newLink(deviceAddress, isOutbound = true)
            armConnectionTimeout(deviceAddress)
            _state.value = ChatUiState.Handshaking
            link.chat.start()
        }
    }

    override fun onDisconnected(deviceAddress: String) {
        scope.launch {
            Log.d(TAG, "onDisconnected (outbound) addr=$deviceAddress")
            if (deviceAddress == activeAddress) endActive("peer disconnected")
            links.remove(deviceAddress)
        }
    }

    // ---- shared frame routing ----

    /** Every frame comes from a peer that may be buggy or hostile - a truncated handshake message,
     *  a ciphertext that fails authentication or a malformed envelope ends that one link, never
     *  anything more. */
    private suspend fun handleFrame(deviceAddress: String, frame: ByteArray) {
        val link = links[deviceAddress] ?: return
        val event = try {
            link.chat.onFrame(frame)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "dropping link after bad frame from $deviceAddress", e)
            abortLink(deviceAddress, "connection error")
            return
        }
        when (event) {
            null -> Unit
            ChatEvent.Blocked -> abortLink(deviceAddress, "blocked peer")
            is ChatEvent.Ready -> {
                if (deviceAddress != activeAddress) {
                    abortLink(deviceAddress, "superseded")
                    return
                }
                disarmConnectionTimeout()
                val peer = event.peer
                _state.value = ChatUiState.Chatting(
                    remotePeerId = peer.peerId,
                    remoteDeviceFingerprint = peer.deviceFingerprint,
                    remotePseudonym = peer.profile.pseudonym,
                    remoteGender = peer.profile.gender,
                    remoteAge = peer.profile.age,
                    remoteBio = peer.profile.bio,
                    remotePhoto = peer.profile.photo,
                    messages = if (contactStore.isSaved(peer.peerId)) historyStore.messagesFor(peer.peerId) else emptyList(),
                )
            }
            is ChatEvent.Received -> when (val envelope = event.envelope) {
                is ChatEnvelope.Text -> appendMessage(ChatMessage(fromMe = false, text = envelope.text, atMillis = System.currentTimeMillis()))
                is ChatEnvelope.WfdOffer -> receiveFile(link, envelope)
                is ChatEnvelope.WideOffer, is ChatEnvelope.Unknown ->
                    Log.d(TAG, "ignoring unsupported envelope ${envelope::class.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "BleChatController"
        const val CONNECTION_TIMEOUT_MILLIS = 15_000L
        const val STALE_PEER_CHECK_MILLIS = 10_000L
        const val PEER_TTL_MILLIS = 30_000L
        const val SESSION_ROTATION_MILLIS = 10 * 60_000L
        const val BLUETOOTH_OFF_REASON = "Bluetooth is off - FRAD continues automatically once it's back on."
        const val WFD_TRANSFER_KEY_INFO = "frad-wfd-media-v2"
    }
}
