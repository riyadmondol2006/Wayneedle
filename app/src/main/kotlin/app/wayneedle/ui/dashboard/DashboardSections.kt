package app.wayneedle.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.wayneedle.R
import app.wayneedle.domain.model.GpsStatus
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.domain.model.TripStats
import app.wayneedle.ui.components.ConfirmDialog
import app.wayneedle.ui.components.ExpressiveButton
import app.wayneedle.ui.components.ExpressiveButtonRow
import app.wayneedle.ui.components.ExpressiveButtonStyle
import app.wayneedle.ui.components.IconBadge
import app.wayneedle.ui.components.MessageCard
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.distanceLabelRes
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.format.speedLabelRes
import app.wayneedle.ui.icons.WayneedleIcons
import app.wayneedle.ui.permission.BackgroundRisk
import app.wayneedle.ui.speedometer.GaugeStatus
import app.wayneedle.ui.speedometer.GaugeStatusTone
import app.wayneedle.ui.speedometer.GpsDetailsDialog
import app.wayneedle.ui.speedometer.GpsSignalIndicator
import app.wayneedle.ui.speedometer.SpeedReading
import app.wayneedle.ui.speedometer.Speedometer
import app.wayneedle.ui.theme.LocalExtraColors
import kotlin.math.roundToInt

private const val HEADER_STACK_FONT_SCALE = 1.3f
private const val THREE_COLUMN_MAX_FONT_SCALE = 1.3f
private const val TWO_COLUMN_MAX_FONT_SCALE = 1.8f
private const val ONE_COLUMN_FONT_SCALE = 1.6f

// ---- Header ----------------------------------------------------------------------------------------------------

@Composable
internal fun DashboardHeader(
    unit: SpeedUnit,
    gps: GpsStatus,
    modifier: Modifier = Modifier,
    onOpenHud: () -> Unit = {},
) {
    val stacked = LocalDensity.current.fontScale >= HEADER_STACK_FONT_SCALE
    var showDetails by rememberSaveable { mutableStateOf(false) }
    if (stacked) {
        Column(modifier.heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Modifier.weight(1f))
                IconButton(onClick = onOpenHud) {
                    Icon(WayneedleIcons.Hud, contentDescription = stringResource(R.string.action_hud))
                }
            }
            GpsSignalIndicator(gps, unit, onClick = { showDetails = true })
        }
    } else {
        Row(
            modifier = modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Wordmark(Modifier.weight(1f))
            IconButton(onClick = onOpenHud) {
                Icon(WayneedleIcons.Hud, contentDescription = stringResource(R.string.action_hud))
            }
            GpsSignalIndicator(gps, unit, onClick = { showDetails = true })
        }
    }
    if (showDetails) GpsDetailsDialog(gps, unit, onDismiss = { showDetails = false })
}

@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = WayneedleIcons.Speed,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

// ---- Gauge -----------------------------------------------------------------------------------------------------

@Composable
internal fun DashboardGauge(
    gauge: GaugeUiState,
    phase: TripPhase,
    awaitingFix: Boolean,
    modifier: Modifier = Modifier,
) {
    Speedometer(
        reading = gauge.reading,
        scale = gauge.scale,
        modifier = modifier,
        status = rememberGaugeStatus(gauge, phase, awaitingFix),
        muted = phase == TripPhase.PAUSED,
        limitInUnit = gauge.limitInUnit,
        overLimit = gauge.overLimit,
        headingDeg = gauge.headingDeg,
    )
}

/**
 * The short state label under the dial. Changes only when the trip, GPS or speed alert state changes, never per
 * second. Going over the speed alert limit takes precedence: the colour change alone is too subtle in some themes.
 */
