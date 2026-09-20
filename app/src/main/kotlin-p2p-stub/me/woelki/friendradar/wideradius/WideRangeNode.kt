package me.woelki.friendradar.wideradius

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Compiled in place of the real gomobile-backed implementation whenever
 * `p2p-go/build/p2pgo.aar` hasn't been built (see the comment above the
 * `android {}` block in app/build.gradle.kts). Every call fails via
 * [isSupported], so [me.woelki.friendradar.wideradius.WideRangeChatController]
 * degrades the same way [me.woelki.friendradar.ble.BleChatController] already
 * does when [me.woelki.friendradar.wifidirect.WifiDirectTransferManager] is
 * unavailable below API 29.
 */
class WideRangeNode(@Suppress("UNUSED_PARAMETER") context: Context) {
    companion object {
        const val isSupported: Boolean = false
    }

    val localPeerId: String = ""

    suspend fun start(config: WideRangeConfig): Result<Unit> = unsupported()
    suspend fun stop(): Result<Unit> = Result.success(Unit)
    suspend fun startAdvertising(rendezvousTopic: String): Result<Unit> = unsupported()
    suspend fun findPeersOnce(rendezvousTopic: String): Result<Unit> = unsupported()
    suspend fun openStream(peerId: String, protocolId: String): Result<WideRangeByteStream> = unsupported()

    fun discoveredPeers(): Flow<String> = emptyFlow()
    fun incomingStreams(): Flow<Pair<String, WideRangeByteStream>> = emptyFlow()

    private fun <T> unsupported(): Result<T> =
        Result.failure(UnsupportedOperationException("Wide-range networking isn't built into this app - see p2p-go/README.md"))
}

/** See [WideRangeNode] — never actually constructed by the stub build, but the
 *  type must exist so [me.woelki.friendradar.wideradius.WideRangeChatController]
 *  (compiled against either variant) type-checks. */
class WideRangeByteStream internal constructor() {
    suspend fun write(bytes: ByteArray): Result<Unit> = unreachable()
    suspend fun readExactly(length: Int): Result<ByteArray> = unreachable()
    fun close() = Unit

    private fun <T> unreachable(): Result<T> =
        Result.failure(IllegalStateException("unreachable: the stub build never constructs a WideRangeByteStream"))
}
