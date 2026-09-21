package app.frad.chat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = FradGreenLight,
    onPrimary = FradOnGreenLight,
    primaryContainer = FradGreenContainerLight,
    onPrimaryContainer = FradOnGreenContainerLight,
    secondary = FradSecondaryLight,
    onSecondary = FradOnSecondaryLight,
    secondaryContainer = FradSecondaryContainerLight,
    onSecondaryContainer = FradOnSecondaryContainerLight,
    tertiary = FradTertiaryLight,
    onTertiary = FradOnTertiaryLight,
    tertiaryContainer = FradTertiaryContainerLight,
    onTertiaryContainer = FradOnTertiaryContainerLight,
    background = FradBackgroundLight,
    onBackground = FradOnBackgroundLight,
    surface = FradBackgroundLight,
    onSurface = FradOnSurfaceLight,
    surfaceVariant = FradSurfaceVariantLight,
    onSurfaceVariant = FradOnSurfaceVariantLight,
    outline = FradOutlineLight,
    error = FradErrorLight,
    onError = FradOnGreenLight,
    errorContainer = FradErrorContainerLight,
    onErrorContainer = FradOnErrorContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = FradGreenDark,
    onPrimary = FradOnGreenDark,
    primaryContainer = FradGreenContainerDark,
    onPrimaryContainer = FradOnGreenContainerDark,
    secondary = FradSecondaryDark,
    onSecondary = FradOnSecondaryDark,
    secondaryContainer = FradSecondaryContainerDark,
    onSecondaryContainer = FradOnSecondaryContainerDark,
    tertiary = FradTertiaryDark,
    onTertiary = FradOnTertiaryDark,
    tertiaryContainer = FradTertiaryContainerDark,
    onTertiaryContainer = FradOnTertiaryContainerDark,
    background = FradBackgroundDark,
    onBackground = FradOnBackgroundDark,
    surface = FradBackgroundDark,
    onSurface = FradOnSurfaceDark,
    surfaceVariant = FradSurfaceVariantDark,
    onSurfaceVariant = FradOnSurfaceVariantDark,
    outline = FradOutlineDark,
    error = FradErrorDark,
    onError = FradOnErrorDark,
    errorContainer = FradErrorContainerDark,
    onErrorContainer = FradOnErrorContainerDark,
)

/** Chat-bubble colors, per theme - not part of Material3's standard [androidx.compose.material3.ColorScheme]
 *  slots, so carried alongside it via composition local rather than hijacking an unrelated slot. */
data class FradExtraColors(val bubbleMine: Color, val bubbleTheirs: Color)

private val LocalFradExtraColors = staticCompositionLocalOf {
    FradExtraColors(bubbleMine = FradBubbleMineLight, bubbleTheirs = FradBubbleTheirsLight)
}

val MaterialTheme.fradExtraColors: FradExtraColors
    @Composable get() = LocalFradExtraColors.current

@Composable
fun FradTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val extraColors = if (darkTheme) {
        FradExtraColors(bubbleMine = FradBubbleMineDark, bubbleTheirs = FradBubbleTheirsDark)
    } else {
        FradExtraColors(bubbleMine = FradBubbleMineLight, bubbleTheirs = FradBubbleTheirsLight)
    }

    CompositionLocalProvider(LocalFradExtraColors provides extraColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
