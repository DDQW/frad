package app.frad.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.frad.chat.chat.ChatUiState
import app.frad.chat.profile.Profile

private enum class Tab(val label: String, val icon: ImageVector) {
    RADAR("Radar", Icons.Default.Wifi),
    CONTACTS("Contacts", Icons.Default.Group),
    BLOCKED("Blocked", Icons.Default.Block),
    PROFILE("Profile", Icons.Default.Person),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    viewModel: ChatViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    if (!permissionsGranted) {
        PermissionGate(onRequestPermissions)
        return
    }

    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.RADAR) }

    // Hide the tab bar while a chat is actively being set up or in progress, so
    // switching tabs can't be used to sidestep "Leave"/"Block" on an open chat.
    val busyWithChat = state is ChatUiState.Connecting || state is ChatUiState.Handshaking || state is ChatUiState.Chatting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FRAD", fontWeight = FontWeight.Bold) },
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = !busyWithChat) {
                NavigationBar {
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon, contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (busyWithChat || tab == Tab.RADAR) {
                RadarTab(state = state, viewModel = viewModel)
            } else when (tab) {
                Tab.CONTACTS -> ContactsTab(viewModel = viewModel)
                Tab.BLOCKED -> BlockedTab(viewModel = viewModel)
                Tab.PROFILE -> ProfileTab(viewModel = viewModel)
                Tab.RADAR -> Unit // unreachable, handled above
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequestPermissions: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "FRAD needs Bluetooth permission to find people nearby.",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "It never asks for your exact location.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
        }
    }
}
