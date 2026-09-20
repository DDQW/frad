package me.woelki.friendradar.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import me.woelki.friendradar.chat.ChatMessage
import me.woelki.friendradar.chat.ChatUiState
import me.woelki.friendradar.chat.MessageKind
import me.woelki.friendradar.contacts.Contact
import me.woelki.friendradar.profile.Profile

private enum class Tab { RADAR, CONTACTS, BLOCKED, PROFILE }

@Composable
fun RadarScreen(
    viewModel: ChatViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.RADAR) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("FRAD", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        if (!permissionsGranted) {
            Text("FRAD needs Bluetooth permission to find people nearby. It never asks for your exact location.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
            return@Column
        }

        // Hide the tab bar while a chat is actively being set up or in progress, so
        // switching tabs can't be used to sidestep "Leave"/"Block" on an open chat.
        val busyWithChat = state is ChatUiState.Connecting || state is ChatUiState.Handshaking || state is ChatUiState.Chatting
        if (!busyWithChat) {
            TabBar(current = tab, onSelect = { tab = it })
            Spacer(Modifier.height(8.dp))
        }

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

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row {
        TextButton(onClick = { onSelect(Tab.RADAR) }) { Text(if (current == Tab.RADAR) "[Radar]" else "Radar") }
        TextButton(onClick = { onSelect(Tab.CONTACTS) }) { Text(if (current == Tab.CONTACTS) "[Contacts]" else "Contacts") }
        TextButton(onClick = { onSelect(Tab.BLOCKED) }) { Text(if (current == Tab.BLOCKED) "[Blocked]" else "Blocked") }
        TextButton(onClick = { onSelect(Tab.PROFILE) }) { Text(if (current == Tab.PROFILE) "[Profile]" else "Profile") }
    }
}

@Composable
private fun RadarTab(state: ChatUiState, viewModel: ChatViewModel) {
    when (val current = state) {
        ChatUiState.Idle -> IdleContent(onStart = { viewModel.setBrowsing(true) })
        is ChatUiState.Browsing -> BrowsingContent(
            peerCount = current.nearbyPeers.size,
            onStop = { viewModel.setBrowsing(false) },
            onRandomChat = { viewModel.requestRandomChat() },
        )
        is ChatUiState.Connecting -> Text("Connecting…")
        ChatUiState.Handshaking -> Text("Setting up an encrypted connection…")
        is ChatUiState.Chatting -> {
            var saved by remember(current.remotePeerId) { mutableStateOf(viewModel.isContactSaved(current.remotePeerId)) }
            val transferStatus by viewModel.transferStatus.collectAsState()
            val errorEvent by viewModel.errorEvent.collectAsState()
            ChatContent(
                remotePeerId = current.remotePeerId,
                remotePseudonym = current.remotePseudonym,
                messages = current.messages,
                alreadySaved = saved,
                fileTransferAvailable = viewModel.fileTransferAvailable,
                transferStatus = transferStatus,
                errorMessage = errorEvent,
                onDismissError = { viewModel.consumeErrorEvent() },
                onSend = { viewModel.sendMessage(it) },
                onSendFile = { viewModel.sendFile(it) },
                onLeave = { viewModel.endChat() },
                onBlock = { viewModel.blockActivePeer() },
                onReport = { viewModel.reportActivePeer("reported from chat") },
                onSaveContact = {
                    viewModel.saveContact(current.remotePeerId, current.remotePseudonym)
                    saved = true
                },
            )
        }
        is ChatUiState.Ended -> Text(current.reason)
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
    remotePseudonym: String,
    messages: List<ChatMessage>,
    alreadySaved: Boolean,
    fileTransferAvailable: Boolean,
    transferStatus: String?,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onSend: (String) -> Unit,
    onSendFile: (Uri) -> Unit,
    onLeave: () -> Unit,
    onBlock: () -> Unit,
    onReport: () -> Unit,
    onSaveContact: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSendFile(uri)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Chatting with ${Profile.displayName(remotePseudonym, remotePeerId)}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Row {
            TextButton(onClick = onLeave) { Text("Leave") }
            TextButton(onClick = onSaveContact, enabled = !alreadySaved) { Text(if (alreadySaved) "Saved" else "Save contact") }
            TextButton(onClick = onBlock) { Text("Block") }
            TextButton(onClick = onReport) { Text("Report") }
        }
        Spacer(Modifier.height(8.dp))

        MessageList(messages, modifier = Modifier.weight(1f).fillMaxWidth())

        if (transferStatus != null) {
            Text(transferStatus, style = MaterialTheme.typography.bodySmall)
        }
        if (errorMessage != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissError) { Text("Dismiss") }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (fileTransferAvailable) {
                TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = transferStatus == null) { Text("Attach") }
            }
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

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier = modifier) {
        items(messages) { message ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start) {
                when (message.kind) {
                    MessageKind.TEXT -> Text(if (message.fromMe) "You: ${message.text}" else message.text)
                    MessageKind.FILE -> FileMessageContent(message, onOpen = { openFile(context, message) })
                }
            }
        }
    }
}

