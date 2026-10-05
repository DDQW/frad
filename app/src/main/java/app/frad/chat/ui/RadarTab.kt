package app.frad.chat.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.frad.chat.chat.ChatUiState
import app.frad.chat.profile.Profile

@Composable
internal fun RadarTab(state: ChatUiState, viewModel: ChatViewModel) {
    when (val current = state) {
        ChatUiState.Idle -> {
            val mode by viewModel.mode.collectAsState()
            IdleContent(
                mode = mode,
                wideRangeAvailable = viewModel.wideRangeAvailable,
                onModeChange = { viewModel.setMode(it) },
                onStart = { viewModel.setBrowsing(true) },
            )
        }
        is ChatUiState.Browsing -> BrowsingContent(
            peerCount = current.nearbyPeers.size,
            warning = current.warning,
            onStop = { viewModel.setBrowsing(false) },
            onRandomChat = { viewModel.requestRandomChat() },
        )
        is ChatUiState.Paused -> PausedContent(reason = current.reason, onStop = { viewModel.setBrowsing(false) })
        is ChatUiState.Connecting -> CenteredStatus(message = "Connecting…", onCancel = { viewModel.endChat() })
        ChatUiState.Handshaking -> CenteredStatus(message = "Setting up an encrypted connection…", onCancel = { viewModel.endChat() })
        is ChatUiState.Chatting -> {
            var saved by remember(current.remotePeerId) { mutableStateOf(viewModel.isContactSaved(current.remotePeerId)) }
            val transferStatus by viewModel.transferStatus.collectAsState()
            val transferProgress by viewModel.transferProgress.collectAsState()
            val errorEvent by viewModel.errorEvent.collectAsState()
            ChatContent(
                remotePeerId = current.remotePeerId,
                remotePseudonym = current.remotePseudonym,
                remoteGender = current.remoteGender,
                remoteAge = current.remoteAge,
                remoteBio = current.remoteBio,
                remotePhoto = current.remotePhoto,
                messages = current.messages,
                peerTyping = current.peerTyping,
                safetyNumber = current.safetyNumber,
                alreadySaved = saved,
                fileTransferAvailable = viewModel.fileTransferAvailable,
                transferStatus = transferStatus,
                transferProgress = transferProgress,
                onCancelTransfer = { viewModel.cancelTransfer() },
                incomingFile = current.incomingFile,
                onAnswerIncomingFile = { viewModel.answerIncomingFile(it) },
                errorMessage = errorEvent,
                onDismissError = { viewModel.consumeErrorEvent() },
                onSend = { viewModel.sendMessage(it) },
                onTyping = { viewModel.notifyTyping() },
                onSendFile = { uri, afterRead -> viewModel.sendFile(uri, afterRead) },
                onLeave = { viewModel.endChat() },
                onBlock = { viewModel.blockActivePeer() },
                onReport = { reason -> viewModel.reportActivePeer(reason) },
                onSaveContact = {
                    viewModel.saveContact(current.remotePeerId, current.remotePseudonym)
                    saved = true
                },
            )
        }
        // Only stays on screen while not browsing (e.g. wide-range couldn't start); while browsing,
        // controllers go straight back to Browsing.
        is ChatUiState.Ended -> EndedContent(reason = current.reason, onDismiss = { viewModel.acknowledgeEnded() })
    }
}

@Composable
internal fun CenteredStatus(message: String, onCancel: (() -> Unit)? = null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
            if (onCancel != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun EndedContent(reason: String, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(reason, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun PausedContent(reason: String, onStop: () -> Unit) {
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            Text(reason, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { runCatching { context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } },
                modifier = Modifier.fillMaxWidth(0.8f),
            ) { Text("Turn on Bluetooth") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onStop) { Text("Stop being visible") }
        }
    }
}

@Composable
private fun IdleContent(mode: ChatMode, wideRangeAvailable: Boolean, onModeChange: (ChatMode) -> Unit, onStart: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text("Find people", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == ChatMode.LOCAL_BLE,
                    onClick = { onModeChange(ChatMode.LOCAL_BLE) },
                    label = { Text("Nearby (Bluetooth)") },
                    leadingIcon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = mode == ChatMode.WIDE_RANGE,
                    onClick = { onModeChange(ChatMode.WIDE_RANGE) },
                    enabled = wideRangeAvailable,
                    label = { Text("Wide range (internet)") },
                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (!wideRangeAvailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wide-range isn't built into this app - see p2p-go/README.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (mode == ChatMode.LOCAL_BLE) "You're not visible to anyone right now."
                else "You're not visible to anyone right now. Set your area in Profile first if you haven't.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth(0.8f)) { Text("Become visible") }
        }
    }
}

@Composable
private fun BrowsingContent(peerCount: Int, warning: String?, onStop: () -> Unit, onRandomChat: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(
                if (peerCount == 0) Icons.Default.Search else Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (peerCount == 0) "Looking for people…" else "$peerCount people found right now",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (warning != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRandomChat, modifier = Modifier.fillMaxWidth(0.8f)) {
                Text("Chat with someone nearby")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onStop) { Text("Stop being visible") }
        }
    }
}
