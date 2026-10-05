package app.frad.chat.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import app.frad.chat.chat.closingOnCancel
import app.frad.chat.crypto.TransferCipher
import app.frad.chat.crypto.readChunked
import app.frad.chat.crypto.writeChunked

/** Like runCatching, but lets a cancellation through - a cancelled transfer must stop the
 *  caller too, not come back as an ordinary failure it might retry or report. */
private inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/** The exact Wi-Fi network a receiver needs to join to reach the sender's group. */
data class WfdCredentials(val networkName: String, val passphrase: String)

/**
 * Drives a one-off Wi-Fi Direct group + TCP socket to move a single file between two
 * phones already chatting over BLE (M3) — see the milestone plan for why a *new*, fixed
 * network-name/passphrase group per transfer (API 29+) is used instead of Wi-Fi Direct's
 * broadcast peer discovery: it's the only way to guarantee the socket ends up talking to
 * the exact person already in this chat, not merely some other nearby Wi-Fi Direct device.
 *
 * [BleChatController][app.frad.chat.ble.BleChatController] is the only caller: it
 * decides *when* to start a transfer and carries the resulting [WfdCredentials] to the peer
 * over the existing encrypted BLE channel. This class never touches BLE.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class WifiDirectTransferManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel = manager.initialize(appContext, appContext.mainLooper, null)

    /** Sender side: creates a fresh group, waits for the receiver to connect, then streams
     *  [fileBytes] as authenticated chunks keyed by [transferKey]. [onCredentialsReady] is
     *  called once the group's network name/passphrase are known, so the caller can relay
     *  them to the peer over BLE before this function blocks waiting for the socket.
     *  Cancelling the calling coroutine aborts the transfer (the sockets are closed). */
    suspend fun hostAndSendFile(
        fileBytes: ByteArray,
        transferKey: ByteArray,
        onProgress: (Long) -> Unit = {},
        onCredentialsReady: suspend (WfdCredentials) -> Unit,
    ): Result<Unit> = runCatchingNonCancellation {
        createGroup()
        try {
            val group = requestGroupInfo()
            onCredentialsReady(WfdCredentials(networkName = group.networkName, passphrase = group.passphrase))

            // Listen on the group's own address only, not on every network the phone is in: on the
            // ordinary Wi-Fi, anyone could otherwise connect first and take the transfer's one slot.
            val groupAddress = runCatching { requestConnectionInfo().groupOwnerAddress }.getOrNull()
            withContext(Dispatchers.IO) {
                (if (groupAddress != null) ServerSocket(PORT, 1, groupAddress) else ServerSocket(PORT)).use { server ->
                    server.soTimeout = CONNECT_TIMEOUT_MILLIS
                    closingOnCancel(server::close) { acceptFromGroup(server, groupAddress) }.use { socket ->
                        val out = socket.getOutputStream()
                        closingOnCancel(socket::close) {
                            writeChunked(fileBytes, TransferCipher(transferKey), CHUNK_SIZE, onProgress) { out.write(it) }
                            out.flush()
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { removeGroup() } }
        }
    }

    /** Receiver side: joins the group described by [credentials], connects to the group
     *  owner, and reads back [expectedSize] plaintext bytes of authenticated chunks.
     *  Cancelling the calling coroutine aborts the transfer (the socket is closed). */
    suspend fun joinAndReceiveFile(
        credentials: WfdCredentials,
        transferKey: ByteArray,
        expectedSize: Long,
        onProgress: (Long) -> Unit = {},
    ): Result<ByteArray> = runCatchingNonCancellation {
        val receiver = ConnectionInfoReceiver(appContext)
        try {
            receiver.register()
            val config = WifiP2pConfig.Builder()
                .setNetworkName(credentials.networkName)
                .setPassphrase(credentials.passphrase)
                .build()
            connect(config)
            val info = withTimeout(CONNECT_TIMEOUT_MILLIS.toLong()) { receiver.awaitConnection() }
            val groupOwnerAddress = info.groupOwnerAddress ?: error("No group owner address after connecting")

            withContext(Dispatchers.IO) {
                connectSocketWithRetry(groupOwnerAddress).use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    closingOnCancel(socket::close) {
                        readChunked(expectedSize, TransferCipher(transferKey), MAX_CHUNK_ON_WIRE, onProgress) { length ->
                            ByteArray(length).also(input::readFully)
                        }
                    }
                }
            }
        } finally {
            receiver.unregister()
            withContext(NonCancellable) { runCatching { removeGroup() } }
        }
    }

    /** The first connection from inside the group's subnet; anything else is dropped. The data
     *  is encrypted for the peer anyway - this only keeps others from occupying the slot. */
    private fun acceptFromGroup(server: ServerSocket, groupAddress: InetAddress?): Socket {
        while (true) {
            val socket = server.accept()
            if (groupAddress == null || sameSubnet(socket.inetAddress, groupAddress)) return socket
            runCatching { socket.close() }
        }
    }

    private fun sameSubnet(a: InetAddress?, b: InetAddress): Boolean {
        val x = a?.address ?: return false
        val y = b.address
        return x.size == 4 && y.size == 4 && x[0] == y[0] && x[1] == y[1] && x[2] == y[2]
    }

    private suspend fun requestConnectionInfo(): WifiP2pInfo = suspendCancellableCoroutine { cont ->
        manager.requestConnectionInfo(channel) { info ->
            if (info != null) cont.resume(info) else cont.resumeWithException(IllegalStateException("No connection info"))
        }
    }

    private fun connectSocketWithRetry(address: InetAddress): Socket {
        var lastError: Exception? = null
        repeat(SOCKET_CONNECT_ATTEMPTS) {
            try {
                return Socket().apply { connect(InetSocketAddress(address, PORT), SOCKET_CONNECT_TIMEOUT_MILLIS) }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(SOCKET_RETRY_DELAY_MILLIS)
            }
        }
        throw lastError ?: IllegalStateException("Could not connect to group owner")
    }

    private suspend fun createGroup(): Unit = suspendCancellableCoroutine { cont ->
        manager.createGroup(channel, actionListener(cont))
    }

    private suspend fun removeGroup(): Unit = suspendCancellableCoroutine { cont ->
        manager.removeGroup(channel, actionListener(cont))
    }

    private suspend fun connect(config: WifiP2pConfig): Unit = suspendCancellableCoroutine { cont ->
        manager.connect(channel, config, actionListener(cont))
    }

    private suspend fun requestGroupInfo(): WifiP2pGroup = suspendCancellableCoroutine { cont ->
        manager.requestGroupInfo(channel) { group ->
            if (group != null) cont.resume(group) else cont.resumeWithException(IllegalStateException("No active Wi-Fi Direct group"))
        }
    }

    private fun actionListener(cont: Continuation<Unit>) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = cont.resume(Unit)
        override fun onFailure(reason: Int) = cont.resumeWithException(IllegalStateException("Wi-Fi Direct action failed: reason=$reason"))
    }

    /** Bridges the async `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast to a single suspend call. */
    private class ConnectionInfoReceiver(private val context: Context) {
        private var continuation: Continuation<WifiP2pInfo>? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                @Suppress("DEPRECATION")
                val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                if (info?.groupFormed == true) {
                    continuation?.let { it.resume(info); continuation = null }
                }
            }
        }

        fun register() {
            context.registerReceiver(receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        }

        fun unregister() {
            runCatching { context.unregisterReceiver(receiver) }
        }

        suspend fun awaitConnection(): WifiP2pInfo = suspendCancellableCoroutine { cont -> continuation = cont }
    }

    private companion object {
        const val PORT = 27183
        const val CHUNK_SIZE = 64 * 1024
        const val MAX_CHUNK_ON_WIRE = CHUNK_SIZE + 64 // plaintext chunk + AEAD tag/overhead headroom
        const val CONNECT_TIMEOUT_MILLIS = 30_000
        const val SOCKET_CONNECT_TIMEOUT_MILLIS = 5_000
        const val SOCKET_CONNECT_ATTEMPTS = 5
        const val SOCKET_RETRY_DELAY_MILLIS = 1_000L
    }
}
