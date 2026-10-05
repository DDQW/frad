package app.frad.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import app.frad.chat.chat.ChatMessage
import app.frad.chat.contacts.Contact
import app.frad.chat.profile.Profile

@Composable
internal fun ContactsTab(viewModel: ChatViewModel) {
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