@Composable
private fun rememberGaugeStatus(gauge: GaugeUiState, phase: TripPhase, awaitingFix: Boolean): GaugeStatus? {
    val reading = gauge.reading
    val noSpeed = reading is SpeedReading.Unavailable
    val limit = gauge.limitInUnit
    if (gauge.overLimit && limit != null) {
        val formatter = rememberUiFormatter()
        return GaugeStatus(
            label = stringResource(
                R.string.status_over_speed_limit,
                formatter.count(limit.roundToInt()),
                stringResource(gauge.scale.unit.speedLabelRes),
            ),
            tone = GaugeStatusTone.ERROR,
            icon = WayneedleIcons.Warning,
            prominent = true,
        )
    }
    return when (phase) {
        TripPhase.PAUSED -> GaugeStatus(
            label = stringResource(R.string.status_paused),
            tone = GaugeStatusTone.CAUTION,
            icon = WayneedleIcons.Pause,
            prominent = true,
        )

        TripPhase.RUNNING -> when {
            awaitingFix -> GaugeStatus(
                label = stringResource(R.string.status_waiting_for_gps),
                tone = GaugeStatusTone.CAUTION,
                loading = true,
            )

            noSpeed -> GaugeStatus(
                label = stringResource(R.string.status_signal_lost),
                tone = GaugeStatusTone.CAUTION,
                loading = true,
            )

            else -> GaugeStatus(
                label = stringResource(R.string.status_recording),
                tone = GaugeStatusTone.ACTIVE,
                icon = WayneedleIcons.Gps,
            )
        }

        TripPhase.FINISHED -> GaugeStatus(
            label = stringResource(R.string.status_finished),
            tone = GaugeStatusTone.ACTIVE,
            icon = WayneedleIcons.Check,
        )

        TripPhase.IDLE ->
            if (noSpeed) null else GaugeStatus(stringResource(R.string.status_ready), GaugeStatusTone.NEUTRAL)
    }
}

// ---- Middle panel: statistics, permission/GPS cards, recovered banner, trip summary ----------------------------

@Composable
internal fun DashboardPanel(
    state: DashboardUiState,
    gate: LocationGate,
    actions: DashboardActions,
    formatter: UiFormatter,
    modifier: Modifier = Modifier,
) {
    val trip = state.trip
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            trip.phase == TripPhase.FINISHED -> TripSummaryCard(state, formatter, actions)

            trip.phase == TripPhase.IDLE && gate != LocationGate.Ready -> LocationGateCard(gate, actions)

            else -> {
                if (trip.phase == TripPhase.PAUSED && trip.recovered) RecoveredTripCard(state, formatter, actions)
                if (gate != LocationGate.Ready) GateBanner(gate, actions)
                if (state.backgroundRecordingUnavailable) {
                    WarningBanner(stringResource(R.string.banner_background_unavailable))
                }
                if (BackgroundRisk.APP_BATTERY_RESTRICTED in state.backgroundRisks) {
                    WarningBanner(
                        message = stringResource(R.string.banner_battery_restricted),
                        actionLabel = stringResource(R.string.action_change),
                        onAction = actions.onOpenAppSettings,
                    )
                }
                if (BackgroundRisk.BATTERY_SAVER_STOPS_GPS in state.backgroundRisks) {
                    WarningBanner(
                        message = stringResource(R.string.banner_battery_saver_gps),
                        actionLabel = stringResource(R.string.action_change),
                        onAction = actions.onOpenBatterySaverSettings,
                        onDismiss = actions.onDismissBatterySaverWarning,
                    )
                }
                if (state.notificationsBlocked && state.isTripActive) {
                    WarningBanner(
                        message = stringResource(R.string.banner_notifications_off),
                        actionLabel = stringResource(R.string.action_turn_on),
                        onAction = actions.onOpenNotificationSettings,
                    )
                }
                TripStatsPanel(state, formatter)
            }
        }
    }
}

@Composable
private fun columnsFor(preferred: Int): Int {
    val fontScale = LocalDensity.current.fontScale
    return when {
        fontScale >= TWO_COLUMN_MAX_FONT_SCALE -> 1
        preferred >= 3 && fontScale > THREE_COLUMN_MAX_FONT_SCALE -> 2
        preferred == 2 && fontScale >= ONE_COLUMN_FONT_SCALE -> 1
        else -> preferred
    }
}