@Composable
private fun FileMessageContent(message: ChatMessage, onOpen: () -> Unit) {
    val path = message.localPath ?: return
    val prefix = if (message.fromMe) "You sent: " else "Received: "
    if (message.mimeType?.startsWith("image/") == true) {
        val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        Column {
            Text("$prefix${message.fileName}", style = MaterialTheme.typography.bodySmall)
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = message.fileName, modifier = Modifier.size(160.dp).clickable(onClick = onOpen))
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$prefix${message.fileName} (${message.sizeBytes / 1024} KB)")
            TextButton(onClick = onOpen) { Text("Open") }
        }
    }
}

private fun openFile(context: Context, message: ChatMessage) {
    val path = message.localPath ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, message.mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

@Composable
private fun ContactsTab(viewModel: ChatViewModel) {
    var contacts by remember { mutableStateOf(viewModel.contacts()) }
    var viewingHistoryFor by remember { mutableStateOf<Contact?>(null) }

    val viewing = viewingHistoryFor
    if (viewing != null) {
        ContactHistoryContent(
            contact = viewing,
            messages = remember(viewing.peerId) { viewModel.historyWith(viewing.peerId) },
            onBack = { viewingHistoryFor = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Saved contacts", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (contacts.isEmpty()) {
            Text("No saved contacts yet. Save someone from an active chat.")
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(contacts) { contact ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Profile.displayName(contact.alias, contact.peerId),
                        modifier = Modifier.weight(1f).clickable { viewingHistoryFor = contact },
                    )
                    TextButton(onClick = {
                        viewModel.removeContact(contact.peerId)
                        contacts = viewModel.contacts()
                    }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun ContactHistoryContent(contact: Contact, messages: List<ChatMessage>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back") }
            Text(Profile.displayName(contact.alias, contact.peerId), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        if (messages.isEmpty()) {
            Text("No saved messages with this contact yet - only messages exchanged after you saved them are kept.")
        }
        MessageList(messages, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun BlockedTab(viewModel: ChatViewModel) {
    var blocked by remember { mutableStateOf(viewModel.blockedPeerIds()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Blocked", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (blocked.isEmpty()) {
            Text("You haven't blocked anyone.")
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(blocked) { peerId ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${peerId.take(10)}…", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        viewModel.unblock(peerId)
                        blocked = viewModel.blockedPeerIds()
                    }) { Text("Unblock") }
                }
            }
        }
    }
}

@Composable
private fun ProfileTab(viewModel: ChatViewModel) {
    var draft by remember { mutableStateOf(viewModel.myPseudonym) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Your profile", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Others see you as: ${Profile.displayName(draft, viewModel.myPeerId)}")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(Profile.MAX_LENGTH) },
            label = { Text("Pseudonym") },
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { viewModel.myPseudonym = draft }) { Text("Save") }
        Spacer(Modifier.height(16.dp))
        Text(
            "The part after # is unique to your device, so people who picked the same pseudonym as you stay distinguishable.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
