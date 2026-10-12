package app.frad.chat.crypto

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Hashcash-style proof of work a wide-range chat request must open with (M5's Sybil/spam
 * resistance). Over the internet anyone can mint a fresh libp2p identity per request for free,
 * so the per-target [app.frad.chat.safety.Cooldown] alone can't stop someone from spamming every
 * person in an area with chat requests; this makes each request cost the sender a fraction of a
 * second of CPU while costing the receiver one hash to check.
 *
 * The token is bound to both libp2p peer ids (the receiver's, so it can't be reused against
 * someone else, and the sender's, which libp2p's secure channel authenticates, so it can't be
 * borrowed) and to a timestamp, so it expires. BLE needs none of this - being physically nearby
 * already limits who can ask.
 */
object ProofOfWork {
    /** ~260k SHA-256 evaluations on average - a fraction of a second on a phone. */
    const val DIFFICULTY_BITS = 18
    const val MAX_CLOCK_SKEW_SECONDS = 10 * 60L

    /** Wire size of a token: 8-byte timestamp (seconds) + 8-byte nonce, big-endian. */
    const val TOKEN_BYTES = 16

    fun solve(responderId: String, initiatorId: String, nowSeconds: Long, difficultyBits: Int = DIFFICULTY_BITS): ByteArray {
        val challenge = challenge(responderId, initiatorId, nowSeconds)
        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = 0L
        while (leadingZeroBits(hash(digest, challenge, nonce)) < difficultyBits) nonce++
        return ByteBuffer.allocate(TOKEN_BYTES).putLong(nowSeconds).putLong(nonce).array()
    }

    fun verify(
        token: ByteArray,
        responderId: String,
        initiatorId: String,
        nowSeconds: Long,
        difficultyBits: Int = DIFFICULTY_BITS,
    ): Boolean {
        if (token.size != TOKEN_BYTES) return false
        val buffer = ByteBuffer.wrap(token)
        val timestamp = buffer.long
        val nonce = buffer.long
        if (abs(nowSeconds - timestamp) > MAX_CLOCK_SKEW_SECONDS) return false
        val digest = MessageDigest.getInstance("SHA-256")
        return leadingZeroBits(hash(digest, challenge(responderId, initiatorId, timestamp), nonce)) >= difficultyBits
    }

    private fun challenge(responderId: String, initiatorId: String, timestampSeconds: Long): ByteArray =
        "frad-pow-v1|$responderId|$initiatorId|$timestampSeconds".toByteArray(StandardCharsets.UTF_8)

    private fun hash(digest: MessageDigest, challenge: ByteArray, nonce: Long): ByteArray {
        digest.update(challenge)
        digest.update(ByteBuffer.allocate(8).putLong(nonce).array())
        return digest.digest()
    }

    private fun leadingZeroBits(hash: ByteArray): Int {
        var bits = 0
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                bits += 8
                continue
            }
            return bits + Integer.numberOfLeadingZeros(value) - 24
        }
        return bits
    }
}
