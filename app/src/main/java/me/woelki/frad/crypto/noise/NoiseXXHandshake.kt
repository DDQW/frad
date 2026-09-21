package me.woelki.frad.crypto.noise

import java.nio.charset.StandardCharsets

/**
 * Hardcoded implementation of the Noise_XX_25519_ChaChaPoly_SHA256 handshake
 * pattern (see http://noiseprotocol.org/noise.html):
 *
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 *
 * Both parties authenticate each other's long-term static key without either
 * side knowing the other's identity in advance — appropriate for FRAD,
 * where two strangers discover each other over BLE/DHT and only learn who
 * they're actually talking to once the encrypted channel is already up.
 *
 * This class is deliberately narrow (one pattern, one role each) rather than a
 * general Noise engine, to keep the state machine easy to audit.
 */
internal class NoiseXXHandshake(
    private val isInitiator: Boolean,
    private val staticPrivateKey: ByteArray,
    private val staticPublicKey: ByteArray,
) {
    private companion object {
        const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val PUB_LEN = Primitives.DH_LEN
        const val TAG_LEN = 16
    }

    private val symmetric = SymmetricState(PROTOCOL_NAME.toByteArray(StandardCharsets.US_ASCII))

    private var ephemeralPrivateKey: ByteArray? = null
    private var ephemeralPublicKey: ByteArray? = null
    private var remoteEphemeralPublicKey: ByteArray? = null
    private var remoteStaticPublicKey: ByteArray? = null

    init {
        symmetric.mixHash(ByteArray(0)) // empty prologue
    }

    private fun generateEphemeral() {
        val (priv, pub) = Primitives.generateKeyPair()
        ephemeralPrivateKey = priv
        ephemeralPublicKey = pub
    }

    // ---- Message 1: -> e -------------------------------------------------

    fun writeMessage1(): ByteArray {
        check(isInitiator)
        generateEphemeral()
        symmetric.mixHash(ephemeralPublicKey!!)
        val payload = symmetric.encryptAndHash(ByteArray(0))
        return ephemeralPublicKey!! + payload
    }

    fun readMessage1(message: ByteArray) {
        check(!isInitiator)
        remoteEphemeralPublicKey = message.copyOfRange(0, PUB_LEN)
        symmetric.mixHash(remoteEphemeralPublicKey!!)
        symmetric.decryptAndHash(message.copyOfRange(PUB_LEN, message.size))
    }

    // ---- Message 2: <- e, ee, s, es --------------------------------------

    fun writeMessage2(): ByteArray {
        check(!isInitiator)
        generateEphemeral()
        symmetric.mixHash(ephemeralPublicKey!!)
        symmetric.mixKey(Primitives.dh(ephemeralPrivateKey!!, remoteEphemeralPublicKey!!)) // ee
        val sCiphertext = symmetric.encryptAndHash(staticPublicKey) // s
        symmetric.mixKey(Primitives.dh(staticPrivateKey, remoteEphemeralPublicKey!!)) // es (responder: DH(s, re))
        val payload = symmetric.encryptAndHash(ByteArray(0))
        return ephemeralPublicKey!! + sCiphertext + payload
    }

    /** @return the remote party's static public key, learned from this message. */
    fun readMessage2(message: ByteArray): ByteArray {
        check(isInitiator)
        var offset = 0
        remoteEphemeralPublicKey = message.copyOfRange(offset, PUB_LEN).also { offset += PUB_LEN }
        symmetric.mixHash(remoteEphemeralPublicKey!!)
        symmetric.mixKey(Primitives.dh(ephemeralPrivateKey!!, remoteEphemeralPublicKey!!)) // ee

        val sCiphertext = message.copyOfRange(offset, offset + PUB_LEN + TAG_LEN).also { offset += PUB_LEN + TAG_LEN }
        val rs = symmetric.decryptAndHash(sCiphertext) // s
        remoteStaticPublicKey = rs
        symmetric.mixKey(Primitives.dh(ephemeralPrivateKey!!, rs)) // es (initiator: DH(e, rs))

        symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
        return rs
    }

    // ---- Message 3: -> s, se ---------------------------------------------

    fun writeMessage3(): ByteArray {
        check(isInitiator)
        val sCiphertext = symmetric.encryptAndHash(staticPublicKey) // s
        symmetric.mixKey(Primitives.dh(staticPrivateKey, remoteEphemeralPublicKey!!)) // se (initiator: DH(s, re))
        val payload = symmetric.encryptAndHash(ByteArray(0))
        return sCiphertext + payload
    }

    /** @return the remote party's static public key, learned from this message. */
    fun readMessage3(message: ByteArray): ByteArray {
        check(!isInitiator)
        val sCiphertext = message.copyOfRange(0, PUB_LEN + TAG_LEN)
        val rs = symmetric.decryptAndHash(sCiphertext) // s
        remoteStaticPublicKey = rs
        symmetric.mixKey(Primitives.dh(ephemeralPrivateKey!!, rs)) // se (responder: DH(e, rs))

        symmetric.decryptAndHash(message.copyOfRange(PUB_LEN + TAG_LEN, message.size))
        return rs
    }

    /** Must be called after message 3 has been written (initiator) or read (responder). */
    fun split(): NoiseTransportKeys {
        val (c1, c2) = symmetric.split()
        return if (isInitiator) NoiseTransportKeys(sending = c1, receiving = c2) else NoiseTransportKeys(sending = c2, receiving = c1)
    }

    fun remoteStaticKey(): ByteArray = remoteStaticPublicKey
        ?: error("Handshake not complete: remote static key not yet known")

    /** Derives a 32-byte secret independent of the transport keys returned by [split],
     *  identical on both sides post-handshake — see [ChatSession.deriveTransferKey]. */
    fun deriveKey(info: ByteArray): ByteArray = symmetric.deriveKey(info)
}

internal class NoiseTransportKeys(
    private val sending: CipherState,
    private val receiving: CipherState,
) {
    fun encrypt(plaintext: ByteArray): ByteArray = sending.encryptWithAd(ByteArray(0), plaintext)
    fun decrypt(ciphertext: ByteArray): ByteArray = receiving.decryptWithAd(ByteArray(0), ciphertext)
}
