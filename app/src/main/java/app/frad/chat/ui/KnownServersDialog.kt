package app.frad.chat.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.frad.chat.R
import app.frad.chat.wideradius.NodeDirectory

/** Every server the phone knows (see NodeDirectory), where it came from, and a way to fetch the
 *  official list right now. */
@Composable
internal fun KnownServersDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    var servers by remember { mutableStateOf(viewModel.knownServers()) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_title, servers.size)) },
        text = {
            Column {
                Text(stringResource(R.string.servers_explainer), style = MaterialTheme.typography.bodySmall)
                if (refreshFailed) {
                    Text(
                        stringResource(R.string.servers_refresh_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (servers.isEmpty()) {
                    Text(stringResource(R.string.servers_none))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(servers, key = { it.address }) { server ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(sourceLabel(server), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                                SelectionContainer {
                                    Text(server.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.profile_close)) } },
        dismissButton = {
            TextButton(
                enabled = !refreshing,
                onClick = {
                    refreshing = true
                    scope.launch {
                        refreshFailed = !viewModel.refreshOfficialServers()
                        servers = viewModel.knownServers()
                        refreshing = false
                    }
                },
            ) { Text(stringResource(if (refreshing) R.string.servers_refreshing else R.string.servers_refresh)) }
        },
    )
}

@Composable
private fun sourceLabel(server: NodeDirectory.KnownNode): String {
    val source = stringResource(
        when (server.source) {
            NodeDirectory.Source.YOURS -> R.string.servers_source_yours
            NodeDirectory.Source.OFFICIAL -> R.string.servers_source_official
            NodeDirectory.Source.LEARNED -> R.string.servers_source_learned
        },
    )
    val heardAt = server.heardAtMillis ?: return source
    val ago = DateUtils.getRelativeTimeSpanString(heardAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return "$source · $ago"
}
