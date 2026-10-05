package app.frad.chat

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Optional lock in front of the app (see [app.frad.chat.profile.Profile.appLock]): fingerprint/face
 * or the phone's own PIN/pattern, through the platform's own prompt - no extra library needed.
 * Only offered when the phone has a screen lock at all; without one there'd be nothing to unlock
 * with, and the user could lock themselves out.
 */
object AppLock {
    /** How long the app may sit in the background before it locks again. */
    const val RELOCK_AFTER_MILLIS = 30_000L

    fun available(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /**
     * Shows the system unlock prompt and calls [onUnlocked] on success. On API 26-27, which has no
     * BiometricPrompt, returns the device-credential intent for the caller to launch (null when
     * the prompt was shown directly).
     */
    fun prompt(activity: Activity, onUnlocked: () -> Unit): Intent? {
        if (!available(activity)) {
            onUnlocked() // no screen lock any more - never lock the user out of their own app
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            return (activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .createConfirmDeviceCredentialIntent("Unlock FRAD", null)
        }
        val builder = BiometricPrompt.Builder(activity).setTitle("Unlock FRAD")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onUnlocked()
            },
        )
        return null
    }
}