@Composable
private fun TileRows(
    columns: Int,
    tiles: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTiles.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The trip numbers: distance, active time, current/top/average speed, moving average, moving and total time. While no trip exists they show "—" so the layout stays put when a trip starts. */
@Composable
internal fun TripStatsPanel(state: DashboardUiState, formatter: UiFormatter, modifier: Modifier = Modifier) {
    val trip = state.trip
    val unit = state.unit
    val hasTrip = trip.phase != TripPhase.IDLE
    val stats = trip.stats
    val headlineTiles = listOf<@Composable (Modifier) -> Unit>(
        { tileModifier ->
            DistanceStatTile(
                meters = stats.distanceMeters.takeIf { hasTrip },
                unit = unit,
                formatter = formatter,
                dimmed = !hasTrip || trip.awaitingFix,
                modifier = tileModifier,
            )
        },
        { tileModifier ->
            DurationStatTile(
                millis = stats.activeDurationMillis.takeIf { hasTrip },
                formatter = formatter,
                dimmed = !hasTrip,
                modifier = tileModifier,
            )
        },
    )
    val currentLabel = stringResource(R.string.stat_current)
    val topLabel = stringResource(R.string.stat_top)
    val averageLabel = stringResource(R.string.stat_average)
    val speedTiles = listOf<@Composable (Modifier) -> Unit>(
        { tileModifier ->
            SpeedStatTile(currentLabel, trip.currentSpeedMps, unit, formatter, tileModifier)
        },
        { tileModifier ->
            SpeedStatTile(
                topLabel,
                stats.maxSpeedMps.takeIf {
                    hasTrip
                },
                unit,
                formatter,
                tileModifier,
                dimmed = !hasTrip,
            )
        },
        { tileModifier ->
            SpeedStatTile(
                averageLabel,
                stats.averageSpeedMps.takeIf {
                    hasTrip
                },
                unit,
                formatter,
                tileModifier,
                dimmed = !hasTrip,
            )
        },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileRows(columns = columnsFor(2), tiles = headlineTiles)
        TileRows(columns = columnsFor(3), tiles = speedTiles)
        TileRows(
            columns = columnsFor(3),
            tiles = listOf(
                { tileModifier ->
                    SpeedStatTile(
                        stringResource(R.string.stat_moving_average),
                        stats.movingAverageSpeedMps.takeIf { hasTrip },
                        unit,
                        formatter,
                        tileModifier,
                        dimmed = !hasTrip,
                    )
                },
                { tileModifier ->
                    DurationStatTile(
                        millis = stats.movingDurationMillis.takeIf { hasTrip },
                        formatter = formatter,
                        modifier = tileModifier,
                        large = false,
                        dimmed = !hasTrip,
                        labelRes = R.string.stat_moving_time,
                    )
                },
                { tileModifier ->
                    DurationStatTile(
                        millis = trip.totalDurationMillis.takeIf { hasTrip },
                        formatter = formatter,
                        modifier = tileModifier,
                        large = false,
                        dimmed = !hasTrip,
                        labelRes = R.string.stat_total_time_short,
                    )
                },
            ),
        )
    }
}

// ---- Permission and GPS cards ----------------------------------------------------------------------------------

/** The single action that resolves [gate], or null when there is none the user can take. */
private class GateAction(val labelRes: Int, val onClick: () -> Unit)

private fun gateAction(gate: LocationGate, actions: DashboardActions): GateAction? = when (gate) {
    LocationGate.NeedsRationale -> GateAction(R.string.gate_action_allow, actions.onRequestPermission)
    LocationGate.CanRequestAgain -> GateAction(R.string.gate_action_try_again, actions.onRequestPermission)
    LocationGate.PermanentlyDenied -> GateAction(R.string.gate_action_open_app_settings, actions.onOpenAppSettings)
    LocationGate.ApproximateOnly -> GateAction(R.string.gate_action_allow_precise, actions.onRequestPermission)
    LocationGate.GpsOff -> GateAction(R.string.gate_action_turn_on_location, actions.onOpenLocationSettings)
    LocationGate.NoGpsHardware, LocationGate.Ready -> null
}

@Composable
internal fun LocationGateCard(gate: LocationGate, actions: DashboardActions, modifier: Modifier = Modifier) {
    val (icon, titleRes, bodyRes) = when (gate) {
        LocationGate.NoGpsHardware -> Triple(
            WayneedleIcons.LocationOff,
            R.string.gate_no_gps_title,
            R.string.gate_no_gps_body,
        )

        LocationGate.NeedsRationale -> Triple(
            WayneedleIcons.Gps,
            R.string.gate_rationale_title,
            R.string.gate_rationale_body,
        )

        LocationGate.CanRequestAgain -> Triple(WayneedleIcons.Gps, R.string.gate_retry_title, R.string.gate_retry_body)

        LocationGate.PermanentlyDenied -> Triple(
            WayneedleIcons.LocationOff,
            R.string.gate_blocked_title,
            R.string.gate_blocked_body,
        )

        LocationGate.ApproximateOnly -> Triple(
            WayneedleIcons.Gps,
            R.string.gate_approximate_title,
            R.string.gate_approximate_body,
        )

        LocationGate.GpsOff -> Triple(
            WayneedleIcons.LocationOff,
            R.string.gate_gps_off_title,
            R.string.gate_gps_off_body,
        )

        LocationGate.Ready -> return
    }
    val action = gateAction(gate, actions)
    MessageCard(
        icon = icon,
        title = stringResource(titleRes),
        body = stringResource(bodyRes),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (action != null) {
                ExpressiveButton(
                    label = stringResource(action.labelRes),
                    onClick = action.onClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (gate == LocationGate.ApproximateOnly) {
                ExpressiveButton(
                    label = stringResource(R.string.gate_action_open_app_settings),
                    onClick = actions.onOpenAppSettings,
                    style = ExpressiveButtonStyle.TEXT,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A compact warning for when location is lost during an active trip; the controls stay available. */
@Composable
private fun GateBanner(gate: LocationGate, actions: DashboardActions, modifier: Modifier = Modifier) {
    val messageRes = when (gate) {
        LocationGate.GpsOff -> R.string.gate_banner_gps_off
        LocationGate.NoGpsHardware -> R.string.gate_no_gps_title
        else -> R.string.gate_banner_permission
    }
    val action = gateAction(gate, actions)
    WarningBanner(
        message = stringResource(messageRes),
        actionLabel = action?.let { stringResource(it.labelRes) },
        onAction = action?.onClick ?: {},
        modifier = modifier,
    )
}

/** A one-line warning strip during an active trip, with an optional fix-it action and close button. */
@Composable
private fun WarningBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val extra = LocalExtraColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = extra.warningContainer,
        contentColor = extra.onWarningContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(WayneedleIcons.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(WayneedleIcons.Close, contentDescription = stringResource(R.string.action_hide_warning))
                }
            }
        }
    }
}

// ---- Recovered trip and trip summary ---------------------------------------------------------------------------

@Composable
internal fun RecoveredTripCard(
    state: DashboardUiState,
    formatter: UiFormatter,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
) {
    val extra = LocalExtraColors.current
    val stats = state.trip.stats
    MessageCard(
        icon = WayneedleIcons.History,
        title = stringResource(R.string.recovered_title),
        body = stringResource(
            R.string.recovered_body,
            formatter.distance(stats.distanceMeters, state.unit),
            stringResource(state.unit.distanceLabelRes),
            formatter.duration(stats.activeDurationMillis),
        ),
        modifier = modifier,
        container = extra.warningContainer.copy(alpha = 0.35f),
        badgeContainer = extra.warningContainer,
        badgeContent = extra.onWarningContainer,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExpressiveButtonRow(
                primary = { buttonModifier, source ->
                    ExpressiveButton(
                        label = stringResource(R.string.action_resume),
                        icon = WayneedleIcons.Play,
                        onClick = actions.onResume,
                        enabled = !state.isFinishing,
                        interactionSource = source,
                        modifier = buttonModifier,
                    )
                },
                secondary = { buttonModifier, source ->
                    ExpressiveButton(
                        label = stringResource(R.string.action_finish),
                        icon = WayneedleIcons.Flag,
                        style = ExpressiveButtonStyle.COMPLETE,
                        onClick = actions.onRequestFinish,
                        enabled = !state.isFinishing,
                        interactionSource = source,
                        modifier = buttonModifier,
                    )
                },
            )
            ExpressiveButton(
                label = stringResource(R.string.action_discard),
                icon = WayneedleIcons.Delete,
                style = ExpressiveButtonStyle.DESTRUCTIVE_TEXT,
                onClick = actions.onRequestDiscard,
                enabled = !state.isFinishing,
                minHeight = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Every per-trip figure as tiles, for the summary card and the trip details. Elevation only when measured. */
@Composable
internal fun tripDetailTiles(
    stats: TripStats,
    unit: SpeedUnit,
    formatter: UiFormatter,
    topLabel: String = stringResource(R.string.stat_top),
    averageLabel: String = stringResource(R.string.stat_average),
): List<@Composable (Modifier) -> Unit> {
    val movingAverageLabel = stringResource(R.string.stat_moving_average)
    val ascentLabel = stringResource(R.string.stat_ascent)
    val descentLabel = stringResource(R.string.stat_descent)
    return buildList {
        add { m -> DistanceStatTile(stats.distanceMeters, unit, formatter, m) }
        add { m -> DurationStatTile(stats.activeDurationMillis, formatter, m) }
        add { m -> SpeedStatTile(topLabel, stats.maxSpeedMps, unit, formatter, m) }
        add { m -> SpeedStatTile(averageLabel, stats.averageSpeedMps, unit, formatter, m) }
        add { m -> SpeedStatTile(movingAverageLabel, stats.movingAverageSpeedMps, unit, formatter, m) }
        add { m ->
            DurationStatTile(
                stats.movingDurationMillis,
                formatter,
                m,
                large = false,
                labelRes = R.string.stat_moving_time,
            )
        }
        if (stats.ascentMeters != null || stats.descentMeters != null) {
            add { m -> ElevationStatTile(ascentLabel, stats.ascentMeters, unit, formatter, m) }
            add { m -> ElevationStatTile(descentLabel, stats.descentMeters, unit, formatter, m) }
        }
    }
}

@Composable
internal fun TripSummaryCard(
    state: DashboardUiState,
    formatter: UiFormatter,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
) {
    val trip = state.trip
    val unit = state.unit
    val savedTripId = trip.savedTripId
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(
                    icon = WayneedleIcons.Check,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.summary_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    trip.startedAtEpochMillis?.let { started ->
                        Text(
                            text = formatter.dateTime(started),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val topLabel = stringResource(R.string.stat_top)
            val averageLabel = stringResource(R.string.stat_average)
            TileRows(
                columns = columnsFor(2),
                tiles = tripDetailTiles(trip.stats, unit, formatter, topLabel, averageLabel),
            )
            TileRows(
                columns = 1,
                tiles = listOf { m -> TotalTimeStatTile(trip.totalDurationMillis, formatter, m) },
            )
            ExpressiveButtonRow(
                primary = { buttonModifier, source ->
                    ExpressiveButton(
                        label = stringResource(R.string.action_done),
                        icon = WayneedleIcons.Check,
                        onClick = actions.onDone,
                        interactionSource = source,
                        modifier = buttonModifier,
                    )
                },
                secondary = { buttonModifier, source ->
                    ExpressiveButton(
                        label = stringResource(R.string.action_view_details),
                        style = ExpressiveButtonStyle.TONAL,
                        onClick = { savedTripId?.let(actions.onViewTrip) },
                        enabled = savedTripId != null,
                        interactionSource = source,
                        modifier = buttonModifier,
                    )
                },
            )
        }
    }
}

// ---- Trip controls ---------------------------------------------------------------------------------------------

@Composable
internal fun TripControls(
    phase: TripPhase,
    isFinishing: Boolean,
    gate: LocationGate,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
) {
    val enabled = !isFinishing
    val finishLabel = stringResource(if (isFinishing) R.string.action_saving else R.string.action_finish)
    when (phase) {
        TripPhase.IDLE -> if (gate == LocationGate.Ready) {
            ExpressiveButton(
                label = stringResource(R.string.action_start),
                icon = WayneedleIcons.Play,
                onClick = actions.onStart,
                minHeight = 72.dp,
                modifier = modifier,
            )
        }

        TripPhase.RUNNING -> ExpressiveButtonRow(
            modifier = modifier,
            primary = { buttonModifier, source ->
                ExpressiveButton(
                    label = stringResource(R.string.action_pause),
                    icon = WayneedleIcons.Pause,
                    style = ExpressiveButtonStyle.TONAL,
                    onClick = actions.onPause,
                    enabled = enabled,
                    minHeight = 72.dp,
                    interactionSource = source,
                    modifier = buttonModifier,
                )
            },
            secondary = { buttonModifier, source ->
                ExpressiveButton(
                    label = finishLabel,
                    icon = WayneedleIcons.Flag,
                    style = ExpressiveButtonStyle.COMPLETE,
                    onClick = actions.onRequestFinish,
                    enabled = enabled,
                    minHeight = 72.dp,
                    interactionSource = source,
                    modifier = buttonModifier,
                )
            },
        )

        TripPhase.PAUSED -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExpressiveButtonRow(
                primary = { buttonModifier, source ->
                    ExpressiveButton(
                        label = stringResource(R.string.action_resume),
                        icon = WayneedleIcons.Play,
                        onClick = actions.onResume,
                        enabled = enabled,
                        minHeight = 72.dp,
                        interactionSource = source,
                        modifier = buttonModifier,
                    )
                },
                secondary = { buttonModifier, source ->
                    ExpressiveButton(
                        label = finishLabel,
                        icon = WayneedleIcons.Flag,
                        style = ExpressiveButtonStyle.COMPLETE,
                        onClick = actions.onRequestFinish,
                        enabled = enabled,
                        minHeight = 72.dp,
                        interactionSource = source,
                        modifier = buttonModifier,
                    )
                },
            )
            ExpressiveButton(
                label = stringResource(R.string.action_discard),
                icon = WayneedleIcons.Delete,
                style = ExpressiveButtonStyle.DESTRUCTIVE_TEXT,
                onClick = actions.onRequestDiscard,
                enabled = enabled,
                minHeight = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TripPhase.FINISHED -> Unit
    }
}

// ---- Dialogs ---------------------------------------------------------------------------------------------------

@Composable
internal fun DashboardDialogs(state: DashboardUiState, formatter: UiFormatter, actions: DashboardActions) {
    when (state.dialog) {
        DashboardDialog.ConfirmFinish -> {
            val stats = state.trip.stats
            val body = if (stats.distanceMeters > 0.0) {
                stringResource(
                    R.string.dialog_finish_body,
                    formatter.distance(stats.distanceMeters, state.unit),
                    stringResource(state.unit.distanceLabelRes),
                    formatter.duration(stats.activeDurationMillis),
                )
            } else {
                stringResource(R.string.dialog_finish_body_empty)
            }
            ConfirmDialog(
                title = stringResource(R.string.dialog_finish_title),
                text = body,
                confirmLabel = stringResource(R.string.action_finish),
                dismissLabel = stringResource(R.string.action_cancel),
                onConfirm = actions.onConfirmFinish,
                onDismiss = actions.onDismissDialog,
                icon = WayneedleIcons.Flag,
            )
        }

        DashboardDialog.ConfirmDiscard -> ConfirmDialog(
            title = stringResource(R.string.dialog_discard_title),
            text = stringResource(R.string.dialog_discard_body),
            confirmLabel = stringResource(R.string.action_discard),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = actions.onConfirmDiscard,
            onDismiss = actions.onDismissDialog,
            destructive = true,
            icon = WayneedleIcons.Delete,
        )

        DashboardDialog.SaveFailed -> ConfirmDialog(
            title = stringResource(R.string.dialog_save_failed_title),
            text = stringResource(R.string.dialog_save_failed_body),
            confirmLabel = stringResource(R.string.action_try_again),
            dismissLabel = stringResource(R.string.action_not_now),
            onConfirm = actions.onConfirmFinish,
            onDismiss = actions.onDismissDialog,
            icon = WayneedleIcons.Warning,
        )

        null -> Unit
    }
}
