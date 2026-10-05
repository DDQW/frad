package app.frad.chat.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import app.frad.chat.profile.Profile
import app.frad.chat.safety.BlockEntry

@Composable
internal fun BlockedTab(viewModel: ChatViewModel) {
    var blocked by remember { mutableStateOf(viewModel.blockedEntries()) }

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
                            IconButton(onClick = {
                                viewModel.unblock(entry)
                                blocked = viewModel.blockedEntries()
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

@Composable
private fun ColumnScope.BlockEntryText(entry: BlockEntry) {
    Text(Profile.displayName(entry.pseudonym ?: "Unknown", entry.key), fontWeight = FontWeight.SemiBold)
    val details = listOfNotNull(
        if (entry.blockedAtMillis > 0) "Blocked " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.blockedAtMillis)) else null,
        entry.reason?.let { "reported: $it" },
    )
    if (details.isNotEmpty()) {
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
