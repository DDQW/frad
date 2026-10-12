package app.frad.chat.crypto.noise

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Checks the hand-written Noise_XX_25519_ChaChaPoly_SHA256 against the published test vector
 * from the Cacophony project (haskell-cryptography/cacophony, vectors/cacophony.txt) - the same
 * vectors other Noise implementations are checked against. With the vector's fixed keys, every
 * handshake and transport message has to come out byte for byte identical.
 */
class NoiseVectorsTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val prologue = hex("4a6f686e2047616c74")
    private val initStatic = hex("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1")
    private val initEphemeral = hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")
    private val respStatic = hex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893")
    private val respEphemeral = hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")
    private val handshakeHash = hex("c8e5f64e846193be2a834104c2a009868d6c9f3bd3c186299888b488b2f1f58e")

    /** (payload, ciphertext); even indices go initiator -> responder. */
    private val messages = listOf(
        "4c756477696720766f6e204d69736573" to
            "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573",
        "4d757272617920526f746862617264" to
            "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f14480884381cbad1f276e038c48378ffce2b65285e08d6b68aaa3629a5a8639392490e5b9bd5269c2f1e4f488ed8831161f19b7815528f8982ffe09be9b5c412f8a0db50f8814c7194e83f23dbd8d162c9326ad",
        "462e20412e20486179656b" to
            "c7195ffacac1307ff99046f219750fc47693e23c3cb08b89c2af808b444850a80ae475b9df0f169ae80a89be0865b57f58c9fea0d4ec82a286427402f113e4b6ae769a1d95941d49b25030",
        "4361726c204d656e676572" to "96763ed773f8e47bb3712f0e29b3060ffc956ffc146cee53d5e1df",
        "4a65616e2d426170746973746520536179" to "3e40f15f6f3a46ae446b253bf8b1d9ffb6ed9b174d272328ff91a7e2e5c79c07f5",
        "457567656e2042f6686d20766f6e2042617765726b" to "eb3f3515110702e047a6c9da4478b6ead94873c11c0f2d710ddb3f09fce024b3a58502ae3f",
    ).map { (payload, ciphertext) -> hex(payload) to hex(ciphertext) }

    private fun keyPair(privateKey: ByteArray) = privateKey to Primitives.publicKeyOf(privateKey)

    @Test
    fun `matches the Cacophony vector for Noise_XX_25519_ChaChaPoly_SHA256`() {
        val initiator = NoiseXXHandshake(true, initStatic, Primitives.publicKeyOf(initStatic), prologue) { keyPair(initEphemeral) }
        val responder = NoiseXXHandshake(false, respStatic, Primitives.publicKeyOf(respStatic), prologue) { keyPair(respEphemeral) }

        val m1 = initiator.writeMessage1(messages[0].first)
        assertArrayEquals(messages[0].second, m1)
        responder.readMessage1(m1)
        assertArrayEquals(messages[0].first, responder.lastReceivedPayload)

        val m2 = responder.writeMessage2(messages[1].first)
        assertArrayEquals(messages[1].second, m2)
        assertArrayEquals(Primitives.publicKeyOf(respStatic), initiator.readMessage2(m2))
        assertArrayEquals(messages[1].first, initiator.lastReceivedPayload)

        val m3 = initiator.writeMessage3(messages[2].first)
        assertArrayEquals(messages[2].second, m3)
        assertArrayEquals(Primitives.publicKeyOf(initStatic), responder.readMessage3(m3))
        assertArrayEquals(messages[2].first, responder.lastReceivedPayload)

        assertArrayEquals(handshakeHash, initiator.handshakeHash())
        assertArrayEquals(handshakeHash, responder.handshakeHash())

        val initiatorKeys = initiator.split()
        val responderKeys = responder.split()
        for (i in 3 until messages.size) {
            val (payload, ciphertext) = messages[i]
            val (sender, receiver) = if (i % 2 == 0) initiatorKeys to responderKeys else responderKeys to initiatorKeys
            assertArrayEquals("transport message $i", ciphertext, sender.encrypt(payload))
            assertArrayEquals(payload, receiver.decrypt(ciphertext))
        }
    }
}
