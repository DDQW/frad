package app.frad.chat.safety

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Best-effort per-device id, independent of [app.frad.chat.crypto.Identity]'s keypair, so a block
 * survives the blocked person resetting their identity (clearing app data, reinstalling) as long
 * as it's still the same physical device — M5's "blocked people stay blocked" goal. Derived from
 * `Settings.Secure.ANDROID_ID`, which Android already scopes to this app's signing key + device +
 * user profile and needs no extra permission to read; the raw platform value never leaves
 * [deviceSecret].
 *
 * Pairwise: the value sent to a peer is an HMAC of their long-term static key under this device's
 * secret ([forPeer]). Whoever blocks us keeps seeing the same value from this device - their
 * static key doesn't change when ours does - so the block still holds after we reset our identity.
 * But two different people we chatted with get unrelated values, so they can't compare notes and
 * link us across identity resets the way one global device id would allow.
 *
 * Not foolproof: a factory reset, a different Android user profile, or a spoofed ANDROID_ID on a
 * rooted device all evade it. It's meant to raise the bar past a trivial "clear app data" reset,
 * not to defeat a determined attacker.
 */
object DeviceFingerprint {
    fun deviceSecret(context: Context): ByteArray {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: FALLBACK_ID
        return MessageDigest.getInstance("SHA-256").digest((SECRET_SALT + androidId).toByteArray(Charsets.UTF_8))
    }

    /** The fingerprint this device presents to the peer whose long-term static key is [remoteStaticKey]. */
    fun forPeer(deviceSecret: ByteArray, remoteStaticKey: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(deviceSecret, "HmacSHA256")) }
        mac.update(PAIRWISE_LABEL.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(remoteStaticKey))
    }

    private const val SECRET_SALT = "frad-device-secret-v1:"
    private const val PAIRWISE_LABEL = "frad-device-fingerprint-v2|"
    private const val FALLBACK_ID = "unknown-android-id"
}
