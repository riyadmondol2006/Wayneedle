package app.wayneedle.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.wayneedle.R
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.ui.components.StatTile
import app.wayneedle.ui.format.PLACEHOLDER
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.distanceLabelRes
import app.wayneedle.ui.format.elevationLabelRes
import app.wayneedle.ui.format.elevationSpokenRes
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.format.spokenDistance
import app.wayneedle.ui.format.spokenDuration
import app.wayneedle.ui.format.spokenSpeed

/** A speed statistic in the user's unit; a null [metersPerSecond] shows "—" (never a made-up zero). */
@Composable
internal fun SpeedStatTile(
    label: String,
    metersPerSecond: Float?,
    unit: SpeedUnit,
    formatter: UiFormatter,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    if (metersPerSecond == null || !metersPerSecond.isFinite()) {
        StatTile(
            label = label,
            value = PLACEHOLDER,
            spokenValue = stringResource(R.string.spoken_no_value),
            dimmed = dimmed,
            modifier = modifier,
        )
        return
    }
    StatTile(
        label = label,
        value = formatter.speed(metersPerSecond, unit),
        unit = stringResource(unit.speedLabelRes),
        spokenValue = spokenSpeed(metersPerSecond, unit, formatter),
        dimmed = dimmed,
        modifier = modifier,
    )
}

/** Trip distance with two decimals; null shows "—". */
@Composable
internal fun DistanceStatTile(
    meters: Double?,
    unit: SpeedUnit,
    formatter: UiFormatter,
    modifier: Modifier = Modifier,
    large: Boolean = true,
    dimmed: Boolean = false,
) {
    val label = stringResource(R.string.stat_distance)
    if (meters == null) {
        StatTile(
            label = label,
            value = PLACEHOLDER,
            spokenValue = stringResource(R.string.spoken_no_value),
            large = large,
            dimmed = dimmed,
            modifier = modifier,
        )
        return
    }
    StatTile(
        label = label,
        value = formatter.distance(meters, unit),
        unit = stringResource(unit.distanceLabelRes),
        spokenValue = spokenDistance(meters, unit, formatter),
        large = large,
        dimmed = dimmed,
        modifier = modifier,
    )
}

/** Active trip time (paused time excluded) as `H:MM:SS`; null shows "—". */
@Composable
internal fun DurationStatTile(
    millis: Long?,
    formatter: UiFormatter,
    modifier: Modifier = Modifier,
    large: Boolean = true,
    dimmed: Boolean = false,
    @StringRes labelRes: Int = R.string.stat_active_time,
) {
    val label = stringResource(labelRes)
    StatTile(
        label = label,
        value = if (millis == null) PLACEHOLDER else formatter.duration(millis),
        spokenValue = if (millis == null) {
            stringResource(R.string.spoken_no_value)
        } else {
            spokenDuration(millis, formatter)
        },
        large = large,
        dimmed = dimmed,
        modifier = modifier,
    )
}

/** Start-to-now time with pauses included, as `H:MM:SS`; null shows "—". */
@Composable
internal fun TotalTimeStatTile(
    millis: Long?,
    formatter: UiFormatter,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) = DurationStatTile(
    millis = millis,
    formatter = formatter,
    modifier = modifier,
    large = false,
    dimmed = dimmed,
    labelRes = R.string.stat_total_time,
)

/** Climb or descent in whole metres or feet; null (not measured) shows "—". */
@Composable
internal fun ElevationStatTile(
    label: String,
    meters: Double?,
    unit: SpeedUnit,
    formatter: UiFormatter,
    modifier: Modifier = Modifier,
) {
    if (meters == null || !meters.isFinite()) {
        StatTile(
            label = label,
            value = PLACEHOLDER,
            spokenValue = stringResource(R.string.spoken_no_value),
            modifier = modifier,
        )
        return
    }
    val quantity = formatter.elevationInUnit(meters, unit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    StatTile(
        label = label,
        value = formatter.elevation(meters, unit),
        unit = stringResource(unit.elevationLabelRes),
        spokenValue = pluralStringResource(unit.elevationSpokenRes, quantity, formatter.elevation(meters, unit)),
        modifier = modifier,
    )
}
