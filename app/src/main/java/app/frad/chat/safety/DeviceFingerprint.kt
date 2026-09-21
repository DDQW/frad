package app.frad.chat.safety

import android.content.Context
import android.provider.Settings
import java.util.Base64
import app.frad.chat.crypto.noise.Primitives

/**
 * Best-effort per-device id, independent of [app.frad.chat.crypto.Identity]'s keypair,
 * so a block survives the blocked person resetting their identity (clearing app data,
 * reinstalling) as long as it's still the same physical device — M5's "blocked people stay
 * blocked" goal. Derived from `Settings.Secure.ANDROID_ID`, which Android already scopes to this
 * app's signing key + device + user profile and needs no extra permission to read; hashed with a
 * fixed salt before ever being stored or sent so the raw platform value never leaves this
 * function. Exchanged with a peer the same way [app.frad.chat.crypto.Identity.peerId] is
 * — only after a chat's Noise handshake completes with them specifically — so it adds no new way
 * to passively track or fingerprint a device that isn't already true of the identity keypair.
 *
 * Not foolproof: a factory reset, a different Android user profile, or a spoofed ANDROID_ID on a
 * rooted device all evade it. It's meant to raise the bar past a trivial "clear app data" reset,
 * not to defeat a determined attacker.
 */
object DeviceFingerprint {
    fun compute(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: FALLBACK_ID
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Primitives.sha256((SALT + androidId).toByteArray(Charsets.UTF_8)))
    }

    private const val SALT = "frad-device-fingerprint-v1:"
    private const val FALLBACK_ID = "unknown-android-id"
}
