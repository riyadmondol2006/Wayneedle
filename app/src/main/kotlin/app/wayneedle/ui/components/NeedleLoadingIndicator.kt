package app.wayneedle.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wayneedle.ui.theme.LocalReduceMotion
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f
private const val SWING_MILLIS = 900
private const val REST_POSITION = 0.5f

/**
 * Wayneedle's loading indicator: a miniature dial whose needle swings back and forth while the app waits
 * for a GPS fix. Stands in for the Expressive `LoadingIndicator`, which is not in material3 1.4.0.
 * Decorative: the surrounding text carries the meaning. Static when the user turned animations off.
 */
@Composable
fun NeedleLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (LocalReduceMotion.current) {
        NeedleDial(modifier, size, color) { REST_POSITION }
    } else {
        val swing = rememberInfiniteTransition(label = "needle swing").animateFloat(
            initialValue = 0.08f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(tween(SWING_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "needle position",
        )
        NeedleDial(modifier, size, color) { swing.value }
    }
}

@Composable
private fun NeedleDial(modifier: Modifier, size: Dp, color: Color, position: () -> Float) {
    val trackColor = color.copy(alpha = 0.3f)
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val inset = stroke / 2f
        drawArc(
            color = trackColor,
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val centre = this.center
        val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * position()).toDouble())
        val length = this.size.minDimension * 0.34f
        drawLine(
            color = color,
            start = centre,
            end = Offset(centre.x + (cos(angle) * length).toFloat(), centre.y + (sin(angle) * length).toFloat()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = stroke * 0.9f, center = centre)
    }
}
