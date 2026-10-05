package app.frad.chat.crypto

import java.util.Base64
import app.frad.chat.crypto.noise.Primitives
import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {

    private fun randomIdentity(): Identity {
        val (priv, pub) = Primitives.generateKeyPair()
        return Identity.fromRawKeyPair(priv, pub)
    }

    private class Handshaked(val alice: ChatSession, val bob: ChatSession, val alicePub: ByteArray, val bobPub: ByteArray)

    /** Runs a full Noise_XX handshake between two independent [ChatSession]s,
     *  exactly as the BLE transport would relay the three messages. */
    private fun handshake(): Handshaked {
        val aliceIdentity = randomIdentity()
        val bobIdentity = randomIdentity()
        val alice = ChatSession(isInitiator = true, identity = aliceIdentity)
        val bob = ChatSession(isInitiator = false, identity = bobIdentity)

        val message1 = alice.startHandshake()
        val message2 = bob.respondToHandshake(message1)
        val message3 = alice.completeHandshake(message2)
        bob.finishHandshake(message3)

        return Handshaked(alice, bob, aliceIdentity.publicKey, bobIdentity.publicKey)
    }

    @Test
    fun `handshake completes and each side learns the other's peer id, not their own`() {
        val h = handshake()
        assertTrue(h.alice.isReady)
        assertTrue(h.bob.isReady)

        val expectedBobPeerId = Base64.getUrlEncoder().withoutPadding().encodeToString(Primitives.sha256(h.bobPub))
        val expectedAlicePeerId = Base64.getUrlEncoder().withoutPadding().encodeToString(Primitives.sha256(h.alicePub))

        assertEquals(expectedBobPeerId, h.alice.remotePeerId())
        assertEquals(expectedAlicePeerId, h.bob.remotePeerId())
        assertNotEquals(h.alice.remotePeerId(), h.bob.remotePeerId())
    }

    @Test
    fun `messages round trip in both directions`() {
        val h = handshake()

        val toBob = h.alice.encryptMessage("hey, anyone around?")
        assertEquals("hey, anyone around?", h.bob.decryptMessage(toBob))

        val toAlice = h.bob.encryptMessage("yeah, small world")
        assertEquals("yeah, small world", h.alice.decryptMessage(toAlice))

        // A further message in the same direction should still work (nonce counter advances correctly).
        val second = h.alice.encryptMessage("nonce should have advanced")
        assertEquals("nonce should have advanced", h.bob.decryptMessage(second))
    }

    @Test
    fun `tampered ciphertext fails authentication instead of decrypting garbage`() {
        val h = handshake()
        val ciphertext = h.alice.encryptMessage("sensitive")
        ciphertext[ciphertext.size - 1] = (ciphertext[ciphertext.size - 1] + 1).toByte()

        assertThrows(InvalidCipherTextException::class.java) {
            h.bob.decryptMessage(ciphertext)
        }
    }

    @Test
    fun `sender and receiver derive the same transfer key, independent of the chat's own messages`() {
        val h = handshake()
        val id = TransferCipher.newTransferId()

        assertArrayEquals(
            h.alice.deriveTransferKey("frad-wfd-media-v2", id, outgoing = true),
            h.bob.deriveTransferKey("frad-wfd-media-v2", id, outgoing = false),
        )

        // Encrypting a chat message must not perturb the derived transfer key - the two are
        // meant to be independent so a concurrent BLE message and Wi-Fi Direct file transfer
        // can't collide on one nonce counter (see ChatSession.deriveTransferKey).
        h.alice.encryptMessage("hello")
        assertArrayEquals(
            h.alice.deriveTransferKey("frad-wfd-media-v2", id, outgoing = true),
            h.bob.deriveTransferKey("frad-wfd-media-v2", id, outgoing = false),
        )
    }

    @Test
    fun `every transfer, direction and transport gets its own key`() {
        // TransferCipher always starts its nonce counter at 0, so any two transfers sharing a key
        // would reuse (key, nonce) pairs - the bug this test guards against.
        val h = handshake()
        val first = TransferCipher.newTransferId()
        val second = TransferCipher.newTransferId()

        val keys = listOf(
            h.alice.deriveTransferKey("frad-wfd-media-v2", first, outgoing = true),
            h.alice.deriveTransferKey("frad-wfd-media-v2", second, outgoing = true),
            // Same id, other direction (Bob sending to Alice).
            h.bob.deriveTransferKey("frad-wfd-media-v2", first, outgoing = true),
            // Same id and direction, other transport.
            h.alice.deriveTransferKey("frad-wide-transfer-v2", first, outgoing = true),
        )
        assertEquals(keys.size, keys.map { it.toList() }.toSet().size)
    }

    @Test
    fun `transfer ids are random and url-safe`() {
        val ids = List(100) { TransferCipher.newTransferId() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { id -> id.length == 22 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' } })
    }

    @Test
    fun `truncated handshake messages are rejected up front instead of overrunning the buffer`() {
        val alice = ChatSession(isInitiator = true, identity = randomIdentity())
        val bob = ChatSession(isInitiator = false, identity = randomIdentity())

        assertThrows(IllegalArgumentException::class.java) { bob.respondToHandshake(ByteArray(10)) }

        alice.startHandshake()
        assertThrows(IllegalArgumentException::class.java) { alice.completeHandshake(ByteArray(40)) }
    }

    @Test
    fun `two independent handshakes between the same identities yield unlinkable ciphertexts`() {
        // Ephemeral keys must make each handshake's transport keys different, even for
        // repeat conversations between the same two static identities (forward secrecy).
        val aliceIdentity = randomIdentity()
        val bobIdentity = randomIdentity()

        fun run(): ByteArray {
            val alice = ChatSession(true, aliceIdentity)
            val bob = ChatSession(false, bobIdentity)
            bob.respondToHandshake(alice.startHandshake()).let { alice.completeHandshake(it) }
                .let { bob.finishHandshake(it) }
            return alice.encryptMessage("same plaintext")
        }

        val first = run()
        val second = run()
        assertNotEquals(String(first), String(second))
    }
}
