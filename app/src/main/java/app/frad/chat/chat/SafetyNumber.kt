package app.frad.chat.chat

import java.security.MessageDigest

/**
 * A 60-digit number derived from both people's long-term public keys, identical on both phones
 * for the same pair. Comparing it out of band (reading it out in person, or over another channel)
 * proves nobody sat in the middle of the first contact - the Noise handshake authenticates keys,
 * but a stranger's key is only as trustworthy as that first meeting. Same idea as Signal's
 * safety numbers.
 */
object SafetyNumber {
    fun of(ourKey: ByteArray, theirKey: ByteArray): String {
        // Order-independent, so both sides compute the same number.
        val (first, second) = if (compare(ourKey, theirKey) <= 0) ourKey to theirKey else theirKey to ourKey
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update("frad-safety-number-v1".toByteArray())
        digest.update(first)
        digest.update(second)
        val hash = digest.digest()
        // 12 groups of 5 digits, each from 5 bytes of the hash (mod 100000).
        return (0 until 12).joinToString(" ") { group ->
            var value = 0L
            for (i in 0 until 5) value = (value shl 8) or (hash[group * 5 + i].toLong() and 0xFF)
            (value % 100_000).toString().padStart(5, '0')
        }
    }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
