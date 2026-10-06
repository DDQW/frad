package app.frad.chat.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.frad.chat.AppLock
import app.frad.chat.R
import app.frad.chat.qr.QrCode
import app.frad.chat.profile.Gender
import app.frad.chat.profile.Interest
import app.frad.chat.profile.Profile
import app.frad.chat.profile.ProfilePhoto
import app.frad.chat.wideradius.AreaLookup
import app.frad.chat.wideradius.Geohash

private val RADIUS_PRESETS = listOf(
    20.0 to R.string.profile_radius_neighborhood,
    75.0 to R.string.profile_radius_city,
    600.0 to R.string.profile_radius_region,
    20_000.0 to R.string.profile_radius_worldwide,
)
private val RETENTION_PRESETS = listOf(
    0 to R.string.profile_retention_keep,
    1 to R.string.profile_retention_1_day,
    7 to R.string.profile_retention_1_week,
    30 to R.string.profile_retention_1_month,
)

// FlowRow: chip and button rows wrap on narrow screens or with large system fonts.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileTab(viewModel: ChatViewModel) {
    var draft by remember { mutableStateOf(viewModel.myPseudonym) }
    var genderDraft by remember { mutableStateOf(viewModel.gender) }
    var ageDraft by remember { mutableStateOf(viewModel.age?.toString() ?: "") }
    var shareAgeDraft by remember { mutableStateOf(viewModel.shareAge) }
    var bioDraft by remember { mutableStateOf(viewModel.bio) }
    var interestsDraft by remember { mutableStateOf(viewModel.interests) }
    var photoOnRequestDraft by remember { mutableStateOf(viewModel.photoOnRequest) }
    var photoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var alwaysVisible by remember { mutableStateOf(viewModel.alwaysVisible) }
    var radiusKm by remember { mutableStateOf(viewModel.searchRadiusKm) }
    val savedNodes by viewModel.savedBootstrapNodes.collectAsState()
    var bootstrapDraft by remember { mutableStateOf(savedNodes.joinToString("\n")) }
    LaunchedEffect(savedNodes) { bootstrapDraft = savedNodes.joinToString("\n") }
    var relayOnly by remember { mutableStateOf(viewModel.wideRangeRelayOnly) }
    var usePublicNodes by remember { mutableStateOf(viewModel.usePublicNodes) }
    var appLock by remember { mutableStateOf(viewModel.appLock) }
    var retentionDays by remember { mutableStateOf(viewModel.historyRetentionDays) }
    var confirmWipe by remember { mutableStateOf(false) }
    var showNodeQr by remember { mutableStateOf(false) }
    var locationDenied by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var areaNameDraft by remember { mutableStateOf("") }
    // String resource id of the area lookup's status/error line, or null when there is none.
    var areaStatus by remember { mutableStateOf<Int?>(null) }
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
            areaStatus = R.string.profile_area_getting
            val geohash = viewModel.useCurrentAreaAsGeohash()
            if (geohash == null) {
                areaStatus = R.string.profile_area_no_fix
            } else {
                areaNameDraft = AreaLookup.nameFor(context, geohash) ?: geohash
                areaStatus = null
            }
            resolvingArea = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionCard(title = stringResource(R.string.profile_section_your_profile)) {
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
                    Text(stringResource(R.string.profile_others_see_you_as), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Profile.displayName(draft, viewModel.myPeerId), fontWeight = FontWeight.SemiBold)
                    Row {
                        TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(stringResource(if (photoBytes == null) R.string.profile_add_photo else R.string.profile_change_photo))
                        }
                        if (photoBytes != null) {
                            TextButton(onClick = { ProfilePhoto.clear(context); photoBytes = null }) { Text(stringResource(R.string.profile_remove_photo)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Profile.MAX_LENGTH) },
                label = { Text(stringResource(R.string.profile_pseudonym_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.profile_gender_label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = genderDraft == Gender.MALE, onClick = { genderDraft = Gender.MALE }, label = { Text(stringResource(R.string.profile_gender_male)) })
                FilterChip(selected = genderDraft == Gender.FEMALE, onClick = { genderDraft = Gender.FEMALE }, label = { Text(stringResource(R.string.profile_gender_female)) })
            }
            Spacer(Modifier.height(12.dp))
            val ageValid = (ageDraft.toIntOrNull() ?: 0) in Profile.MIN_AGE..Profile.MAX_AGE
            OutlinedTextField(
                value = ageDraft,
                onValueChange = { ageDraft = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.profile_age_label)) },
                isError = !ageValid,
                supportingText = { if (!ageValid) Text(stringResource(R.string.profile_age_too_young, Profile.MIN_AGE)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profile_share_age), modifier = Modifier.weight(1f))
                Switch(checked = shareAgeDraft, onCheckedChange = { shareAgeDraft = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profile_photo_on_request), modifier = Modifier.weight(1f))
                Switch(checked = photoOnRequestDraft, onCheckedChange = { photoOnRequestDraft = it })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bioDraft,
                onValueChange = { bioDraft = it.take(Profile.MAX_BIO_LENGTH) },
                label = { Text(stringResource(R.string.profile_bio_label)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${bioDraft.length}/${Profile.MAX_BIO_LENGTH}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.profile_interests_hint, Interest.MAX_PER_PROFILE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Interest.entries.forEach { interest ->
                    val selected = interest in interestsDraft
                    FilterChip(
                        selected = selected,
                        enabled = selected || interestsDraft.size < Interest.MAX_PER_PROFILE,
                        onClick = { interestsDraft = if (selected) interestsDraft - interest else interestsDraft + interest },
                        label = { Text(stringResource(interest.labelRes)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = ageValid && genderDraft != null,
                onClick = {
                    viewModel.myPseudonym = draft
                    viewModel.gender = genderDraft
                    viewModel.age = ageDraft.toIntOrNull()
                    viewModel.shareAge = shareAgeDraft
                    viewModel.bio = bioDraft
                    viewModel.interests = interestsDraft
                    viewModel.photoOnRequest = photoOnRequestDraft
                },
            ) { Text(stringResource(R.string.profile_save_button)) }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.profile_pseudonym_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.profile_section_visibility)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_always_visible), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.profile_always_visible_explainer),
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

        SectionCard(title = stringResource(R.string.profile_section_wide_range)) {
            Text(
                stringResource(R.string.profile_area_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = areaNameDraft,
                onValueChange = { areaNameDraft = it; areaStatus = null },
                label = { Text(stringResource(R.string.profile_area_label)) },
                placeholder = { Text(stringResource(R.string.profile_area_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.profile_use_my_area))
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
                            areaStatus = R.string.profile_area_not_found
                        }
                        resolvingArea = false
                    }
                }) { Text(stringResource(R.string.profile_save_area)) }
            }
            if (resolvingArea) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.profile_area_looking_up), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (locationDenied) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.profile_location_denied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val status = areaStatus
            if (status != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(16.dp))
            val radiusPreset = RADIUS_PRESETS.firstOrNull { it.first == radiusKm }
            val radiusLabel = if (radiusPreset != null) {
                stringResource(radiusPreset.second)
            } else {
                stringResource(R.string.profile_radius_km, radiusKm.toInt())
            }
            Text(stringResource(R.string.profile_search_radius, radiusLabel))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RADIUS_PRESETS.forEach { (km, labelRes) ->
                    FilterChip(
                        selected = km == radiusKm,
                        onClick = { radiusKm = km; viewModel.searchRadiusKm = km },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_use_public_servers), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.profile_public_servers_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = usePublicNodes, onCheckedChange = { usePublicNodes = it; viewModel.usePublicNodes = it })
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.profile_own_servers_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bootstrapDraft,
                onValueChange = { bootstrapDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.profile_bootstrap_label)) },
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.bootstrapNodes = bootstrapDraft.lines().map { it.trim() }.filter { it.isNotEmpty() } }) {
                    Text(stringResource(R.string.profile_save_nodes))
                }
                val shareLink = viewModel.nodeShareLink()
                val shareServersTitle = stringResource(R.string.profile_share_servers)
                OutlinedButton(
                    enabled = shareLink != null && savedNodes.isNotEmpty(),
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareLink)
                        context.startActivity(Intent.createChooser(send, shareServersTitle))
                    },
                ) { Text(shareServersTitle) }
                OutlinedButton(enabled = shareLink != null && savedNodes.isNotEmpty(), onClick = { showNodeQr = true }) { Text(stringResource(R.string.profile_qr_code_button)) }
            }
            Text(
                stringResource(R.string.profile_share_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_hide_ip), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.profile_hide_ip_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = relayOnly, onCheckedChange = { relayOnly = it; viewModel.wideRangeRelayOnly = it })
            }
        }

        SectionCard(title = stringResource(R.string.profile_section_privacy)) {
            val lockAvailable = remember { AppLock.available(context) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.profile_app_lock), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            if (lockAvailable) R.string.profile_app_lock_explainer else R.string.profile_app_lock_unavailable,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = appLock && lockAvailable,
                    enabled = lockAvailable,
                    onCheckedChange = { appLock = it; viewModel.appLock = it },
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.profile_retention_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.profile_retention_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RETENTION_PRESETS.forEach { (days, labelRes) ->
                    FilterChip(
                        selected = days == retentionDays,
                        onClick = { retentionDays = days; viewModel.historyRetentionDays = days },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.profile_delete_all_title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.profile_delete_all_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmWipe = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(R.string.profile_delete_everything)) }
        }
    }

    if (showNodeQr) {
        val link = viewModel.nodeShareLink()
        val qr = remember(link) { link?.let { runCatching { qrBitmap(QrCode.encode(it)).asImageBitmap() }.getOrNull() } }
        AlertDialog(
            onDismissRequest = { showNodeQr = false },
            title = { Text(stringResource(R.string.profile_node_qr_title)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = stringResource(R.string.profile_node_qr_description),
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.size(240.dp),
                        )
                    } else {
                        Text(stringResource(R.string.profile_node_qr_too_many))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.profile_node_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showNodeQr = false }) { Text(stringResource(R.string.profile_close)) } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.profile_wipe_confirm_title)) },
            text = { Text(stringResource(R.string.profile_wipe_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; viewModel.wipeEverything() }) {
                    Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(stringResource(R.string.profile_cancel)) } },
        )
    }
}
