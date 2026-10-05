package app.frad.chat.crypto

import java.nio.charset.StandardCharsets
import java.util.Base64
import app.frad.chat.crypto.noise.NoiseTransportKeys
import app.frad.chat.crypto.noise.NoiseXXHandshake
import app.frad.chat.crypto.noise.Primitives

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
    private val handshake = NoiseXXHandshake(
        isInitiator,
        identity.privateKey,
        identity.publicKey,
        PROLOGUE.toByteArray(StandardCharsets.US_ASCII),
    )
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
        readResponse(message2)
        return finishAsInitiator()
    }

    /** Initiator, first half of [completeHandshake]: learns the responder's identity
     *  ([remotePeerId] works afterwards) without revealing ours yet - message 3 is what carries
     *  our static key, so a caller can still walk away from a peer it has blocked. */
    fun readResponse(message2: ByteArray) {
        check(isInitiator)
        handshake.readMessage2(message2)
    }

    /** Initiator, second half of [completeHandshake]: message 3 to send back. */
    fun finishAsInitiator(): ByteArray {
        check(isInitiator)
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

    /** The peer's long-term static public key, known once the handshake has revealed it. */
    fun remoteStaticKey(): ByteArray = handshake.remoteStaticKey()

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

    /** A 32-byte secret independent of the chat's own transport keys, for encrypting one
     *  side-channel file transfer (see [app.frad.chat.crypto.TransferCipher]) instead of
     *  reusing the chat's [encryptMessage]/[decryptMessage] nonce counter, which two concurrent
     *  transports incrementing independently could otherwise collide on. Identical on both sides,
     *  since it's derived from the mutually-authenticated handshake transcript.
     *
     *  Every [TransferCipher] starts its nonce counter at 0, so this key must never be reused
     *  across transfers: [transferId] (a fresh [TransferCipher.newTransferId] the sender puts in
     *  its file offer) makes it unique per file, and the direction is mixed in as well so a file
     *  each way can't share one key either. [outgoing] is true on the sending side and false on
     *  the receiving side - both then derive the same key for the same transfer.
     *
     *  [transport] must be distinct per file-transfer transport (Wi-Fi Direct vs. wide-range)
     *  sharing this same handshake - see [app.frad.chat.wideradius.WideRangeChatController]'s
     *  use of a different one. */
    fun deriveTransferKey(transport: String, transferId: String, outgoing: Boolean): ByteArray {
        check(isReady) { "Handshake not complete" }
        require(transferId.isNotEmpty()) { "Empty transfer id" }
        val senderIsInitiator = outgoing == isInitiator
        val direction = if (senderIsInitiator) "i2r" else "r2i"
        return handshake.deriveKey("$transport|$direction|$transferId".toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        /** Noise prologue: names the app and its chat protocol version. A peer speaking a
         *  different version fails the handshake right away (as an authentication error) instead
         *  of misinterpreting what follows. Bump it with any incompatible protocol change. */
        const val PROLOGUE = "FRAD chat/2"
    }
}
