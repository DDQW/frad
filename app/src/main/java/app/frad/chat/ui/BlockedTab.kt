package app.frad.chat.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import app.frad.chat.R
import app.frad.chat.profile.Profile
import app.frad.chat.safety.BlockEntry

@Composable
internal fun BlockedTab(viewModel: ChatViewModel) {
    var blocked by remember { mutableStateOf(viewModel.blockedEntries()) }
    var openReport by remember { mutableStateOf<Pair<BlockEntry, String>?>(null) }
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.misc_blocked_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
        if (blocked.isEmpty()) {
            EmptyState(Icons.Default.Block, stringResource(R.string.misc_blocked_empty))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(blocked, key = { it.key }) { entry ->
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
                            Column(modifier = Modifier.weight(1f)) { BlockEntryText(entry) }
                            val transcript = remember(entry.key) { viewModel.reportTranscript(entry) }
                            if (transcript != null) {
                                IconButton(onClick = { openReport = entry to transcript }) {
                                    Icon(Icons.Default.Description, contentDescription = stringResource(R.string.misc_blocked_show_report))
                                }
                            }
                            IconButton(onClick = {
                                viewModel.unblock(entry)
                                blocked = viewModel.blockedEntries()
                            }) {
                                Icon(Icons.Default.LockOpen, contentDescription = stringResource(R.string.misc_blocked_unblock))
                            }
                        }
                    }
                }
            }
        }
    }

    openReport?.let { (entry, transcript) ->
        AlertDialog(
            onDismissRequest = { openReport = null },
            title = {
                val name = Profile.displayName(entry.pseudonym ?: stringResource(R.string.misc_blocked_unknown_pseudonym), entry.key)
                Text(stringResource(R.string.misc_blocked_report_title, name))
            },
            text = {
                SelectionContainer {
                    Text(
                        transcript,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                val subject = stringResource(R.string.misc_blocked_report_subject)
                val chooserTitle = stringResource(R.string.misc_blocked_export_report_chooser)
                TextButton(onClick = {
                    // Only ever leaves the phone this way - the user picks where it goes.
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, subject)
                        .putExtra(Intent.EXTRA_TEXT, transcript)
                    context.startActivity(Intent.createChooser(send, chooserTitle))
                }) { Text(stringResource(R.string.misc_blocked_export)) }
            },
            dismissButton = { TextButton(onClick = { openReport = null }) { Text(stringResource(R.string.misc_blocked_close)) } },
        )
    }
}

@Composable
private fun ColumnScope.BlockEntryText(entry: BlockEntry) {
    Text(Profile.displayName(entry.pseudonym ?: stringResource(R.string.misc_blocked_unknown_pseudonym), entry.key), fontWeight = FontWeight.SemiBold)
    val blockedOn = if (entry.blockedAtMillis > 0) {
        stringResource(R.string.misc_blocked_on_date, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.blockedAtMillis)))
    } else {
        null
    }
    val reported = entry.reason?.let { stringResource(R.string.misc_blocked_reported_reason, it) }
    val details = listOfNotNull(blockedOn, reported)
    if (details.isNotEmpty()) {
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
