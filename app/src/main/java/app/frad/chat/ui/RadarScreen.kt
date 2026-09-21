package app.frad.chat.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.launch
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.ChatUiState
import app.frad.chat.chat.MessageKind
import app.frad.chat.contacts.Contact
import app.frad.chat.media.AudioRecorder
import app.frad.chat.media.CaptureFiles
import app.frad.chat.profile.Profile
import app.frad.chat.ui.theme.fradExtraColors
import app.frad.chat.wideradius.AreaLookup
import app.frad.chat.wideradius.Geohash

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

@Composable
private fun RadarTab(state: ChatUiState, viewModel: ChatViewModel) {
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
            onStop = { viewModel.setBrowsing(false) },
            onRandomChat = { viewModel.requestRandomChat() },
        )
        is ChatUiState.Connecting -> CenteredStatus(message = "Connecting…")
        ChatUiState.Handshaking -> CenteredStatus(message = "Setting up an encrypted connection…")
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
        is ChatUiState.Ended -> CenteredStatus(message = current.reason)
    }
}

@Composable
private fun CenteredStatus(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
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
private fun BrowsingContent(peerCount: Int, onStop: () -> Unit, onRandomChat: () -> Unit) {
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
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRandomChat, modifier = Modifier.fillMaxWidth(0.8f)) {
                Text("Chat with someone nearby")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onStop) { Text("Stop being visible") }
        }
    }
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
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSendFile(uri)
    }

    // Photo/video capture is delegated to the device's own camera app (ACTION_IMAGE_CAPTURE/
    // ACTION_VIDEO_CAPTURE via implicit intent) rather than embedding a camera preview in FRAD,
    // so it needs no CAMERA permission of its own - only a temp file (CaptureFiles) for the
    // camera app to write its result into.
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (file != null) {
            val uri = CaptureFiles.uriFor(context, file)
            CaptureFiles.revokeAccess(context, uri)
            if (success) onSendFile(uri)
            file.delete()
        }
    }
    val captureVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (file != null) {
            val uri = CaptureFiles.uriFor(context, file)
            CaptureFiles.revokeAccess(context, uri)
            if (success) onSendFile(uri)
            file.delete()
        }
    }
    fun startPhotoCapture() {
        val file = CaptureFiles.newImageFile(context)
        val uri = CaptureFiles.uriFor(context, file)
        CaptureFiles.grantWriteAccess(context, MediaStore.ACTION_IMAGE_CAPTURE, uri)
        pendingCaptureFile = file
        takePictureLauncher.launch(uri)
    }
    fun startVideoCapture() {
        val file = CaptureFiles.newVideoFile(context)
        val uri = CaptureFiles.uriFor(context, file)
        CaptureFiles.grantWriteAccess(context, MediaStore.ACTION_VIDEO_CAPTURE, uri)
        pendingCaptureFile = file
        captureVideoLauncher.launch(uri)
    }

    // Voice messages are recorded in-app instead (no system "record audio" intent is reliably
    // available across devices), so this is the one attachment type that needs its own runtime
    // permission (RECORD_AUDIO).
    val audioRecorder = remember { AudioRecorder(context) }
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var audioPermissionDenied by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { audioRecorder.cancel() } }

    fun beginAudioRecording() {
        val file = CaptureFiles.newAudioFile(context)
        val started = runCatching { audioRecorder.start(file) }.isSuccess
        if (started) {
            recordingFile = file
            isRecordingAudio = true
            audioPermissionDenied = false
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginAudioRecording() else audioPermissionDenied = true
    }
    fun cancelAudioRecording() {
        audioRecorder.cancel()
        recordingFile?.delete()
        recordingFile = null
        isRecordingAudio = false
    }
    fun sendAudioRecording() {
        val ok = audioRecorder.stop()
        val file = recordingFile
        recordingFile = null
        isRecordingAudio = false
        if (ok && file != null) {
            onSendFile(CaptureFiles.uriFor(context, file))
            file.delete()
        } else {
            file?.delete()
        }
    }

    var showAttachMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(label = remotePseudonym, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        Profile.displayName(remotePseudonym, remotePeerId),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onLeave) {
                        Icon(Icons.Default.Close, contentDescription = "Leave chat")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = onSaveContact,
                        enabled = !alreadySaved,
                        label = { Text(if (alreadySaved) "Saved" else "Save contact") },
                        leadingIcon = {
                            Icon(if (alreadySaved) Icons.Default.Person else Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    AssistChip(
                        onClick = onBlock,
                        label = { Text("Block") },
                        leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error, leadingIconContentColor = MaterialTheme.colorScheme.error),
                    )
                    AssistChip(
                        onClick = onReport,
                        label = { Text("Report") },
                        leadingIcon = { Icon(Icons.Default.Report, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error, leadingIconContentColor = MaterialTheme.colorScheme.error),
                    )
                }
            }
        }
        HorizontalDivider()

        MessageList(messages, modifier = Modifier.weight(1f).fillMaxWidth())

        if (transferStatus != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(transferStatus, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (errorMessage != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissError) { Text("Dismiss") }
                }
            }
        }

        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (fileTransferAvailable) {
                        Box {
                            IconButton(onClick = { showAttachMenu = true }, enabled = transferStatus == null && !isRecordingAudio) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach")
                            }
                            DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("File") },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                    onClick = { showAttachMenu = false; filePicker.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Photo") },
                                    leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                                    onClick = { showAttachMenu = false; startPhotoCapture() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Video") },
                                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                                    onClick = { showAttachMenu = false; startVideoCapture() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Voice message") },
                                    leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            beginAudioRecording()
                                        } else {
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (isRecordingAudio) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Text("Recording voice message…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { cancelAudioRecording() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel recording")
                        }
                        IconButton(onClick = { sendAudioRecording() }, colors = IconButtonDefaults.filledIconButtonColors()) {
                            Icon(Icons.Default.Send, contentDescription = "Send voice message")
                        }
                    } else {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message") },
                            shape = RoundedCornerShape(24.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                if (draft.isNotBlank()) {
                                    onSend(draft)
                                    draft = ""
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(),
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send")
                        }
                    }
                }
                if (audioPermissionDenied) {
                    Text(
                        "Microphone permission denied - can't record a voice message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** A small circular initial-letter avatar, used anywhere a peer/contact is shown in a list or
 *  header - purely cosmetic; peer identity is always the full [Profile.displayName]. */
@Composable
private fun Avatar(label: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                label.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(messages) { message ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start) {
                MessageBubble(message = message, onOpenFile = { openFile(context, message) })
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onOpenFile: () -> Unit) {
    val bubbleColor = if (message.fromMe) MaterialTheme.fradExtraColors.bubbleMine else MaterialTheme.fradExtraColors.bubbleTheirs
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (message.fromMe) 16.dp else 4.dp,
        bottomEnd = if (message.fromMe) 4.dp else 16.dp,
    )
    Surface(color = bubbleColor, shape = shape, modifier = Modifier.widthIn(max = 280.dp)) {
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            when (message.kind) {
                MessageKind.TEXT -> Text(message.text)
                MessageKind.FILE -> FileMessageContent(message, onOpen = onOpenFile)
            }
        }
    }
}

@Composable
private fun FileMessageContent(message: ChatMessage, onOpen: () -> Unit) {
    val path = message.localPath ?: return
    if (message.mimeType?.startsWith("image/") == true) {
        val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
        Column {
            Text(message.fileName ?: "Image", style = MaterialTheme.typography.bodySmall)
            if (bitmap != null) {
                Spacer(Modifier.height(4.dp))
                Image(
                    bitmap = bitmap,
                    contentDescription = message.fileName,
                    modifier = Modifier.size(160.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen),
                )
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("${message.fileName} (${message.sizeBytes / 1024} KB)")
            Spacer(Modifier.width(6.dp))
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
private fun EmptyState(icon: ImageVector, message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
        Text(
            "Saved contacts",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
        if (contacts.isEmpty()) {
            EmptyState(Icons.Default.Group, "No saved contacts yet. Save someone from an active chat.")
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(contacts) { contact ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewingHistoryFor = contact },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(label = contact.alias)
                            Spacer(Modifier.width(12.dp))
                            Text(Profile.displayName(contact.alias, contact.peerId), modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                viewModel.removeContact(contact.peerId)
                                contacts = viewModel.contacts()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove contact")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactHistoryContent(contact: Contact, messages: List<ChatMessage>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Avatar(label = contact.alias, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Text(Profile.displayName(contact.alias, contact.peerId), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
        if (messages.isEmpty()) {
            EmptyState(Icons.Default.Group, "No saved messages with this contact yet - only messages exchanged after you saved them are kept.")
        } else {
            MessageList(messages, modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun BlockedTab(viewModel: ChatViewModel) {
    var blocked by remember { mutableStateOf(viewModel.blockedPeerIds()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Blocked",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
        if (blocked.isEmpty()) {
            EmptyState(Icons.Default.Block, "You haven't blocked anyone.")
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(blocked) { peerId ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Text("${peerId.take(10)}…", modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                viewModel.unblock(peerId)
                                blocked = viewModel.blockedPeerIds()
                            }) {
                                Icon(Icons.Default.LockOpen, contentDescription = "Unblock")
                            }
                        }
                    }
                }
            }
        }
    }
}

private val RADIUS_PRESETS = listOf(20.0 to "Neighborhood", 75.0 to "City", 600.0 to "Region", 20_000.0 to "Worldwide")

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ProfileTab(viewModel: ChatViewModel) {
    var draft by remember { mutableStateOf(viewModel.myPseudonym) }
    var alwaysVisible by remember { mutableStateOf(viewModel.alwaysVisible) }
    var radiusKm by remember { mutableStateOf(viewModel.searchRadiusKm) }
    var bootstrapDraft by remember { mutableStateOf(viewModel.bootstrapNodes.joinToString("\n")) }
    var locationDenied by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var areaNameDraft by remember { mutableStateOf("") }
    var areaStatus by remember { mutableStateOf<String?>(null) }
    var resolvingArea by remember { mutableStateOf(false) }

    // The stored area is a geohash (see Profile.coarseGeohash) - show it as a place name instead
    // of that cryptic code by reverse-geocoding it once when this screen first appears.
    LaunchedEffect(Unit) {
        val saved = viewModel.coarseGeohash ?: return@LaunchedEffect
        areaNameDraft = AreaLookup.nameFor(context, saved) ?: saved
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            locationDenied = true
            return@rememberLauncherForActivityResult
        }
        locationDenied = false
        val geohash = viewModel.useCurrentAreaAsGeohash()
        if (geohash == null) {
            areaStatus = "Couldn't get a location fix yet - try again in a moment."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            resolvingArea = true
            areaNameDraft = AreaLookup.nameFor(context, geohash) ?: geohash
            areaStatus = null
            resolvingArea = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionCard(title = "Your profile") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(label = draft)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Others see you as", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Profile.displayName(draft, viewModel.myPeerId), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Profile.MAX_LENGTH) },
                label = { Text("Pseudonym") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.myPseudonym = draft }) { Text("Save") }
            Spacer(Modifier.height(12.dp))
            Text(
                "The part after # is unique to your device, so people who picked the same pseudonym as you stay distinguishable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Visibility") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Always visible", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Keep FRAD discoverable in the background, even when it's not open, " +
                            "so people can actually find and message you. Shows an ongoing " +
                            "notification while active - never a silent background broadcast.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = alwaysVisible,
                    onCheckedChange = {
                        alwaysVisible = it
                        viewModel.alwaysVisible = it
                    },
                )
            }
        }

        SectionCard(title = "Wide-range (internet)") {
            Text(
                "Your area, as a place name - it's only ever reduced to a coarse cell roughly the " +
                    "size of the search radius below before it's shared, never your exact location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = areaNameDraft,
                onValueChange = { areaNameDraft = it; areaStatus = null },
                label = { Text("Area") },
                placeholder = { Text("e.g. Berlin, Germany") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use my area")
                }
                Button(onClick = {
                    val query = areaNameDraft.trim()
                    if (query.isEmpty()) {
                        viewModel.coarseGeohash = null
                        areaStatus = null
                        return@Button
                    }
                    scope.launch {
                        resolvingArea = true
                        val geohash = AreaLookup.geohashFor(context, query, Geohash.precisionForRadiusKm(radiusKm))
                        if (geohash != null) {
                            viewModel.coarseGeohash = geohash
                            areaStatus = null
                        } else {
                            areaStatus = "Couldn't find that place - try a nearby city name."
                        }
                        resolvingArea = false
                    }
                }) { Text("Save area") }
            }
            if (resolvingArea) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Looking that up…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (locationDenied) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Location permission denied - type your area's name instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (areaStatus != null) {
                Spacer(Modifier.height(4.dp))
                Text(areaStatus!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            Text("Search radius: ${RADIUS_PRESETS.firstOrNull { it.first == radiusKm }?.second ?: "${radiusKm.toInt()} km"}")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RADIUS_PRESETS.forEach { (km, label) ->
                    FilterChip(
                        selected = km == radiusKm,
                        onClick = { radiusKm = km; viewModel.searchRadiusKm = km },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Bootstrap/relay nodes, one multiaddr per line - empty by default, since no single " +
                    "party runs one for everyone (see p2p-go/README.md). Wide-range discovery can't " +
                    "find anyone until at least one is set here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bootstrapDraft,
                onValueChange = { bootstrapDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Bootstrap/relay multiaddrs") },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.bootstrapNodes = bootstrapDraft.lines().map { it.trim() }.filter { it.isNotEmpty() } }) {
                Text("Save nodes")
            }
        }
    }
}
