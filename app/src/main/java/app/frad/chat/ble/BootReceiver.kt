package app.frad.chat.ble

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.frad.chat.profile.Profile

/**
 * Brings [Profile.alwaysVisible] back after a reboot - otherwise "always visible" silently meant
 * "until the phone restarts". Does nothing unless the Bluetooth permissions were already granted
 * (the app must have been opened and set up at least once).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val profile = Profile(context)
        if (!profile.onboarded || !profile.alwaysVisible || !bluetoothPermissionsGranted(context)) return
        // Starting a foreground service from the background can still be refused on some
        // versions/OEMs; the next app launch starts it anyway.
        runCatching { LocalBleService.startAlwaysVisible(context) }
            .onFailure { Log.w("BootReceiver", "couldn't restore always-visible after boot", it) }
    }

    private fun bluetoothPermissionsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
