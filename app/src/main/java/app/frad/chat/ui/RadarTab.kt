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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.frad.chat.R
import app.frad.chat.chat.ChatUiState
import app.frad.chat.profile.Profile

@Composable
internal fun RadarTab(state: ChatUiState, viewModel: ChatViewModel) {
    val endedChat by viewModel.endedChat.collectAsState()
    if (state !is ChatUiState.Chatting) {
        endedChat?.let { ended ->
            Column(modifier = Modifier.fillMaxSize()) {
                EndedChatCard(
                    ended = ended,
                    alreadySaved = viewModel.isContactSaved(ended.peerId),
                    onSave = viewModel::saveEndedChat,
                    onBlock = { viewModel.blockEndedChat() },
                    onReport = { reason -> viewModel.blockEndedChat(reason) },
                    onDismiss = viewModel::dismissEndedChat,
                )
                Box(modifier = Modifier.weight(1f)) { RadarStateContent(state, viewModel) }
            }
            return
        }
    }
    RadarStateContent(state, viewModel)
}

/** "Your chat with X ended" with what can still be done about X - see [ChatViewModel.endedChat]. */
@Composable
private fun EndedChatCard(
    ended: ChatViewModel.EndedChat,
    alreadySaved: Boolean,
    onSave: () -> Unit,
    onBlock: () -> Unit,
    onReport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmBlock by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val name = Profile.displayName(ended.pseudonym, ended.peerId)
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.radar_ended_chat_with, name), style = MaterialTheme.typography.bodyMedium)
            Row {
                if (!alreadySaved) TextButton(onClick = onSave) { Text(stringResource(R.string.radar_save_contact)) }
                TextButton(onClick = { confirmBlock = true }) { Text(stringResource(R.string.radar_block), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { reporting = true }) { Text(stringResource(R.string.radar_report_ellipsis)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.radar_dismiss)) }
            }
        }
    }
    if (confirmBlock) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(stringResource(R.string.radar_block_title, name)) },
            text = { Text(stringResource(R.string.radar_block_body)) },
            confirmButton = { TextButton(onClick = { confirmBlock = false; onBlock() }) { Text(stringResource(R.string.radar_block)) } },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.radar_cancel)) } },
        )
    }
    if (reporting) {
        ReportDialog(onDismiss = { reporting = false }, onReport = { reason -> reporting = false; onReport(reason) })
    }
}

@Composable
private fun RadarStateContent(state: ChatUiState, viewModel: ChatViewModel) {
    when (val current = state) {
        ChatUiState.Idle -> {
            val mode by viewModel.mode.collectAsState()
            IdleContent(
                mode = mode,
                wideRangeAvailable = viewModel.wideRangeAvailable,
                wideRangeMissing = viewModel.wideRangeSetupMissing(),
                onModeChange = { viewModel.setMode(it) },
                onStart = { viewModel.setBrowsing(true) },
            )
        }
        is ChatUiState.Browsing -> BrowsingContent(
            peers = current.nearbyPeers,
            warning = current.warning,
            visibleUntilMillis = viewModel.visibleUntilMillis.collectAsState().value,
            onVisibleFor = viewModel::setVisibleFor,
            onStop = { viewModel.setBrowsing(false) },
            onRandomChat = { viewModel.requestRandomChat() },
        )
        is ChatUiState.Paused -> PausedContent(reason = current.reason, onStop = { viewModel.setBrowsing(false) })
        is ChatUiState.Connecting -> CenteredStatus(message = stringResource(R.string.radar_connecting), onCancel = { viewModel.endChat() })
        ChatUiState.Handshaking -> CenteredStatus(message = stringResource(R.string.radar_handshaking), onCancel = { viewModel.endChat() })
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
                remoteInterests = current.remoteInterests,
                remotePhotoHidden = current.remotePhotoHidden,
                photoSwap = current.photoSwap,
                onRequestPhotoSwap = { viewModel.requestPhotoSwap() },
                onAnswerPhotoSwap = { viewModel.answerPhotoSwap(it) },
                myInterests = viewModel.interests,
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
                TextButton(onClick = onCancel) { Text(stringResource(R.string.radar_cancel)) }
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
            Button(onClick = onDismiss) { Text(stringResource(R.string.radar_ok)) }
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
            ) { Text(stringResource(R.string.radar_turn_on_bluetooth)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onStop) { Text(stringResource(R.string.radar_stop_being_visible)) }
        }
    }
}

