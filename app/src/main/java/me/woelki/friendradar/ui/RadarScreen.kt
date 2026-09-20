package me.woelki.friendradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import me.woelki.friendradar.ble.ChatUiState

@Composable
fun RadarScreen(
    viewModel: ChatViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("FRAD", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        if (!permissionsGranted) {
            Text("FRAD needs Bluetooth permission to find people nearby. It never asks for your exact location.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
            return@Column
        }

        when (val current = state) {
            ChatUiState.Idle -> IdleContent(onStart = { viewModel.setBrowsing(true) })
            is ChatUiState.Browsing -> BrowsingContent(
                peerCount = current.nearbyPeers.size,
                onStop = { viewModel.setBrowsing(false) },
                onRandomChat = { viewModel.requestRandomChat() },
            )
            is ChatUiState.Connecting -> Text("Connecting…")
            ChatUiState.Handshaking -> Text("Setting up an encrypted connection…")
            is ChatUiState.Chatting -> ChatContent(
                remotePeerId = current.remotePeerId,
                messages = current.messages,
                onSend = { viewModel.sendMessage(it) },
                onLeave = { viewModel.endChat() },
                onBlock = { viewModel.blockActivePeer() },
                onReport = { viewModel.reportActivePeer("reported from chat") },
            )
            is ChatUiState.Ended -> Text(current.reason)
        }
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit) {
    Text("You're not visible to anyone right now.")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onStart) { Text("Become visible nearby") }
}

@Composable
private fun BrowsingContent(peerCount: Int, onStop: () -> Unit, onRandomChat: () -> Unit) {
    Text(if (peerCount == 0) "Looking for people nearby…" else "$peerCount people nearby right now")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onRandomChat, modifier = Modifier.fillMaxWidth()) {
        Text("Chat with someone nearby")
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onStop) { Text("Stop being visible") }
}

@Composable
private fun ChatContent(
    remotePeerId: String,
    messages: List<me.woelki.friendradar.ble.ChatMessage>,
    onSend: (String) -> Unit,
    onLeave: () -> Unit,
    onBlock: () -> Unit,
    onReport: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Chatting with ${remotePeerId.take(8)}…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Row {
            TextButton(onClick = onLeave) { Text("Leave") }
            TextButton(onClick = onBlock) { Text("Block") }
            TextButton(onClick = onReport) { Text("Report") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(messages) { message ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start) {
                    Text(if (message.fromMe) "You: ${message.text}" else message.text)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
            }) { Text("Send") }
        }
    }
}
