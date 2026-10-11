package app.frad.chat.wideradius

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import app.frad.chat.p2pgo.node.Config as GoConfig
import app.frad.chat.p2pgo.node.Host as GoHost
import app.frad.chat.p2pgo.node.IncomingStreamListener as GoIncomingStreamListener
import app.frad.chat.p2pgo.node.Node as GoNode
import app.frad.chat.p2pgo.node.PeerFoundListener as GoPeerFoundListener

/**
 * Wraps the gomobile-generated go-libp2p binding (`p2p-go/node`, built into
 * `p2p-go/build/p2pgo.aar` by the `gomobileBind` Gradle task — see
 * app/build.gradle.kts) with the suspend/Result idiom this codebase already
 * uses for [app.frad.chat.wifidirect.WifiDirectTransferManager].
 *
 * The `app.frad.chat.p2pgo.node.*` classes follow gomobile bind's Java-binding
 * conventions (an exported Go func becomes a lowerCamelCase static method on
 * a class named after the Title-cased Go package — `node.NewHost` ->
 * `Node.newHost`; exported struct fields become `getX()`/`setX()`; a Go
 * `(T, error)` return becomes a thrown exception on error). Go's `int` crosses
 * as Java `long`, hence the `toLong()` conversions here and in
 * [WideRangeByteStream]. The CI job `android-real-p2p` builds this file against
 * a real `gomobile bind` output on every push.
 */
class WideRangeNode(context: Context) {
    companion object {
        const val isSupported: Boolean = true

        /**
         * The entries of [entries] (multiaddrs ending in `/p2p/<peerid>`) that every FRAD
         * participant would accept from the internet, de-duplicated - the same check the Go
         * side applies to every node list it receives (`node.ValidateNodeList`).
         */
        fun validateNodeList(entries: List<String>): List<String> =
            GoNode.validateNodeList(entries.joinToString("\n")).nonBlankLines()
    }

    private val appContext = context.applicationContext

    @Volatile private var goHost: GoHost? = null

    /** What [setKnownNodes] last set; guarded by [nodesLock] together with handing it to [goHost]. */
    private var knownNodes: List<String> = emptyList()
    private val nodesLock = Any()

    private val _discoveredPeers = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val _incomingStreams = MutableSharedFlow<Pair<String, WideRangeByteStream>>(extraBufferCapacity = 16)

    fun discoveredPeers(): Flow<String> = _discoveredPeers.asSharedFlow()
    fun incomingStreams(): Flow<Pair<String, WideRangeByteStream>> = _incomingStreams.asSharedFlow()

    val localPeerId: String get() = goHost?.localPeerId() ?: ""

    private val goPeerFoundListener = object : GoPeerFoundListener {
        override fun onPeerFound(peerId: String) {
            _discoveredPeers.tryEmit(peerId)
        }

        override fun onDiscoveryError(message: String) {
            // Advertise/FindPeers run on the Go side's own background loop and have
            // no single Kotlin caller waiting on this particular failure; surfacing
            // it as a peer-discovery gap (an empty result) is the best this can do.
        }
    }

    private val goIncomingStreamListener = object : GoIncomingStreamListener {
        override fun onIncomingStream(protocolId: String, peerId: String, streamHandle: String) {
            val host = goHost ?: return
            val stream = runCatching { host.acceptStream(streamHandle) }.getOrNull() ?: return
            val wrapped = WideRangeByteStream(stream)
            // Buffer full (a burst of incoming streams): close rather than leak the stream.
            if (!_incomingStreams.tryEmit(protocolId to wrapped)) wrapped.close()
        }
    }

