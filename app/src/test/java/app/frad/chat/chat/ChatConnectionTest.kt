package app.frad.chat.chat

import kotlinx.coroutines.runBlocking
import app.frad.chat.crypto.Identity
import app.frad.chat.crypto.noise.Primitives
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConnectionTest {

    /** Collects whatever a [ChatConnection] sends, for the test to hand to the other side. */
    private class RecordingTransport : FrameTransport {
        val outbox = ArrayDeque<ByteArray>()
        var closed = false
        override suspend fun send(frame: ByteArray) { outbox.addLast(frame) }
        override fun close() { closed = true }
    }

    private class Side(
        val identity: Identity,
        val fingerprint: String,
        val pseudonym: String,
        val blocked: MutableSet<String> = mutableSetOf(),
    ) {
        val transport = RecordingTransport()
        val events = mutableListOf<ChatEvent>()
        val peerId: String get() = identity.peerId
        lateinit var connection: ChatConnection

        fun connect(isInitiator: Boolean): ChatConnection = ChatConnection(
            isInitiator = isInitiator,
            identity = identity,
            transport = transport,
            localDeviceFingerprint = fingerprint,
            localProfile = { JSONObject().put("pseudonym", pseudonym).put("gender", "FEMALE").toString() },
            isBlocked = { it in blocked },
        ).also { connection = it }
    }

    private fun side(name: String): Side {
        val (priv, pub) = Primitives.generateKeyPair()
        return Side(Identity.fromRawKeyPair(priv, pub), fingerprint = "fp-$name", pseudonym = name)
    }

    /** Delivers queued frames back and forth until both sides go quiet. */
    private suspend fun pump(a: Side, b: Side) {
        while (a.transport.outbox.isNotEmpty() || b.transport.outbox.isNotEmpty()) {
            a.transport.outbox.removeFirstOrNull()?.let { frame -> b.connection.onFrame(frame)?.let { b.events += it } }
            b.transport.outbox.removeFirstOrNull()?.let { frame -> a.connection.onFrame(frame)?.let { a.events += it } }
        }
    }

    private suspend fun connected(alice: Side = side("alice"), bob: Side = side("bob")): Pair<Side, Side> {
        alice.connect(isInitiator = true)
        bob.connect(isInitiator = false)
        alice.connection.start()
        pump(alice, bob)
        return alice to bob
    }

    @Test
    fun `setup ends with both sides knowing the other's identity, device and profile`() = runBlocking<Unit> {
        val (alice, bob) = connected()

        val aliceSaw = (alice.events.single() as ChatEvent.Ready).peer
        val bobSaw = (bob.events.single() as ChatEvent.Ready).peer
        assertEquals(bob.peerId, aliceSaw.peerId)
        assertEquals("fp-bob", aliceSaw.deviceFingerprint)
        assertEquals("bob", aliceSaw.profile.pseudonym)
        assertEquals(alice.peerId, bobSaw.peerId)
        assertEquals("fp-alice", bobSaw.deviceFingerprint)
        assertEquals("alice", bobSaw.profile.pseudonym)
        assertTrue(alice.connection.isReady && bob.connection.isReady)
    }

    @Test
    fun `envelopes flow both ways once open`() = runBlocking<Unit> {
        val (alice, bob) = connected()
        alice.events.clear(); bob.events.clear()

        alice.connection.send(ChatEnvelope.Text("hi bob"))
        bob.connection.send(ChatEnvelope.Text("hi alice"))
        pump(alice, bob)

        assertEquals(ChatEnvelope.Text("hi bob"), (bob.events.single() as ChatEvent.Received).envelope)
        assertEquals(ChatEnvelope.Text("hi alice"), (alice.events.single() as ChatEvent.Received).envelope)
    }

    @Test
    fun `a peer blocked by identity is dropped before learning anything about us`() = runBlocking<Unit> {
        val alice = side("alice")
        val bob = side("bob")
        bob.blocked += alice.peerId

        connected(alice, bob)

        assertEquals(listOf<ChatEvent>(ChatEvent.Blocked), bob.events)
        // Alice never got as far as Bob's profile (or even his device fingerprint).
        assertTrue(alice.events.isEmpty())
        assertFalse(alice.connection.isReady)
    }

    @Test
    fun `a peer blocked by device fingerprint never gets our profile`() = runBlocking<Unit> {
        val alice = side("alice")
        val bob = side("bob")
        alice.blocked += "fp-bob"

        connected(alice, bob)

        assertEquals(listOf<ChatEvent>(ChatEvent.Blocked), alice.events)
        assertTrue(bob.events.isEmpty())
        assertNull(alice.connection.remotePeer)
    }

    @Test
    fun `garbage at any point of setup is a protocol error`() = runBlocking<Unit> {
        val responder = side("bob").connect(isInitiator = false)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { responder.onFrame(ByteArray(3)) } }

        val initiator = side("alice").connect(isInitiator = true)
        initiator.start()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { initiator.onFrame(ByteArray(50)) } }
    }

    @Test
    fun `a tampered message on an open chat is a protocol error`() = runBlocking<Unit> {
        val (alice, bob) = connected()
        alice.connection.send(ChatEnvelope.Text("hello"))
        val frame = alice.transport.outbox.removeFirst()
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()

        assertThrows(Exception::class.java) { runBlocking { bob.connection.onFrame(frame) } }
    }

    @Test
    fun `sending before setup finishes is refused`() = runBlocking<Unit> {
        val alice = side("alice").connect(isInitiator = true)
        assertThrows(IllegalStateException::class.java) { runBlocking { alice.send(ChatEnvelope.Text("too early")) } }
    }

    @Test
    fun `transfer ids can only be claimed once per chat`() = runBlocking<Unit> {
        val (alice, _) = connected()
        assertTrue(alice.connection.claimTransferId("abc"))
        assertFalse(alice.connection.claimTransferId("abc"))
    }
}
