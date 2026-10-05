package app.frad.chat.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.MAX_MESSAGE_CHARS
import app.frad.chat.chat.MessageKind
import app.frad.chat.media.AudioRecorder
import app.frad.chat.media.CaptureFiles
import app.frad.chat.profile.Gender
import app.frad.chat.profile.Profile
import app.frad.chat.ui.theme.fradExtraColors

@Composable
internal fun ChatContent(
    remotePeerId: String,
    remotePseudonym: String,
    remoteGender: Gender,
    remoteAge: Int?,
    remoteBio: String,
    remotePhoto: ByteArray?,
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
                    Avatar(label = remotePseudonym, photoBytes = remotePhoto, size = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            Profile.displayName(remotePseudonym, remotePeerId),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            genderAgeLine(remoteGender, remoteAge),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onLeave) {
                        Icon(Icons.Default.Close, contentDescription = "Leave chat")
                    }
                }
                if (remoteBio.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(remoteBio, style = MaterialTheme.typography.bodySmall)
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
                            onValueChange = { draft = it.take(MAX_MESSAGE_CHARS) },
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

@Composable
internal fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
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
        // Up to 25 MB from a stranger: decoded subsampled to the preview size, off the main thread.
        val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
            value = withContext(Dispatchers.IO) { runCatching { decodeSampled(path, IMAGE_PREVIEW_MAX_PIXELS)?.asImageBitmap() }.getOrNull() }
        }
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

private fun genderAgeLine(gender: Gender, age: Int?): String {
    val genderLabel = if (gender == Gender.MALE) "Male" else "Female"
    return if (age != null) "$genderLabel, $age" else genderLabel
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

/** Large enough for the 160dp inline image preview on a high-density screen. */
private const val IMAGE_PREVIEW_MAX_PIXELS = 480
