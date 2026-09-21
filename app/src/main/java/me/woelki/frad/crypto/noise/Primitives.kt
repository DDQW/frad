package me.woelki.frad.crypto.noise

import java.security.SecureRandom
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * DH, HKDF and AEAD primitives for the Noise_XX_25519_ChaChaPoly_SHA256 handshake,
 * implemented directly against BouncyCastle's lightweight API so behaviour is
 * identical across Android versions/OEMs instead of relying on platform JCA
 * provider differences.
 */
internal object Primitives {
    const val DH_LEN = 32
    const val HASH_LEN = 32
    private val secureRandom = SecureRandom()

    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(secureRandom)
        val pub = priv.generatePublicKey()
        val privBytes = ByteArray(DH_LEN)
        priv.encode(privBytes, 0)
        val pubBytes = ByteArray(DH_LEN)
        pub.encode(pubBytes, 0)
        return privBytes to pubBytes
    }

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), shared, 0)
        return shared
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = SHA256Digest()
        for (part in parts) digest.update(part, 0, part.size)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    /** Noise spec section 4.3: HKDF producing 2 or 3 32-byte outputs. */
    fun hkdf(chainingKey: ByteArray, inputKeyMaterial: ByteArray, numOutputs: Int): List<ByteArray> {
        require(numOutputs == 2 || numOutputs == 3)
        val tempKey = hmacSha256(chainingKey, inputKeyMaterial)
        val output1 = hmacSha256(tempKey, byteArrayOf(0x01))
        val output2 = hmacSha256(tempKey, output1 + byteArrayOf(0x02))
        if (numOutputs == 2) return listOf(output1, output2)
        val output3 = hmacSha256(tempKey, output2 + byteArrayOf(0x03))
        return listOf(output1, output2, output3)
    }

    /** IETF ChaCha20-Poly1305 AEAD, with the 96-bit nonce encoded per the Noise spec:
     *  32 bits of zeros followed by the little-endian 64-bit counter `n`. */
    private fun nonceBytes(n: Long): ByteArray {
        val nonce = ByteArray(12)
        var v = n
        for (i in 4 until 12) {
            nonce[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return nonce
    }

    fun encrypt(key: ByteArray, n: Long, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonceBytes(n), ad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }

    /** @throws org.bouncycastle.crypto.InvalidCipherTextException on authentication failure. */
    fun decrypt(key: ByteArray, n: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonceBytes(n), ad))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += cipher.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }
}
