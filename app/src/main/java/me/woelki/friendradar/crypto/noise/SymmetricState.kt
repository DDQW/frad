package me.woelki.friendradar.crypto.noise

/** Noise spec section 5.2: tracks the running handshake hash `h` and chaining key `ck`. */
internal class SymmetricState(protocolName: ByteArray) {
    private var h: ByteArray
    private var ck: ByteArray
    private val cipherState = CipherState()

    init {
        h = if (protocolName.size <= Primitives.HASH_LEN) {
            protocolName.copyOf(Primitives.HASH_LEN)
        } else {
            Primitives.sha256(protocolName)
        }
        ck = h
    }

    fun mixHash(data: ByteArray) {
        h = Primitives.sha256(h, data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempK) = Primitives.hkdf(ck, inputKeyMaterial, 2)
        ck = newCk
        cipherState.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipherState.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipherState.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /** Returns (sending, receiving) CipherStates for the *initiator*'s perspective;
     *  the responder must swap the pair. */
    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Primitives.hkdf(ck, ByteArray(0), 2)
        val c1 = CipherState().apply { initializeKey(k1) }
        val c2 = CipherState().apply { initializeKey(k2) }
        return c1 to c2
    }

    fun handshakeHash(): ByteArray = h
}

private operator fun <T> List<T>.component1() = this[0]
private operator fun <T> List<T>.component2() = this[1]
