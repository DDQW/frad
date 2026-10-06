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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.frad.chat.R
import app.frad.chat.chat.ChatMessage
import app.frad.chat.chat.MAX_MESSAGE_CHARS
import app.frad.chat.chat.MessageKind
import app.frad.chat.media.AudioRecorder
import app.frad.chat.media.CaptureFiles
import app.frad.chat.profile.Gender
import app.frad.chat.profile.Interest
import app.frad.chat.profile.Profile
import app.frad.chat.safety.Nudges
import app.frad.chat.chat.FileOffer
import app.frad.chat.chat.PhotoSwap
import app.frad.chat.ui.theme.fradExtraColors
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatContent(
    remotePeerId: String,
    remotePseudonym: String,
    remoteGender: Gender?,
    remoteAge: Int?,
    remoteBio: String,
    remotePhoto: ByteArray?,
    messages: List<ChatMessage>,
    peerTyping: Boolean,
    safetyNumber: String,
    alreadySaved: Boolean,
    fileTransferAvailable: Boolean,
    transferStatus: String?,
    /** 0..1 once known; null shows an indeterminate bar. */
    transferProgress: Float?,
    onCancelTransfer: () -> Unit,
    /** The peer asks to send this; [onAnswerIncomingFile] says yes or no. */
    incomingFile: FileOffer?,
    onAnswerIncomingFile: (accept: Boolean) -> Unit,
    remoteInterests: Set<Interest>,
    myInterests: Set<Interest>,
    /** They keep their photo back until both agree to swap (see ChatEnvelope.PhotoRequest). */
    remotePhotoHidden: Boolean,
    photoSwap: PhotoSwap?,
    onRequestPhotoSwap: () -> Unit,
    onAnswerPhotoSwap: (accept: Boolean) -> Unit,
    errorMessage: String?,
    onDismissError: () -> Unit,
    onSend: (String) -> Unit,
    onTyping: () -> Unit,
    /** Sends the file at the uri; the callback runs once it has been read (temp files can go then). */
    onSendFile: (Uri, () -> Unit) -> Unit,
    onLeave: () -> Unit,
    onBlock: () -> Unit,
    onReport: (reason: String) -> Unit,
    onSaveContact: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var showMoreMenu by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var reportDialog by remember { mutableStateOf(false) }
    var showSafetyNumber by remember { mutableStateOf(false) }
    var confirmSensitive by remember { mutableStateOf<Set<Nudges.Outgoing>>(emptySet()) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSendFile(uri) {}
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
            if (success) onSendFile(uri) { file.delete() } else file.delete()
        }
    }
    val captureVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (file != null) {
            val uri = CaptureFiles.uriFor(context, file)
            CaptureFiles.revokeAccess(context, uri)
            if (success) onSendFile(uri) { file.delete() } else file.delete()
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
            onSendFile(CaptureFiles.uriFor(context, file)) { file.delete() }
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
                            if (peerTyping) stringResource(R.string.chat_typing) else genderAgeLine(remoteGender, remoteAge),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (peerTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_more))
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_verify_safety_number)) },
                                leadingIcon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                                onClick = { showMoreMenu = false; showSafetyNumber = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_block_ellipsis)) },
                                leadingIcon = { Icon(Icons.Default.Block, contentDescription = null) },
                                onClick = { showMoreMenu = false; confirmBlock = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_report_ellipsis)) },
                                leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) },
                                onClick = { showMoreMenu = false; reportDialog = true },
                            )
                        }
                    }
                    IconButton(onClick = onLeave) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_leave))
                    }
                }
                if (remoteBio.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(remoteBio, style = MaterialTheme.typography.bodySmall)
                }
                if (remoteInterests.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    // Shared interests first and starred.
                    Text(
                        remoteInterests.sortedBy { it !in myInterests }.joinToString("  ·  ") {
                            if (it in myInterests) "★ ${it.label}" else it.label
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (remotePhotoHidden) {
                        AssistChip(
                            onClick = onRequestPhotoSwap,
                            enabled = photoSwap == null,
                            label = {
                                Text(
                                    if (photoSwap == PhotoSwap.WE_ASKED) {
                                        stringResource(R.string.chat_photo_swap_asked)
                                    } else {
                                        stringResource(R.string.chat_swap_photos)
                                    },
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = onSaveContact,
                        enabled = !alreadySaved,
                        label = { Text(if (alreadySaved) stringResource(R.string.chat_saved) else stringResource(R.string.chat_save_contact)) },
                        leadingIcon = {
                            Icon(if (alreadySaved) Icons.Default.Person else Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                    AssistChip(
                        onClick = { confirmBlock = true },
                        label = { Text(stringResource(R.string.chat_block)) },
                        leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error, leadingIconContentColor = MaterialTheme.colorScheme.error),
                    )
                }
            }
        }
        HorizontalDivider()

        if (messages.isEmpty()) {
            // An empty chat: a few openers, from what both picked (see Interest.icebreakers).
            val openers = remember(remoteInterests, myInterests) { Interest.icebreakers(remoteInterests intersect myInterests) }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                Text(stringResource(R.string.chat_openers_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                openers.forEach { opener ->
                    AssistChip(onClick = { draft = opener }, label = { Text(opener) })
                }
            }
        } else {
            // Received pictures from someone who isn't a contact stay pixelated until tapped.
            MessageList(messages, modifier = Modifier.weight(1f).fillMaxWidth(), veilTheirImages = !alreadySaved)
        }

        if (confirmBlock) {
            AlertDialog(
                onDismissRequest = { confirmBlock = false },
                title = { Text(stringResource(R.string.chat_block_title, Profile.displayName(remotePseudonym, remotePeerId))) },
                text = { Text(stringResource(R.string.chat_block_body)) },
                confirmButton = { TextButton(onClick = { confirmBlock = false; onBlock() }) { Text(stringResource(R.string.chat_block)) } },
                dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.chat_cancel)) } },
            )
        }
        if (reportDialog) {
            ReportDialog(onDismiss = { reportDialog = false }, onReport = { reason -> reportDialog = false; onReport(reason) })
        }
        if (confirmSensitive.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { confirmSensitive = emptySet() },
                title = { Text(stringResource(R.string.chat_share_title)) },
                text = {
                    // "a and b and c", with the localized "and".
                    val found = confirmSensitive.map { it.label }
                        .reduce { acc, label -> stringResource(R.string.chat_join_and, acc, label) }
                    Text(stringResource(R.string.chat_share_body, found, remotePseudonym))
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmSensitive = emptySet()
                        onSend(draft)
                        draft = ""
                    }) { Text(stringResource(R.string.chat_send_anyway)) }
                },
                dismissButton = { TextButton(onClick = { confirmSensitive = emptySet() }) { Text(stringResource(R.string.chat_edit)) } },
            )
        }
        if (showSafetyNumber) {
            AlertDialog(
                onDismissRequest = { showSafetyNumber = false },
                title = { Text(stringResource(R.string.chat_safety_number_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.chat_safety_number_body, remotePseudonym),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(safetyNumber, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                    }
                },
                confirmButton = { TextButton(onClick = { showSafetyNumber = false }) { Text(stringResource(R.string.chat_close)) } },
            )
        }

        if (photoSwap == PhotoSwap.THEY_ASKED) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.chat_photo_swap_request, Profile.displayName(remotePseudonym, remotePeerId)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onAnswerPhotoSwap(false) }) { Text(stringResource(R.string.chat_no_thanks)) }
                        TextButton(onClick = { onAnswerPhotoSwap(true) }) { Text(stringResource(R.string.chat_swap)) }
                    }
                }
            }
        }
        if (incomingFile != null) {
            IncomingFileCard(
                fromName = Profile.displayName(remotePseudonym, remotePeerId),
                offer = incomingFile,
                onAnswer = onAnswerIncomingFile,
            )
        }
        if (transferStatus != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        transferStatus + (transferProgress?.let { "  " + stringResource(R.string.chat_percent, (it * 100).toInt()) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancelTransfer) { Text(stringResource(R.string.chat_cancel)) }
                }
                if (transferProgress != null) {
                    LinearProgressIndicator(progress = { transferProgress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
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
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.chat_dismiss)) }
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
                                Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.chat_attach))
                            }
                            DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_file)) },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                    onClick = { showAttachMenu = false; filePicker.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_photo)) },
                                    leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                                    onClick = { showAttachMenu = false; startPhotoCapture() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_video)) },
                                    leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null) },
                                    onClick = { showAttachMenu = false; startVideoCapture() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_voice)) },
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
                        Text(stringResource(R.string.chat_recording_voice), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { cancelAudioRecording() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_cancel_recording))
                        }
                        IconButton(onClick = { sendAudioRecording() }, colors = IconButtonDefaults.filledIconButtonColors()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send_voice))
                        }
                    } else {
                        // Only shown close to the limit, so it doesn't take up room the rest of the time.
                        val lengthCounter: (@Composable () -> Unit)? =
                            if (draft.length > MAX_MESSAGE_CHARS * 9 / 10) {
                                { Text("${draft.length}/$MAX_MESSAGE_CHARS") }
                            } else {
                                null
                            }
                        val sendDraft = {
                            if (draft.isNotBlank()) {
                                // Contact details to someone who isn't a contact yet: ask first.
                                val sensitive = if (alreadySaved) emptySet() else Nudges.beforeSending(draft)
                                if (sensitive.isEmpty()) {
                                    onSend(draft)
                                    draft = ""
                                } else {
                                    confirmSensitive = sensitive
                                }
                            }
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { new ->
                                if (new.length > draft.length) onTyping()
                                draft = new.take(MAX_MESSAGE_CHARS)
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.chat_message_placeholder)) },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                            supportingText = lengthCounter,
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = sendDraft,
                            colors = IconButtonDefaults.filledIconButtonColors(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                        }
                    }
                }
                if (audioPermissionDenied) {
                    Text(
                        stringResource(R.string.chat_mic_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** The transcript, newest at the bottom: opens scrolled to the latest message and follows new
 *  ones as long as the user is already at the bottom (or sent the message themselves), with a
 *  date line wherever the day changes. */
@Composable
private fun IncomingFileCard(fromName: String, offer: FileOffer, onAnswer: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.chat_incoming_file, fromName, offer.fileName, readableSize(offer.sizeBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.chat_decline)) }
                TextButton(onClick = { onAnswer(true) }) { Text(stringResource(R.string.chat_accept)) }
            }
        }
    }
}