@Composable
private fun IdleContent(
    mode: ChatMode,
    wideRangeAvailable: Boolean,
    /** What wide-range still needs before it can find anyone (null: ready). */
    wideRangeMissing: String?,
    onModeChange: (ChatMode) -> Unit,
    onStart: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.radar_find_people), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == ChatMode.LOCAL_BLE,
                    onClick = { onModeChange(ChatMode.LOCAL_BLE) },
                    label = { Text(stringResource(R.string.radar_mode_nearby)) },
                    leadingIcon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = mode == ChatMode.WIDE_RANGE,
                    onClick = { onModeChange(ChatMode.WIDE_RANGE) },
                    enabled = wideRangeAvailable,
                    label = { Text(stringResource(R.string.radar_mode_wide_range)) },
                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (!wideRangeAvailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.radar_wide_range_not_built),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            val missing = wideRangeMissing.takeIf { mode == ChatMode.WIDE_RANGE }
            Text(
                missing ?: stringResource(R.string.radar_not_visible),
                style = MaterialTheme.typography.bodyMedium,
                color = if (missing != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onStart, enabled = missing == null, modifier = Modifier.fillMaxWidth(0.8f)) { Text(stringResource(R.string.radar_become_visible)) }
        }
    }
}

@Composable
private fun BrowsingContent(
    peers: List<app.frad.chat.pairing.NearbyPeer>,
    warning: String?,
    visibleUntilMillis: Long,
    onVisibleFor: (minutes: Int?) -> Unit,
    onStop: () -> Unit,
    onRandomChat: () -> Unit,
) {
    val peerCount = peers.size
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            RadarView(peers)
            Spacer(Modifier.height(20.dp))
            Text(
                if (peerCount == 0) stringResource(R.string.radar_looking_for_people)
                else pluralStringResource(R.plurals.radar_people_found, peerCount, peerCount),
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
                Text(stringResource(R.string.radar_chat_with_someone_nearby))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onStop) { Text(stringResource(R.string.radar_stop_being_visible)) }
            Spacer(Modifier.height(16.dp))
            VisibilityLimit(visibleUntilMillis, onVisibleFor)
        }
    }
}

/** Minutes the visibility chips offer; null is "until I stop". */
private val VISIBILITY_LIMITS = listOf(null, 30, 60, 180)

/** "Stay visible: until I stop / 30 min / 1 h / 3 h" - see [ChatViewModel.setVisibleFor]. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun VisibilityLimit(visibleUntilMillis: Long, onVisibleFor: (Int?) -> Unit) {
    // Which chip was picked last in this screen; the deadline itself is what counts.
    var picked by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(visibleUntilMillis) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val remainingMinutes = if (visibleUntilMillis > 0) ((visibleUntilMillis - now) / 60_000).coerceAtLeast(0) else null
    Text(
        if (remainingMinutes != null) stringResource(R.string.radar_visible_remaining, remainingMinutes)
        else stringResource(R.string.radar_stay_visible),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VISIBILITY_LIMITS.forEach { minutes ->
            val label = when {
                minutes == null -> stringResource(R.string.radar_visible_until_stop)
                minutes < 60 -> stringResource(R.string.radar_visible_minutes, minutes)
                else -> stringResource(R.string.radar_visible_hours, minutes / 60)
            }
            FilterChip(
                selected = if (visibleUntilMillis == 0L) minutes == null else picked == minutes,
                onClick = { picked = minutes; onVisibleFor(minutes) },
                label = { Text(label) },
            )
        }
    }
}
