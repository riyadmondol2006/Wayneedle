package app.wayneedle.ui.speedometer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayneedle.R
import app.wayneedle.domain.model.GpsSignalQuality
import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.accuracyRes
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.theme.LocalExtraColors

/** Test tag of the GPS signal indicator. */
const val GPS_INDICATOR_TAG = "gps_signal"

private const val BAR_COUNT = 4

/** Number of lit bars (0..4) for a signal quality. */
fun GpsSignalQuality.bars(): Int = when (this) {
    GpsSignalQuality.NONE -> 0
    GpsSignalQuality.POOR -> 1
    GpsSignalQuality.FAIR -> 2
    GpsSignalQuality.GOOD -> 3
    GpsSignalQuality.EXCELLENT -> 4
}

private val GpsSignalQuality.labelRes: Int
    get() = when (this) {
        GpsSignalQuality.NONE -> R.string.gps_quality_none
        GpsSignalQuality.POOR -> R.string.gps_quality_poor
        GpsSignalQuality.FAIR -> R.string.gps_quality_fair
        GpsSignalQuality.GOOD -> R.string.gps_quality_good
        GpsSignalQuality.EXCELLENT -> R.string.gps_quality_excellent
    }

/**
 * GPS signal strength as four rising bars plus the quality word and, when known, accuracy and satellite
 * counts. One accessibility node reads the whole status as a sentence.
 */
@Composable
fun GpsSignalIndicator(
    gps: GpsStatus,
    unit: SpeedUnit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberUiFormatter()
    val quality = if (gps.providerEnabled) gps.quality else GpsSignalQuality.NONE
    val headline = if (gps.providerEnabled) stringResource(quality.labelRes) else stringResource(R.string.gps_off)
    val accuracy = gps.horizontalAccuracyM
        ?.takeIf { quality != GpsSignalQuality.NONE && it.isFinite() }
        ?.let { stringResource(unit.accuracyRes, formatter.accuracy(it, unit)) }
    val satellites = satelliteText(gps, formatter)
    val detail = listOfNotNull(accuracy, satellites).joinToString(" · ")
    val spoken = spokenDescription(gps, quality, headline, unit, formatter)

    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = spoken
            testTag = GPS_INDICATOR_TAG
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SignalBars(quality)
        Column {
            Text(
                text = headline,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun satelliteText(gps: GpsStatus, formatter: UiFormatter): String? {
    val used = gps.satellitesUsed
    val inView = gps.satellitesInView
    return when {
        used != null && inView != null && inView >= used ->
            stringResource(R.string.gps_satellites_used_of_view, formatter.count(used), formatter.count(inView))

        used != null -> pluralStringResource(R.plurals.gps_satellites_used, used, formatter.count(used))

        else -> null
    }
}

@Composable
private fun spokenDescription(
    gps: GpsStatus,
    quality: GpsSignalQuality,
    headline: String,
    unit: SpeedUnit,
    formatter: UiFormatter,
): String {
    // "GPS off" already names the GPS; "GPS: GPS off" would read the word twice.
    val parts =
        mutableListOf(if (gps.providerEnabled) stringResource(R.string.spoken_gps_signal, headline) else headline)
    val accuracy = gps.horizontalAccuracyM
    if (accuracy != null && quality != GpsSignalQuality.NONE && accuracy.isFinite()) {
        val value = formatter.accuracy(accuracy, unit)
        // The plural must agree with the number spoken (feet for mph users), not with the metres.
        val quantity = formatter.accuracyInUnit(accuracy, unit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        parts += when (unit) {
            SpeedUnit.KMH -> pluralStringResource(R.plurals.spoken_accuracy_m, quantity, value)
            SpeedUnit.MPH -> pluralStringResource(R.plurals.spoken_accuracy_ft, quantity, value)
        }
    }
    val used = gps.satellitesUsed
    val inView = gps.satellitesInView
    if (used != null && inView != null && inView >= used) {
        parts += stringResource(R.string.gps_satellites_used_of_view, formatter.count(used), formatter.count(inView))
    } else if (used != null) {
        parts += pluralStringResource(R.plurals.gps_satellites_used, used, formatter.count(used))
    }
    return parts.joinToString(". ")
}

@Composable
private fun SignalBars(quality: GpsSignalQuality) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val lit: Color = when (quality) {
        GpsSignalQuality.NONE -> scheme.outline
        GpsSignalQuality.POOR -> scheme.error
        GpsSignalQuality.FAIR -> extra.warning
        GpsSignalQuality.GOOD, GpsSignalQuality.EXCELLENT -> extra.success
    }
    val unlit = scheme.outlineVariant
    val active = quality.bars()
    Canvas(Modifier.width(26.dp).height(20.dp)) {
        val barWidth = size.width / (BAR_COUNT * 2f - 1f)
        for (index in 0 until BAR_COUNT) {
            val barHeight = size.height * (index + 1) / BAR_COUNT
            drawRoundRect(
                color = if (index < active) lit else unlit,
                topLeft = Offset(index * barWidth * 2f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
