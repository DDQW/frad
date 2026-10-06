package app.frad.chat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import app.frad.chat.profile.Profile
import app.frad.chat.ui.ChatViewModel
import app.frad.chat.ui.RadarScreen
import app.frad.chat.ui.theme.FradTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private var permissionsGranted by mutableStateOf(false)

    // App lock (see AppLock / Profile.appLock): locked on start, and again after a while in the background.
    private var locked by mutableStateOf(false)
    private var backgroundedAtMillis = 0L
    /** The prompt opens by itself once per lock - after a cancel, the "Unlock" button brings it
     *  back, instead of it reopening on every return to the activity. */
    private var promptedForThisLock = false
    private val deviceCredentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) locked = false
    }

    private val requiredPermissions: Array<String>
        get() {
            val ble = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Wi-Fi Direct file transfer (M3) is only ever used from API 29+ (see
            // BleChatController), and WifiP2pManager needs one of these two on top of BLE's
            // own permissions, same tiered split as BLE scanning above.
            val wifiDirect = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> emptyArray()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            return ble + wifiDirect
        }

    /** Android stops showing the permission dialog after it was denied twice (or with "don't
     *  ask again") - then only the app's system settings page can grant it. */
    private var permissionsBlocked by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionsGranted = grants.values.all { it }
        if (permissionsGranted) {
            onCorePermissionsGranted()
        } else {
            permissionsBlocked = grants.filterValues { !it }.keys.none { shouldShowRequestPermissionRationale(it) }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
    }

    // Separate from requiredPermissions/permissionsGranted: this one only gates whether
    // LocalBleService's "always visible" notification is actually visible, not any core
    // functionality - denying it shouldn't lock the user out of the rest of the app the way
    // denying Bluetooth does, so it's requested independently and the service starts either way.
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Across a rotation the lock state carries over; after anything else (process death, a
        // fresh launch) the app starts locked - see onSaveInstanceState.
        locked = Profile(this).appLock && AppLock.available(this) &&
            (savedInstanceState?.getBoolean(STATE_LOCKED) ?: true)
        if (savedInstanceState == null) handleLink(intent) // not again after a rotation
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissionsGranted) onCorePermissionsGranted()

        setContent {
            FradTheme {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (locked) {
                        LockedScreen(onUnlock = ::unlock)
                    } else {
                        RadarScreen(
                            viewModel = viewModel,
                            permissionsGranted = permissionsGranted,
                            onRequestPermissions = {
                                if (permissionsBlocked) openAppSettings() else permissionLauncher.launch(requiredPermissions)
                            },
                            permissionsBlocked = permissionsBlocked,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** A `frad://node` link (see wideradius/NodeLinks.kt) only ever leads to a confirmation. */
    private fun handleLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let(viewModel::offerNodeLink)
    }

    override fun onStart() {
        super.onStart()
        val appLock = Profile(this).appLock
        // Keep chats out of the recent-apps overview and screenshots whenever the app is locked down.
        if (appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (appLock && !locked && backgroundedAtMillis != 0L &&
            SystemClock.elapsedRealtime() - backgroundedAtMillis > AppLock.RELOCK_AFTER_MILLIS
        ) {
            locked = AppLock.available(this)
            promptedForThisLock = false
        }
        if (locked && !promptedForThisLock) {
            promptedForThisLock = true
            unlock()
        }
        viewModel.setUiVisible(true)
        // Back from the system settings page, perhaps with the permissions granted there.
        if (!permissionsGranted && requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            permissionsGranted = true
            permissionsBlocked = false
            onCorePermissionsGranted()
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAtMillis = SystemClock.elapsedRealtime()
        viewModel.setUiVisible(false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only a configuration change may restore an unlocked app; being recreated for any
        // other reason means it was in the background, possibly for long.
        outState.putBoolean(STATE_LOCKED, locked || !isChangingConfigurations)
    }

    private fun unlock() {
        AppLock.prompt(this) { locked = false }?.let(deviceCredentialLauncher::launch)
    }

    /** Core BLE permissions are granted - safe to let [ChatViewModel] start the always-visible
     *  background service now (see [ChatViewModel.onPermissionsGranted]). Requests the separate,
     *  non-blocking notification permission first on API 33+ so that service's persistent
     *  notification actually shows; starts regardless of that specific grant either way. */
    private fun onCorePermissionsGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onPermissionsGranted()
        }
    }

    private companion object {
        const val STATE_LOCKED = "locked"
    }
}

@androidx.compose.runtime.Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.misc_locked_title), style = MaterialTheme.typography.titleLarge)
        Button(onClick = onUnlock) { Text(stringResource(R.string.misc_unlock)) }
    }
}
