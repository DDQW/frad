package me.woelki.friendradar.crypto

import java.nio.charset.StandardCharsets
import java.util.Base64
import me.woelki.friendradar.crypto.noise.NoiseTransportKeys
import me.woelki.friendradar.crypto.noise.NoiseXXHandshake
import me.woelki.friendradar.crypto.noise.Primitives

/**
 * Drives a Noise_XX handshake to set up an end-to-end encrypted 1:1 chat with a
 * peer discovered over BLE (or, from M4 onward, the wide-range DHT layer), then
 * encrypts/decrypts the resulting text messages.
 *
 * The transport (BLE GATT characteristic writes/notifications today) is
 * responsible only for moving these opaque byte blobs across the wire in order;
 * it never needs to know anything about the cryptography.
 */
class ChatSession(private val isInitiator: Boolean, identity: Identity) {
    private val handshake = NoiseXXHandshake(isInitiator, identity.privateKey, identity.publicKey)
    private var transportKeys: NoiseTransportKeys? = null

    val isReady: Boolean
        get() = transportKeys != null

    /** Initiator: call first, send the result to the peer. */
    fun startHandshake(): ByteArray {
        check(isInitiator) { "Only the initiator sends the first handshake message" }
        return handshake.writeMessage1()
    }

    /** Responder: call with the initiator's first message, send the result back. */
    fun respondToHandshake(message1: ByteArray): ByteArray {
        check(!isInitiator) { "Only the responder answers the first handshake message" }
        handshake.readMessage1(message1)
        return handshake.writeMessage2()
    }

    /** Initiator: call with the responder's message, send the result back, then handshake is complete. */
    fun completeHandshake(message2: ByteArray): ByteArray {
        check(isInitiator)
        handshake.readMessage2(message2)
        val message3 = handshake.writeMessage3()
        transportKeys = handshake.split()
        return message3
    }

    /** Responder: call with the initiator's final message; handshake is complete afterwards. */
    fun finishHandshake(message3: ByteArray) {
        check(!isInitiator)
        handshake.readMessage3(message3)
        transportKeys = handshake.split()
    }

    /** Base64url-encoded SHA-256 of the peer's static key — stable across this conversation,
     *  usable for blocking, but never learnable by anyone merely scanning for nearby devices. */
    fun remotePeerId(): String {
        val remoteStatic = handshake.remoteStaticKey()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Primitives.sha256(remoteStatic))
    }

    fun encryptMessage(text: String): ByteArray {
        val keys = transportKeys ?: error("Handshake not complete")
        return keys.encrypt(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun decryptMessage(ciphertext: ByteArray): String {
        val keys = transportKeys ?: error("Handshake not complete")
        return String(keys.decrypt(ciphertext), StandardCharsets.UTF_8)
    }
}
