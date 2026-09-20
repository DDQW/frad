package me.woelki.friendradar.wideradius

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import me.woelki.friendradar.p2pgo.node.Config as GoConfig
import me.woelki.friendradar.p2pgo.node.Host as GoHost
import me.woelki.friendradar.p2pgo.node.IncomingStreamListener as GoIncomingStreamListener
import me.woelki.friendradar.p2pgo.node.Node as GoNode
import me.woelki.friendradar.p2pgo.node.PeerFoundListener as GoPeerFoundListener

/**
 * Wraps the gomobile-generated go-libp2p binding (`p2p-go/node`, built into
 * `p2p-go/build/p2pgo.aar` by the `gomobileBind` Gradle task — see
 * app/build.gradle.kts) with the suspend/Result idiom this codebase already
 * uses for [me.woelki.friendradar.wifidirect.WifiDirectTransferManager].
 *
 * The exact shape of the `me.woelki.friendradar.p2pgo.node.*` classes
 * referenced below follows gomobile bind's documented Java-binding
 * conventions (an exported Go func becomes a lowerCamelCase static method on
 * a class named after the Title-cased Go package — `node.NewHost` ->
 * `Node.newHost`; exported struct fields become `getX()`/`setX()`; a Go
 * `(T, error)` return becomes a thrown exception on error) — see
 * `golang.org/x/mobile/bind`'s `genjava.go`. This has **not** been verified
 * against an actual `gomobile bind` run in this pass (no Android NDK was
 * available); running `./gradlew gomobileBind` followed by a real compile is
 * the verification step — see p2p-go/README.md.
 */
class WideRangeNode(@Suppress("UNUSED_PARAMETER") context: Context) {
    companion object {
        const val isSupported: Boolean = true
    }

    @Volatile private var goHost: GoHost? = null

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
            _incomingStreams.tryEmit(protocolId to WideRangeByteStream(stream))
        }
    }

    suspend fun start(config: WideRangeConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val goConfig = GoConfig().apply {
                listenPort = config.listenPort
                identitySeed = config.identitySeed
                bootstrapPeers = config.bootstrapPeers.joinToString("\n")
            }
            val host = GoNode.newHost(goConfig, goPeerFoundListener, goIncomingStreamListener)
            host.start()
            goHost = host
            host.startAdvertising(config.rendezvousTopic)
            host.findPeersOnce(config.rendezvousTopic)
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

    private fun requireHost(): GoHost = goHost ?: error("WideRangeNode.start() must succeed before use")
}