@Composable
private fun readableSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> stringResource(R.string.chat_size_mb, bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> stringResource(R.string.chat_size_kb, bytes / 1024)
    else -> pluralStringResource(R.plurals.chat_size_bytes, bytes.toInt(), bytes.toInt())
}

/** @param veilTheirImages show pictures the other person sent pixelated until tapped. */
@Composable
internal fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier, veilTheirImages: Boolean = false) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val transcript = remember(messages) { withDayLines(messages) }

    LaunchedEffect(Unit) {
        if (transcript.isNotEmpty()) listState.scrollToItem(transcript.lastIndex)
    }
    LaunchedEffect(transcript.size) {
        if (transcript.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val atBottom = lastVisible >= transcript.lastIndex - 2
        val mine = (transcript.last() as? TranscriptItem.Message)?.message?.fromMe == true
        if (atBottom || mine) listState.animateScrollToItem(transcript.lastIndex)
    }

    LazyColumn(state = listState, modifier = modifier, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(transcript) { item ->
            when (item) {
                is TranscriptItem.Day -> Text(
                    item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                is TranscriptItem.Message -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (item.message.fromMe) Arrangement.End else Arrangement.Start,
                ) {
                    MessageBubble(
                        message = item.message,
                        veiled = veilTheirImages && !item.message.fromMe,
                        onOpenFile = { openFile(context, item.message) },
                    )
                }
            }
        }
    }
}

