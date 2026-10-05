package app.frad.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.frad.chat.profile.Gender
import app.frad.chat.profile.Profile

/**
 * First-run setup: what others will see (pseudonym, gender) and the age that decides who you can
 * be matched with. Shown before anything else - FRAD advertises nothing and talks to nobody until
 * this is done (see [Profile.onboarded]). Nothing here is ever chosen for the user.
 */
@Composable
internal fun OnboardingScreen(viewModel: ChatViewModel) {
    var pseudonym by remember { mutableStateOf(viewModel.myPseudonym) }
    var gender by remember { mutableStateOf(viewModel.gender) }
    var age by remember { mutableStateOf(viewModel.age?.toString() ?: "") }
    var shareAge by remember { mutableStateOf(viewModel.shareAge) }

    val ageValue = age.toIntOrNull()
    val tooYoung = ageValue != null && ageValue < Profile.MIN_AGE
    val canContinue = pseudonym.isNotBlank() && gender != null && ageValue != null && ageValue in Profile.MIN_AGE..Profile.MAX_AGE

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Welcome to FRAD", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Before you can meet anyone, tell people a little about yourself. There's no account - " +
                "all of this stays on your phone until you're matched with someone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = pseudonym,
            onValueChange = { pseudonym = it.take(Profile.MAX_LENGTH) },
            label = { Text("Pseudonym") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Text("Gender", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = gender == Gender.MALE, onClick = { gender = Gender.MALE }, label = { Text("Male") })
            FilterChip(selected = gender == Gender.FEMALE, onClick = { gender = Gender.FEMALE }, label = { Text("Female") })
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = age,
            onValueChange = { age = it.filter(Char::isDigit).take(3) },
            label = { Text("Age") },
            isError = tooYoung,
            supportingText = {
                Text(
                    if (tooYoung) "Sorry - FRAD is for people aged ${Profile.MIN_AGE} and over."
                    else "Adults are only matched with adults, and under-18s only with under-18s.",
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show my age to matches", modifier = Modifier.weight(1f))
            Switch(checked = shareAge, onCheckedChange = { shareAge = it })
        }
        Spacer(Modifier.height(24.dp))

        Button(
            enabled = canContinue,
            onClick = { viewModel.completeOnboarding(pseudonym, gender!!, ageValue!!, shareAge) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
        Spacer(Modifier.height(8.dp))
        Text(
            "You can add a photo and a short description later in Profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
