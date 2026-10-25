package app.wayneedle.ui.speedometer

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wayneedle.R
import app.wayneedle.ui.components.NeedleLoadingIndicator
import app.wayneedle.ui.format.PLACEHOLDER
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.format.speedSpokenRes
import app.wayneedle.ui.theme.LocalExtraColors
import app.wayneedle.ui.theme.WayneedleMotion
import app.wayneedle.ui.theme.tabular
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Test tag of the gauge's accessibility node. */
const val SPEEDOMETER_TAG = "speedometer"

private const val START_ANGLE = 135f
private const val SWEEP_ANGLE = 270f

/** Height of the gauge as a fraction of its width. The dial is cut below the arc's end caps. */
const val GAUGE_HEIGHT_FRACTION = 0.9f

// Geometry, as fractions of the gauge width. The dial's centre is (0.5, 0.5) of the width.
private const val OUTER_RADIUS = 0.47f
private const val ARC_STROKE = 0.034f
private const val TICK_GAP = 0.014f
private const val MAJOR_TICK = 0.04f
private const val MINOR_TICK = 0.022f
private const val LABEL_GAP = 0.034f
private const val NEEDLE_BASE_RADIUS = 0.27f
private const val NEEDLE_BASE_HALF_WIDTH = 0.019f
private const val NEEDLE_TIP_HALF_WIDTH = 0.003f
private const val DIGITS_SIZE = 0.26f
private const val STATUS_TOP = 0.775f
private const val UNAVAILABLE_ALPHA = 0.35f
private const val LABEL_FONT = 0.034f

/** Visual weight of the [GaugeStatus] pill. */
enum class GaugeStatusTone {
    NEUTRAL,
    ACTIVE,
    CAUTION,
    ERROR,
}

/** A short state label shown in the gap at the bottom of the dial (e.g. "Paused", "Waiting for GPS…"). */
@Immutable
data class GaugeStatus(
    val label: String,
    val tone: GaugeStatusTone,
    val icon: ImageVector? = null,
    val loading: Boolean = false,
    /** Larger pill for states that must not be missed, such as a paused trip. */
    val prominent: Boolean = false,
)

/**
 * The Wayneedle speedometer: a 270-degree dial with ticks, a spring-animated needle and a large digital
 * readout with tabular figures. [SpeedReading.Unavailable] shows "—" and the reason; it never shows 0.
 *
 * The whole dial is a single accessibility node (label + current speed as its state). Per-second changes
 * are not announced: only [status] is a polite live region and it changes on state transitions only.
 *
 * @param muted draws the progress arc and needle dimmed (e.g. while a trip is paused).
 */
@Composable
fun Speedometer(
    reading: SpeedReading,
    scale: GaugeScale,
    modifier: Modifier = Modifier,
    status: GaugeStatus? = null,
    muted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val formatter = rememberUiFormatter()
    val density = LocalDensity.current
    val available = reading is SpeedReading.Available

    val targetFraction = (reading as? SpeedReading.Available)?.let { scale.fractionOf(it.valueInUnit) } ?: 0f
    val needle = remember { Animatable(0f) }
    val spec by rememberUpdatedState(WayneedleMotion.spatial<Float>())
    LaunchedEffect(targetFraction) { needle.animateTo(targetFraction, spec) }

    val gaugeLabel = stringResource(R.string.speedometer_label)
    val spokenState = when (reading) {
        is SpeedReading.Available -> {
            val rounded = reading.valueInUnit.roundToInt()
            pluralStringResource(
                scale.unit.speedSpokenRes,
                rounded,
                formatter.speedInUnit(reading.valueInUnit.toDouble()),
            )
        }
        is SpeedReading.Unavailable -> stringResource(
            R.string.spoken_speed_unavailable,
            stringResource(reading.reason.labelRes),
        )
    }

    val labelStyle = MaterialTheme.typography.labelSmall.tabular()
    val textMeasurer = rememberTextMeasurer()
    val trackColor = colors.outlineVariant
    val majorTickColor = colors.onSurface
    val minorTickColor = colors.outline
    val labelColor = colors.onSurfaceVariant
    val arcColor = colors.primary
    val needleColor = colors.primary
    val needleHalo = colors.surface
    val fontScale = density.fontScale

    BoxWithConstraints(modifier) {
        val width = maxWidth
        val extraHeight = ((fontScale - 1f).coerceAtLeast(0f) * 44f).dp
        Box(Modifier.fillMaxWidth().height(width * GAUGE_HEIGHT_FRACTION + extraHeight)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {
                        contentDescription = gaugeLabel
                        stateDescription = spokenState
                        testTag = SPEEDOMETER_TAG
                    },
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val layouts = buildLabelLayouts(textMeasurer, labelStyle, labelColor, scale) { value ->
                                formatter.speedInUnit(value.toDouble())
                            }
                            onDrawBehind {
                                drawScale(scale, layouts, trackColor, majorTickColor, minorTickColor)
                            }
                        },
                )
                Canvas(Modifier.fillMaxSize()) {
                    drawNeedleLayer(
                        fraction = needle.value,
                        scale = scale,
                        arcColor = arcColor,
                        needleColor = needleColor,
                        haloColor = needleHalo,
                        alpha = if (!available) UNAVAILABLE_ALPHA else if (muted) 0.5f else 1f,
                        showArc = available,
                    )
                }
                SpeedReadout(
                    reading = reading,
                    unitLabel = stringResource(scale.unit.speedLabelRes),
                    dialWidth = width,
                    muted = muted,
                    formatter = formatter,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .requiredHeight(width),
                )
            }
            if (status != null) {
                GaugeStatusPill(
                    status = status,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = width * STATUS_TOP),
                )
            }
        }
    }
}