    /** Starts the libp2p host and DHT bootstrap only — callers drive
     *  [startAdvertising]/[findPeersOnce] themselves (see
     *  [app.frad.chat.wideradius.WideRangeChatController], which
     *  re-runs [findPeersOnce] on an interval the way BLE scanning is
     *  continuous, since a single DHT lookup is a one-shot round). */
    suspend fun start(config: WideRangeConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val goConfig = GoConfig().apply {
                listenPort = config.listenPort.toLong()
                identitySeed = config.identitySeed
                bootstrapPeers = config.bootstrapPeers.joinToString("\n")
                relayOnly = config.relayOnly
                interfaceAddrs = interfaceAddresses(appContext).joinToString("\n")
            }
            val host = GoNode.newHost(goConfig, goPeerFoundListener, goIncomingStreamListener)
            host.start()
            synchronized(nodesLock) {
                host.setKnownNodes(knownNodes.joinToString("\n"))
                goHost = host
            }
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        val host = goHost
        goHost = null
        runCatching { host?.stop() ?: Unit }
    }

    suspend fun startAdvertising(rendezvousTopic: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { requireHost().startAdvertising(rendezvousTopic) }
    }

    suspend fun findPeersOnce(rendezvousTopic: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { requireHost().findPeersOnce(rendezvousTopic) }
    }

    suspend fun openStream(peerId: String, protocolId: String): Result<WideRangeByteStream> = withContext(Dispatchers.IO) {
        runCatching { WideRangeByteStream(requireHost().openStream(peerId, protocolId)) }
    }

    /**
     * Sets the server list this device hands to any peer that asks for it (the node-list
     * protocol, `/frad/nodes/1.0.0`) - typically everything saved in Profile, so a server
     * address keeps spreading even once its original source is gone. Can be called before
     * [start]; the Go side validates and caps the list, and shares the configured bootstrap
     * nodes as well.
     */
    fun setKnownNodes(nodes: List<String>) {
        synchronized(nodesLock) {
            knownNodes = nodes.toList()
            goHost?.setKnownNodes(knownNodes.joinToString("\n"))
        }
    }

    /**
     * Swaps node lists with every configured bootstrap node and returns the servers they know
     * about (validated, de-duplicated, diversity-capped). These are candidates, not verified
     * servers. Fails only if no bootstrap node answered.
     */
    suspend fun exchangeNodesWithBootstrapPeers(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { requireHost().exchangeNodesWithBootstrapPeers().nonBlankLines() }
    }

    /** Like [exchangeNodesWithBootstrapPeers], with one specific peer - e.g. a chat partner. */
    suspend fun exchangeNodesWithPeer(peerId: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching { requireHost().exchangeNodesWithPeer(peerId).nonBlankLines() }
    }

    /**
     * The (validated) node lists other peers have pushed to this device since the last call -
     * unverified candidates, since anyone can push a well-formed list. Each call hands them over
     * once.
     */
    fun takeLearnedNodes(): List<String> = goHost?.takeLearnedNodes()?.nonBlankLines() ?: emptyList()

    private fun requireHost(): GoHost = goHost ?: error("WideRangeNode.start() must succeed before use")
}

private fun String.nonBlankLines(): List<String> = lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * This device's IP addresses, for the Go side's `Config.InterfaceAddrs`: Go can't enumerate
 * network interfaces on Android 11+ (see p2p-go/node/interface_addrs.go), so without these the
 * host would only ever advertise loopback addresses. The active network's addresses come from
 * [ConnectivityManager] (which needs ACCESS_NETWORK_STATE); failing that, from
 * [NetworkInterface], which Android still serves to apps. Go drops the ones nobody else could
 * use (loopback, link-local, ...); empty just means libp2p advertises what it found itself.
 */
private fun interfaceAddresses(context: Context): List<String> {
    val addresses = runCatching { activeNetworkAddresses(context) }.getOrDefault(emptyList())
        .ifEmpty { runCatching { allInterfaceAddresses() }.getOrDefault(emptyList()) }
    return addresses.mapNotNull { it.hostAddress }.distinct()
}

private fun activeNetworkAddresses(context: Context): List<InetAddress> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val network = connectivity.activeNetwork ?: return emptyList()
    return connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
}

private fun allInterfaceAddresses(): List<InetAddress> =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
