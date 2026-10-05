package app.frad.chat.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.frad.chat.profile.Gender
import app.frad.chat.profile.Profile
import app.frad.chat.profile.ProfilePhoto
import app.frad.chat.wideradius.AreaLookup
import app.frad.chat.wideradius.Geohash

private val RADIUS_PRESETS = listOf(20.0 to "Neighborhood", 75.0 to "City", 600.0 to "Region", 20_000.0 to "Worldwide")

@Composable
internal fun ProfileTab(viewModel: ChatViewModel) {
    var draft by remember { mutableStateOf(viewModel.myPseudonym) }
    var genderDraft by remember { mutableStateOf(viewModel.gender) }
    var ageDraft by remember { mutableStateOf(viewModel.age?.toString() ?: "") }
    var shareAgeDraft by remember { mutableStateOf(viewModel.shareAge) }
    var bioDraft by remember { mutableStateOf(viewModel.bio) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var alwaysVisible by remember { mutableStateOf(viewModel.alwaysVisible) }
    var radiusKm by remember { mutableStateOf(viewModel.searchRadiusKm) }
    var bootstrapDraft by remember { mutableStateOf(viewModel.bootstrapNodes.joinToString("\n")) }
    var locationDenied by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var areaNameDraft by remember { mutableStateOf("") }
    var areaStatus by remember { mutableStateOf<String?>(null) }
    var resolvingArea by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        photoBytes = ProfilePhoto.bytesOrNull(context)
        // The stored area is a geohash (see Profile.coarseGeohash) - show it as a place name
        // instead of that cryptic code by reverse-geocoding it once when this screen first appears.
        val saved = viewModel.coarseGeohash ?: return@LaunchedEffect
        areaNameDraft = AreaLookup.nameFor(context, saved) ?: saved
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && ProfilePhoto.store(context, uri)) {
            photoBytes = ProfilePhoto.bytesOrNull(context)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            locationDenied = true
            return@rememberLauncherForActivityResult
        }
        locationDenied = false
        scope.launch {
            resolvingArea = true
            areaStatus = "Getting your area…"
            val geohash = viewModel.useCurrentAreaAsGeohash()
            if (geohash == null) {
                areaStatus = "Couldn't get a location fix - check that location is switched on, then try again."
            } else {
                areaNameDraft = AreaLookup.nameFor(context, geohash) ?: geohash
                areaStatus = null
            }
            resolvingArea = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionCard(title = "Your profile") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clickable {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                ) {
                    Avatar(label = draft, photoBytes = photoBytes, size = 56.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Others see you as", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Profile.displayName(draft, viewModel.myPeerId), fontWeight = FontWeight.SemiBold)
                    Row {
                        TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(if (photoBytes == null) "Add photo" else "Change photo")
                        }
                        if (photoBytes != null) {
                            TextButton(onClick = { ProfilePhoto.clear(context); photoBytes = null }) { Text("Remove") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Profile.MAX_LENGTH) },
                label = { Text("Pseudonym") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Gender", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = genderDraft == Gender.MALE, onClick = { genderDraft = Gender.MALE }, label = { Text("Male") })
                FilterChip(selected = genderDraft == Gender.FEMALE, onClick = { genderDraft = Gender.FEMALE }, label = { Text("Female") })
            }
            Spacer(Modifier.height(12.dp))
            val ageValid = (ageDraft.toIntOrNull() ?: 0) in Profile.MIN_AGE..Profile.MAX_AGE
            OutlinedTextField(
                value = ageDraft,
                onValueChange = { ageDraft = it.filter(Char::isDigit).take(3) },
                label = { Text("Age") },
                isError = !ageValid,
                supportingText = { if (!ageValid) Text("FRAD is for people aged ${Profile.MIN_AGE} and over.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show my age to matches", modifier = Modifier.weight(1f))
                Switch(checked = shareAgeDraft, onCheckedChange = { shareAgeDraft = it })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bioDraft,
                onValueChange = { bioDraft = it.take(Profile.MAX_BIO_LENGTH) },
                label = { Text("Short description (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${bioDraft.length}/${Profile.MAX_BIO_LENGTH}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = ageValid && genderDraft != null,
                onClick = {
                    viewModel.myPseudonym = draft
                    viewModel.gender = genderDraft
                    viewModel.age = ageDraft.toIntOrNull()
                    viewModel.shareAge = shareAgeDraft
                    viewModel.bio = bioDraft
                },
            ) { Text("Save") }
            Spacer(Modifier.height(12.dp))
            Text(
                "The part after # is unique to your device, so people who picked the same pseudonym as you stay distinguishable. " +
                    "Your photo, gender, age and description are shown automatically to whoever you match with - the photo is " +
                    "kept deliberately tiny/low-quality so it doesn't slow down connecting.",
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
