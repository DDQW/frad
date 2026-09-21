package app.frad.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import app.frad.chat.ui.ChatViewModel
import app.frad.chat.ui.RadarScreen
import app.frad.chat.ui.theme.FradTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private var permissionsGranted by mutableStateOf(false)

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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permissionsGranted = grants.values.all { it }
        if (permissionsGranted) onCorePermissionsGranted()
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
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissionsGranted) onCorePermissionsGranted()

        setContent {
            FradTheme {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    RadarScreen(
                        viewModel = viewModel,
                        permissionsGranted = permissionsGranted,
                        onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    )
                }
            }
        }
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
}
