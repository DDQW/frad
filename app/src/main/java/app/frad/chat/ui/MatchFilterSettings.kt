package app.frad.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import app.frad.chat.R
import app.frad.chat.profile.Gender
import app.frad.chat.profile.MatchFilter
import app.frad.chat.profile.Profile

/** The slider's top end; at it, there's no upper age limit. */
private const val AGE_SLIDER_MAX = 70

/** "Who to meet": an age range and men/women/everyone - see [MatchFilter]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatchFilterSettings(viewModel: ChatViewModel) {
    var filter by remember { mutableStateOf(viewModel.matchFilter) }
    var range by remember {
        mutableStateOf((filter.minAge ?: Profile.MIN_AGE).toFloat()..(filter.maxAge ?: AGE_SLIDER_MAX).toFloat())
    }
    fun save(updated: MatchFilter) {
        filter = updated
        viewModel.matchFilter = updated
    }

    Column {
        Text(stringResource(R.string.match_title), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.match_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to R.string.match_everyone, Gender.FEMALE to R.string.match_women, Gender.MALE to R.string.match_men)
                .forEach { (gender, labelRes) ->
                    FilterChip(
                        selected = filter.gender == gender,
                        onClick = { save(filter.copy(gender = gender)) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
        }
        Spacer(Modifier.height(8.dp))
        val low = range.start.roundToInt()
        val high = range.endInclusive.roundToInt()
        Text(
            when {
                low <= Profile.MIN_AGE && high >= AGE_SLIDER_MAX -> stringResource(R.string.match_any_age)
                high >= AGE_SLIDER_MAX -> stringResource(R.string.match_age_from, low)
                else -> stringResource(R.string.match_age_range, low, high)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = Profile.MIN_AGE.toFloat()..AGE_SLIDER_MAX.toFloat(),
            steps = AGE_SLIDER_MAX - Profile.MIN_AGE - 1,
            onValueChangeFinished = {
                val min = range.start.roundToInt().takeIf { it > Profile.MIN_AGE }
                val max = range.endInclusive.roundToInt().takeIf { it < AGE_SLIDER_MAX }
                save(filter.copy(minAge = min, maxAge = max))
            },
        )
    }
}
