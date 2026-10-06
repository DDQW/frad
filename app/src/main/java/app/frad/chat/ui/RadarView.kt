package app.frad.chat.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import app.frad.chat.R
import app.frad.chat.pairing.NearbyPeer
import app.frad.chat.pairing.ProximityBand

/**
 * The people FRAD currently sees, as dots on a radar: the inner ring is "very close", the middle
 * one "nearby", the outer one everything further (and everyone found over the internet). Only
 * these coarse bands are shown - and a dot's angle is derived from its rotating session id, so
 * it carries no direction information at all.
 */
@Composable
internal fun RadarView(peers: List<NearbyPeer>, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val sweep by rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4_000, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    val bands = peers.groupingBy { ProximityBand.of(it.signalStrength) }.eachCount()
    val description = stringResource(
        R.string.radar_view_description,
        bands[ProximityBand.VERY_CLOSE] ?: 0,
        bands[ProximityBand.NEARBY] ?: 0,
        bands[ProximityBand.FURTHER] ?: 0,
    )

    Canvas(modifier = modifier.size(size).semantics { contentDescription = description }) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val radius = this.size.minDimension / 2
        for (fraction in listOf(1f / 3, 2f / 3, 1f)) {
            drawCircle(ring, radius * fraction, center, style = Stroke(width = 1.dp.toPx()))
        }
        rotate(sweep, center) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(Color.Transparent, primary.copy(alpha = 0.25f)), center),
                radius = radius,
                center = center,
            )
        }
        peers.forEach { peer ->
            val band = ProximityBand.of(peer.signalStrength)
            val angle = Math.toRadians((peer.sessionId.hashCode() and 0x7fffffff) % 360.0)
            val distance = radius * (band.ordinal + 0.5f) / 3f
            val dot = Offset(center.x + (distance * cos(angle)).toFloat(), center.y + (distance * sin(angle)).toFloat())
            drawCircle(primary, 6.dp.toPx(), dot)
        }
        drawCircle(primary, 4.dp.toPx(), center)
    }
}