private sealed interface TranscriptItem {
    data class Day(val label: String) : TranscriptItem
    data class Message(val message: ChatMessage) : TranscriptItem
}

private fun withDayLines(messages: List<ChatMessage>): List<TranscriptItem> {
    val zone = ZoneId.systemDefault()
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val result = mutableListOf<TranscriptItem>()
    var lastDay: LocalDate? = null
    for (message in messages) {
        val day = Instant.ofEpochMilli(message.atMillis).atZone(zone).toLocalDate()
        if (day != lastDay) {
            result += TranscriptItem.Day(dateFormat.format(day))
            lastDay = day
        }
        result += TranscriptItem.Message(message)
    }
    return result
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun timeOf(message: ChatMessage): String =
    timeFormat.format(Instant.ofEpochMilli(message.atMillis).atZone(ZoneId.systemDefault()))

@Composable
internal fun ReportDialog(onDismiss: () -> Unit, onReport: (String) -> Unit) {
    // The chosen reason is kept as shown (in the user's language) - it's a note for them, nobody else reads it.
    val reasons = listOf(
        stringResource(R.string.chat_report_reason_spam),
        stringResource(R.string.chat_report_reason_harassment),
        stringResource(R.string.chat_report_reason_sexual),
        stringResource(R.string.chat_report_reason_impersonation),
        stringResource(R.string.chat_report_reason_underage),
        stringResource(R.string.chat_report_reason_other),
    )
    // By position, so a language change while the dialog is open keeps the choice.
    var selected by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_report_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.chat_report_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                reasons.forEachIndexed { index, reason ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { selected = index },
                    ) {
                        RadioButton(selected = selected == index, onClick = { selected = index })
                        Text(reason)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let { onReport(reasons[it]) } }) {
                Text(stringResource(R.string.chat_report))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

@Composable
private fun MessageBubble(message: ChatMessage, veiled: Boolean, onOpenFile: () -> Unit) {
    val bubbleColor = if (message.fromMe) MaterialTheme.fradExtraColors.bubbleMine else MaterialTheme.fradExtraColors.bubbleTheirs
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (message.fromMe) 16.dp else 4.dp,
        bottomEnd = if (message.fromMe) 4.dp else 16.dp,
    )
    Surface(color = bubbleColor, shape = shape, modifier = Modifier.widthIn(max = 280.dp)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            when (message.kind) {
                MessageKind.TEXT -> {
                    Text(message.text)
                    // From someone who isn't a contact: point out the usual lures (see Nudges).
                    val warnings = remember(message.text, veiled) {
                        if (veiled) Nudges.onReceived(message.text) else emptySet()
                    }
                    if (warnings.isNotEmpty()) {
                        Text(
                            stringResource(R.string.chat_careful, warnings.joinToString(", ") { it.label }),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                MessageKind.FILE -> FileMessageContent(message, veiled = veiled, onOpen = onOpenFile)
            }
            Text(
                // Delivered = the peer's phone confirmed it arrived (see ChatEnvelope.Ack).
                timeOf(message) + if (message.fromMe && message.id != null) (if (message.delivered) "  ✓✓" else "  ✓") else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun FileMessageContent(message: ChatMessage, veiled: Boolean, onOpen: () -> Unit) {
    val path = message.localPath ?: return
    if (message.mimeType?.startsWith("image/") == true) {
        var revealed by remember(path) { mutableStateOf(!veiled) }
        // Up to 25 MB from a stranger: decoded subsampled to the preview size, off the main thread.
        // While veiled only a 12-pixel-wide copy is kept and blown up blocky - shapes and colours,
        // nothing more, the same on every Android version (unlike a blur).
        val bitmap = produceState<ImageBitmap?>(initialValue = null, path, revealed) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val decoded = decodeSampled(path, IMAGE_PREVIEW_MAX_PIXELS) ?: return@runCatching null
                    (if (revealed) decoded else pixelated(decoded)).asImageBitmap()
                }.getOrNull()
            }
        }.value
        Column {
            Text(message.fileName ?: stringResource(R.string.chat_image), style = MaterialTheme.typography.bodySmall)
            if (bitmap != null) {
                Spacer(Modifier.height(4.dp))
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = message.fileName,
                        contentScale = ContentScale.Crop,
                        filterQuality = if (revealed) FilterQuality.Low else FilterQuality.None,
                        modifier = Modifier
                            .size(160.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { if (revealed) onOpen() else revealed = true },
                    )
                    if (!revealed) {
                        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.chat_tap_to_view), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(
                    R.string.chat_file_with_size,
                    message.fileName.orEmpty(),
                    stringResource(R.string.chat_size_kb, message.sizeBytes / 1024),
                ),
            )
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onOpen) { Text(stringResource(R.string.chat_open)) }
        }
    }
}

@Composable
private fun genderAgeLine(gender: Gender?, age: Int?): String {
    val genderLabel = when (gender) {
        Gender.MALE -> stringResource(R.string.chat_gender_male)
        Gender.FEMALE -> stringResource(R.string.chat_gender_female)
        null -> null
    }
    return listOfNotNull(genderLabel, age?.toString()).joinToString(", ")
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