private val SpeedUnavailableReason.labelRes: Int
    get() = when (this) {
        SpeedUnavailableReason.NO_GPS_HARDWARE -> R.string.speed_reason_no_gps
        SpeedUnavailableReason.PERMISSION_REQUIRED -> R.string.speed_reason_permission
        SpeedUnavailableReason.APPROXIMATE_ONLY -> R.string.speed_reason_approximate
        SpeedUnavailableReason.GPS_OFF -> R.string.speed_reason_gps_off
        SpeedUnavailableReason.SEARCHING -> R.string.speed_reason_searching
    }

@Composable
private fun SpeedReadout(
    reading: SpeedReading,
    unitLabel: String,
    dialWidth: Dp,
    muted: Boolean,
    formatter: UiFormatter,
    modifier: Modifier = Modifier,
) {
    val digitsSize = with(LocalDensity.current) { (dialWidth * DIGITS_SIZE).toSp() }
    val digitsStyle = MaterialTheme.typography.displayLarge.tabular().copy(
        fontSize = digitsSize,
        lineHeight = digitsSize * 1.05f,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (reading) {
            is SpeedReading.Available -> {
                Text(
                    text = formatter.speedInUnit(reading.valueInUnit.toDouble()),
                    style = digitsStyle,
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = unitLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is SpeedReading.Unavailable -> {
                Text(
                    text = PLACEHOLDER,
                    style = digitsStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    softWrap = false,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.widthIn(max = dialWidth * 0.55f),
                ) {
                    if (reading.reason == SpeedUnavailableReason.SEARCHING) NeedleLoadingIndicator(size = 20.dp)
                    Text(
                        text = stringResource(reading.reason.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun GaugeStatusPill(status: GaugeStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val (container, content) = when (status.tone) {
        GaugeStatusTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurface
        GaugeStatusTone.ACTIVE -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        GaugeStatusTone.CAUTION -> extra.warningContainer to extra.onWarningContainer
        GaugeStatusTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = if (status.prominent) 40.dp else 32.dp)
                .padding(horizontal = if (status.prominent) 18.dp else 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                status.loading -> NeedleLoadingIndicator(size = 18.dp, color = content)
                status.icon != null -> Icon(status.icon, contentDescription = null, modifier = Modifier.size(if (status.prominent) 22.dp else 18.dp))
            }
            Text(
                text = status.label,
                style = if (status.prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** A measured tick label and the dial value it belongs to. */
private class TickLabel(val layout: TextLayoutResult, val value: Int)

private fun CacheDrawScope.buildLabelLayouts(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    scale: GaugeScale,
    format: (Int) -> String,
): List<TickLabel> {
    val sized = style.copy(fontSize = (size.width * LABEL_FONT).toSp(), color = color)
    return (0..scale.max step scale.majorStep).map { value ->
        TickLabel(measurer.measure(format(value), sized), value)
    }
}

private fun angleFor(scale: GaugeScale, value: Int): Double =
    Math.toRadians((START_ANGLE + SWEEP_ANGLE * value / scale.max).toDouble())

private fun DrawScope.pointOnDial(angleRadians: Double, radius: Float): Offset {
    val centre = Offset(size.width / 2f, size.width / 2f)
    return Offset(
        centre.x + (cos(angleRadians) * radius).toFloat(),
        centre.y + (sin(angleRadians) * radius).toFloat(),
    )
}

private fun DrawScope.drawScale(
    scale: GaugeScale,
    labels: List<TickLabel>,
    trackColor: Color,
    majorTickColor: Color,
    minorTickColor: Color,
) {
    val w = size.width
    val arcStroke = w * ARC_STROKE
    val arcRadius = w * OUTER_RADIUS - arcStroke / 2f
    drawArc(
        color = trackColor,
        startAngle = START_ANGLE,
        sweepAngle = SWEEP_ANGLE,
        useCenter = false,
        topLeft = Offset(w / 2f - arcRadius, w / 2f - arcRadius),
        size = Size(arcRadius * 2f, arcRadius * 2f),
        style = Stroke(width = arcStroke, cap = StrokeCap.Round),
    )
    val tickOuter = w * OUTER_RADIUS - arcStroke - w * TICK_GAP
    var value = 0
    while (value <= scale.max) {
        val major = value % scale.majorStep == 0
        val angle = angleFor(scale, value)
        drawLine(
            color = if (major) majorTickColor else minorTickColor,
            start = pointOnDial(angle, tickOuter),
            end = pointOnDial(angle, tickOuter - w * (if (major) MAJOR_TICK else MINOR_TICK)),
            strokeWidth = w * (if (major) 0.0075f else 0.004f),
            cap = StrokeCap.Round,
        )
        value += scale.minorStep
    }
    val labelRadius = tickOuter - w * MAJOR_TICK - w * LABEL_GAP
    for (label in labels) {
        val centre = pointOnDial(angleFor(scale, label.value), labelRadius)
        drawText(
            textLayoutResult = label.layout,
            topLeft = Offset(
                centre.x - label.layout.size.width / 2f,
                centre.y - label.layout.size.height / 2f,
            ),
        )
    }
}

/** Re-draws the ticks the needle has passed in the accent colour, so the scale lights up behind the needle. */
private fun DrawScope.drawLitTicks(scale: GaugeScale, fraction: Float, tickOuter: Float, color: Color) {
    val w = size.width
    var value = 0
    while (value <= scale.max && value <= fraction * scale.max) {
        val major = value % scale.majorStep == 0
        val angle = angleFor(scale, value)
        drawLine(
            color = color,
            start = pointOnDial(angle, tickOuter),
            end = pointOnDial(angle, tickOuter - w * (if (major) MAJOR_TICK else MINOR_TICK)),
            strokeWidth = w * (if (major) 0.0075f else 0.004f),
            cap = StrokeCap.Round,
        )
        value += scale.minorStep
    }
}

private fun DrawScope.drawNeedleLayer(
    fraction: Float,
    scale: GaugeScale,
    arcColor: Color,
    needleColor: Color,
    haloColor: Color,
    alpha: Float,
    showArc: Boolean,
) {
    val w = size.width
    val centre = Offset(w / 2f, w / 2f)
    val arcStroke = w * ARC_STROKE
    val arcRadius = w * OUTER_RADIUS - arcStroke / 2f
    val clamped = fraction.coerceIn(-0.02f, 1.02f)
    val sweep = SWEEP_ANGLE * clamped

    if (showArc && sweep > 0.5f) {
        rotate(START_ANGLE, pivot = centre) {
            drawArc(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0f to arcColor.copy(alpha = 0.55f * alpha),
                        SWEEP_ANGLE / 360f to arcColor.copy(alpha = alpha),
                    ),
                    center = centre,
                ),
                startAngle = 0f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(centre.x - arcRadius, centre.y - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = arcStroke, cap = StrokeCap.Round),
            )
        }
    }

    val tipRadius = w * OUTER_RADIUS - arcStroke - w * TICK_GAP
    if (showArc) drawLitTicks(scale, clamped, tipRadius, arcColor.copy(alpha = alpha))
    val baseRadius = w * NEEDLE_BASE_RADIUS
    val baseHalf = w * NEEDLE_BASE_HALF_WIDTH
    val tipHalf = w * NEEDLE_TIP_HALF_WIDTH
    val needle = Path().apply {
        moveTo(centre.x + baseRadius, centre.y - baseHalf)
        lineTo(centre.x + tipRadius, centre.y - tipHalf)
        lineTo(centre.x + tipRadius, centre.y + tipHalf)
        lineTo(centre.x + baseRadius, centre.y + baseHalf)
        close()
    }
    rotate(START_ANGLE + sweep, pivot = centre) {
        drawPath(
            path = needle,
            color = haloColor.copy(alpha = alpha),
            style = Stroke(width = w * 0.008f, join = StrokeJoin.Round),
        )
        drawPath(path = needle, color = needleColor.copy(alpha = alpha))
        drawCircle(
            color = needleColor.copy(alpha = alpha),
            radius = baseHalf,
            center = Offset(centre.x + baseRadius, centre.y),
        )
    }
}
