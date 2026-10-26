package app.wayneedle.ui.speedometer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wayneedle.R
import app.wayneedle.domain.model.GpsSignalQuality
import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.format.PLACEHOLDER
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.accuracyRes
import app.wayneedle.ui.format.compassPointRes
import app.wayneedle.ui.format.elevationLabelRes
import app.wayneedle.ui.format.normalizedDegrees
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.icons.WayneedleIcons
import app.wayneedle.ui.theme.LocalExtraColors
import app.wayneedle.ui.theme.tabular

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
    onClick: (() -> Unit)? = null,
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

    val clickLabel = stringResource(R.string.action_gps_details)
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = modifier.then(clickable).heightIn(min = 48.dp).clearAndSetSemantics {
            contentDescription = spoken
            testTag = GPS_INDICATOR_TAG
            if (onClick != null) {
                role = Role.Button
                onClick(label = clickLabel) {
                    onClick()
                    true
                }
            }
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

/** Everything the receiver reports about the current fix, behind a tap on the signal indicator. */
@Composable
fun GpsDetailsDialog(gps: GpsStatus, unit: SpeedUnit, onDismiss: () -> Unit) {
    val formatter = rememberUiFormatter()
    val quality = if (gps.providerEnabled) gps.quality else GpsSignalQuality.NONE
    val none = PLACEHOLDER
    val rows = listOf(
        R.string.gps_details_signal to
            if (gps.providerEnabled) stringResource(quality.labelRes) else stringResource(R.string.gps_off),
        R.string.gps_details_accuracy to (
            gps.horizontalAccuracyM?.takeIf { it.isFinite() }
                ?.let { stringResource(unit.accuracyRes, formatter.accuracy(it, unit)) } ?: none
            ),
        R.string.gps_details_speed_accuracy to (
            gps.speedAccuracyMps?.takeIf { it.isFinite() }
                ?.let { "±" + formatter.speed(it, unit) + " " + stringResource(unit.speedLabelRes) } ?: none
            ),
        R.string.gps_details_satellites to (
            gps.satellitesInView?.let { inView ->
                "${gps.satellitesUsed?.let(formatter::count) ?: none} / ${formatter.count(inView)}"
            }
                ?: none
            ),
        R.string.gps_details_fix_age to (
            gps.lastFixAgeMillis?.let {
                stringResource(R.string.gps_details_fix_age_value, formatter.count((it / 1_000L).toInt()))
            }
                ?: none
            ),
        R.string.gps_details_altitude to (
            gps.altitudeM?.takeIf { it.isFinite() }?.let { altitude ->
                val accuracy = gps.verticalAccuracyM?.takeIf { it.isFinite() }
                    ?.let { " (" + stringResource(unit.accuracyRes, formatter.accuracy(it, unit)) + ")" }.orEmpty()
                formatter.elevationSigned(altitude, unit) + " " + stringResource(unit.elevationLabelRes) + accuracy
            } ?: none
            ),
        R.string.gps_details_heading to (
            gps.bearingDeg?.let {
                stringResource(
                    R.string.heading_value,
                    stringResource(compassPointRes(it)),
                    formatter.count(normalizedDegrees(it)),
                )
            } ?: none
            ),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(WayneedleIcons.Gps, contentDescription = null) },
        title = { Text(stringResource(R.string.gps_details_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (labelRes, value) ->
                    val label = stringResource(labelRes)
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.gps_details_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
