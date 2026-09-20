package me.woelki.friendradar.ble

import android.content.Context
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.woelki.friendradar.crypto.ChatSession
import me.woelki.friendradar.crypto.Identity
import me.woelki.friendradar.pairing.NearbyPeer
import me.woelki.friendradar.pairing.RandomMatcher
import me.woelki.friendradar.safety.BlockList
import me.woelki.friendradar.safety.Cooldown
import me.woelki.friendradar.safety.ReportFlow

data class ChatMessage(val fromMe: Boolean, val text: String, val atMillis: Long)

sealed interface ChatUiState {
    /** Broadcasting is off; nothing is happening. */
    data object Idle : ChatUiState

    /** Opted in: advertising presence and scanning for others who are too. */
    data class Browsing(val nearbyPeers: List<NearbyPeer>) : ChatUiState

    data class Connecting(val target: NearbyPeer) : ChatUiState
    data object Handshaking : ChatUiState
    data class Chatting(val remotePeerId: String, val messages: List<ChatMessage>) : ChatUiState
    data class Ended(val reason: String) : ChatUiState
}

/**
 * Ties the BLE transport ([BlePeripheralServer] + [BleCentralClient]), the
 * Noise handshake ([ChatSession]), matching ([RandomMatcher]) and the safety
 * layer ([BlockList]/[Cooldown]/[ReportFlow]) into the single state machine the
 * UI drives. One chat at a time in M1 — see the milestone plan for why.
 */
