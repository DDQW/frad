package me.woelki.frad

import android.Manifest
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
import me.woelki.frad.ui.ChatViewModel
import me.woelki.frad.ui.RadarScreen
import me.woelki.frad.ui.theme.FradTheme

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionsGranted = requiredPermissions.all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

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
}
