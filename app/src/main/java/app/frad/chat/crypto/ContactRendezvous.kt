package app.frad.chat.crypto

import java.nio.ByteBuffer
import app.frad.chat.crypto.noise.Primitives

/**
 * How two saved contacts find each other again over the internet without telling anyone else
 * they're looking: both phones know both long-term keys (from their first chat), so both can
 * compute the same Diffie-Hellman secret - and from it, a DHT rendezvous topic that changes every
 * day. Each advertises itself under that topic and looks it up. To everyone else the topic is a
 * random-looking string, different for every pair of people and every day, so it can't be used to
 * follow someone or to tell who knows whom.
 */
object ContactRendezvous {
    private val SECRET_LABEL = Primitives.sha256("FRAD contact rendezvous v1".toByteArray())
    const val TOPIC_PREFIX = "frad/contact/v1/"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** The same on both phones: [ourPrivateKey] with [theirPublicKey] equals theirs with ours. */
    fun secret(ourPrivateKey: ByteArray, theirPublicKey: ByteArray): ByteArray =
        Primitives.hkdf(SECRET_LABEL, Primitives.dh(ourPrivateKey, theirPublicKey), 2)[0]

    /** The topic for UTC day number [day] (see [dayOf]). */
    fun topic(secret: ByteArray, day: Long): String {
        val derived = Primitives.hkdf(secret, ByteBuffer.allocate(8).putLong(day).array(), 2)[0]
        return TOPIC_PREFIX + derived.copyOf(16).joinToString("") { "%02x".format(it) }
    }

    fun dayOf(millis: Long): Long = Math.floorDiv(millis, DAY_MILLIS)

    /** Today's and - around midnight, when clocks disagree - the neighbouring day's topic. */
    fun topicsAround(secret: ByteArray, millis: Long): List<String> {
        val day = dayOf(millis)
        val intoDay = millis - day * DAY_MILLIS
        val neighbour = when {
            intoDay < DAY_MILLIS / 24 -> day - 1
            intoDay > DAY_MILLIS - DAY_MILLIS / 24 -> day + 1
            else -> null
        }
        return listOfNotNull(topic(secret, day), neighbour?.let { topic(secret, it) })
    }
}