class BleChatController(
    private val context: Context,
    private val identity: Identity,
) : BlePeripheralServer.Listener, BleCentralClient.Listener {

    private val blockList = BlockList(context)
    private val cooldown = Cooldown()
    private val reportFlow = ReportFlow(context, blockList)
    private val matcher = RandomMatcher()

    private val peripheral = BlePeripheralServer(context, this)
    private val central = BleCentralClient(context, this)

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private enum class HandshakeStep { EXPECT_MESSAGE_1, EXPECT_MESSAGE_2, EXPECT_MESSAGE_3, DONE }

    private class Connection(val session: ChatSession, val isOutbound: Boolean) {
        var step: HandshakeStep = if (session.isReady) HandshakeStep.DONE
        else if (isOutbound) HandshakeStep.EXPECT_MESSAGE_2 else HandshakeStep.EXPECT_MESSAGE_1
    }

    // BLE callbacks (peripheral GATT server, central GATT client, scan results) can each
    // land on their own binder thread, so these shared maps need to tolerate concurrent
    // access; synchronizedMap is a coarse but adequate mitigation for M1's single-active-
    // connection scope. A later milestone should route everything through one serial
    // dispatcher instead of relying on this.
    private val discoveredByAddress = java.util.Collections.synchronizedMap(mutableMapOf<String, NearbyPeer>())
    private val addressBySessionId = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    private val connections = java.util.Collections.synchronizedMap(mutableMapOf<String, Connection>())
    private var activeAddress: String? = null
    private var browsing = false

    /** The user-facing "make me discoverable" toggle. Off by default and never
     *  persisted across app restarts — see the safety-by-design notes in the plan. */
    fun setBrowsing(enabled: Boolean) {
        if (enabled == browsing) return
        browsing = enabled
        if (enabled) {
            discoveredByAddress.clear()
            addressBySessionId.clear()
            peripheral.start(Random.nextBytes(GattProfile.MAX_ADVERTISED_SESSION_ID_BYTES))
            central.startScanning()
            _state.value = ChatUiState.Browsing(emptyList())
        } else {
            central.stopScanning()
            peripheral.stop()
            endActiveConnection("stopped browsing")
            _state.value = ChatUiState.Idle
        }
    }

    /** Picks a random currently-visible peer and starts a chat with them. */
    fun requestRandomChat() {
        if (_state.value !is ChatUiState.Browsing) return
        val picked = matcher.pickRandomPeer(discoveredByAddress.values.toList()) ?: return
        if (!cooldown.canRequest(picked.sessionId)) return
        val address = addressBySessionId[picked.sessionId] ?: return

        cooldown.recordRequest(picked.sessionId)
        activeAddress = address
        _state.value = ChatUiState.Connecting(picked)
        central.stopScanning() // one conversation at a time
        peripheral.stop()
        central.connect(address)
    }

    fun sendMessage(text: String) {
        val address = activeAddress ?: return
        val connection = connections[address] ?: return
        if (connection.step != HandshakeStep.DONE) return

        val ciphertext = connection.session.encryptMessage(text)
        if (connection.isOutbound) central.sendFrame(address, ciphertext) else peripheral.sendFrame(address, ciphertext)

        val current = _state.value
        if (current is ChatUiState.Chatting) {
            _state.value = current.copy(messages = current.messages + ChatMessage(fromMe = true, text = text, atMillis = System.currentTimeMillis()))
        }
    }

    fun endActiveConnection(reason: String) {
        val address = activeAddress
        if (address != null) {
            val connection = connections[address]
            if (connection?.isOutbound == true) central.disconnect(address) else peripheral.disconnectDevice(address)
            connections.remove(address)
        }
        activeAddress = null
        _state.value = ChatUiState.Ended(reason)
        if (browsing) {
            peripheral.start(Random.nextBytes(GattProfile.MAX_ADVERTISED_SESSION_ID_BYTES))
            central.startScanning()
            _state.value = ChatUiState.Browsing(discoveredByAddress.values.toList())
        }
    }

    fun blockActivePeer() {
        val current = _state.value
        if (current is ChatUiState.Chatting) blockList.block(current.remotePeerId)
        endActiveConnection("blocked")
    }

    fun reportActivePeer(reason: String) {
        val current = _state.value
        if (current is ChatUiState.Chatting) reportFlow.report(current.remotePeerId, reason)
        endActiveConnection("reported")
    }

    // ---- BlePeripheralServer.Listener (inbound / "someone connected to us") ----

    override fun onCentralConnected(deviceAddress: String) {
        if (activeAddress != null) {
            peripheral.disconnectDevice(deviceAddress) // already busy with another chat
            return
        }
        activeAddress = deviceAddress
        connections[deviceAddress] = Connection(ChatSession(isInitiator = false, identity = identity), isOutbound = false)
        _state.value = ChatUiState.Handshaking
    }

    override fun onCentralDisconnected(deviceAddress: String) {
        if (deviceAddress == activeAddress) endActiveConnection("peer disconnected")
    }

    override fun onFrameReceived(deviceAddress: String, frame: ByteArray) = handleFrame(deviceAddress, frame)

    // ---- BleCentralClient.Listener (outbound / "we connected to someone") ----

    override fun onPeerDiscovered(deviceAddress: String, sessionId: ByteArray, rssi: Int) {
        val sessionIdHex = sessionId.joinToString("") { "%02x".format(it) }
        addressBySessionId[sessionIdHex] = deviceAddress
        discoveredByAddress[deviceAddress] = NearbyPeer(sessionId = sessionIdHex, rssi = rssi, lastSeenAtMillis = System.currentTimeMillis())
        if (_state.value is ChatUiState.Browsing) {
            _state.value = ChatUiState.Browsing(discoveredByAddress.values.toList())
        }
    }

    override fun onConnected(deviceAddress: String) {
        val connection = Connection(ChatSession(isInitiator = true, identity = identity), isOutbound = true)
        connections[deviceAddress] = connection
        _state.value = ChatUiState.Handshaking
        central.sendFrame(deviceAddress, connection.session.startHandshake())
    }

    override fun onDisconnected(deviceAddress: String) {
        if (deviceAddress == activeAddress) endActiveConnection("peer disconnected")
    }

    // ---- shared handshake/chat frame routing ----

    private fun handleFrame(deviceAddress: String, frame: ByteArray) {
        val connection = connections[deviceAddress] ?: return
        when (connection.step) {
            HandshakeStep.EXPECT_MESSAGE_1 -> {
                val message2 = connection.session.respondToHandshake(frame)
                peripheral.sendFrame(deviceAddress, message2)
                connection.step = HandshakeStep.EXPECT_MESSAGE_3
            }
            HandshakeStep.EXPECT_MESSAGE_2 -> {
                val message3 = connection.session.completeHandshake(frame)
                central.sendFrame(deviceAddress, message3)
                connection.step = HandshakeStep.DONE
                onHandshakeComplete(deviceAddress, connection)
            }
            HandshakeStep.EXPECT_MESSAGE_3 -> {
                connection.session.finishHandshake(frame)
                connection.step = HandshakeStep.DONE
                onHandshakeComplete(deviceAddress, connection)
            }
            HandshakeStep.DONE -> {
                val text = connection.session.decryptMessage(frame)
                _state.update { current ->
                    if (current is ChatUiState.Chatting) {
                        current.copy(messages = current.messages + ChatMessage(fromMe = false, text = text, atMillis = System.currentTimeMillis()))
                    } else current
                }
            }
        }
    }

    private fun onHandshakeComplete(deviceAddress: String, connection: Connection) {
        val remotePeerId = connection.session.remotePeerId()
        if (blockList.isBlocked(remotePeerId)) {
            // Blocking only becomes possible to check *after* the handshake reveals the
            // peer's long-term identity — discovery only ever exposes rotating session
            // ids. Silently drop rather than surfacing any chat UI.
            if (connection.isOutbound) central.disconnect(deviceAddress) else peripheral.disconnectDevice(deviceAddress)
            connections.remove(deviceAddress)
            endActiveConnection("blocked peer")
            return
        }
        _state.value = ChatUiState.Chatting(remotePeerId = remotePeerId, messages = emptyList())
    }
}
