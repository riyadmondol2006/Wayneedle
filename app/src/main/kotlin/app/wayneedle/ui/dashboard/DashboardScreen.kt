package app.wayneedle.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.ui.format.UiFormatter
import app.wayneedle.ui.format.rememberUiFormatter
import app.wayneedle.ui.speedometer.GAUGE_HEIGHT_FRACTION
import kotlin.math.max

/**
 * Height of everything on a compact screen except the gauge (header, stats, controls, gaps), at font scale 1,
 * for the tallest state (PAUSED: two rows of controls). Measured on a 411x891 dp screen: about 321 dp scrolling
 * content plus 148 dp of pinned controls. The gauge size must not depend on the phase, or it would jump.
 */
private const val NON_GAUGE_HEIGHT_DP = 470f
private const val MIN_GAUGE_WIDTH_DP = 260f
private const val WIDE_LAYOUT_MIN_WIDTH_DP = 600
private val ScreenPadding = 16.dp

/**
 * The main screen: speedometer, trip statistics and trip controls. Stateless; see [DashboardRoute].
 *
 * Portrait: header, gauge and statistics scroll together while the controls stay pinned at the bottom,
 * and at normal font size everything fits one 411x891 dp screen without scrolling. Wide windows put the
 * gauge beside the statistics and controls.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    gate: LocationGate,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberUiFormatter()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH_DP.dp && maxWidth > maxHeight) {
            WideDashboard(state, gate, actions, formatter, maxWidth, maxHeight)
        } else {
            CompactDashboard(state, gate, actions, formatter, maxWidth, maxHeight)
        }
    }
    DashboardDialogs(state, formatter, actions)
}

@Composable
private fun CompactDashboard(
    state: DashboardUiState,
    gate: LocationGate,
    actions: DashboardActions,
    formatter: UiFormatter,
    availableWidth: Dp,
    availableHeight: Dp,
) {
    val fontScale = LocalDensity.current.fontScale
    val reserved = (NON_GAUGE_HEIGHT_DP * max(1f, fontScale)).dp
    val gaugeWidth = minOf(availableWidth - ScreenPadding * 2, (availableHeight - reserved) / GAUGE_HEIGHT_FRACTION)
        .coerceAtLeast(MIN_GAUGE_WIDTH_DP.dp)
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DashboardHeader(state.unit, state.gps, Modifier.fillMaxWidth(), onOpenHud = actions.onOpenHud)
            DashboardGauge(state.gauge, state.trip.phase, state.trip.awaitingFix, Modifier.width(gaugeWidth))
            DashboardPanel(state, gate, actions, formatter, Modifier.fillMaxWidth())
        }
        if (showsPinnedControls(state.trip.phase, state.trip.recovered, gate)) {
            TripControls(
                phase = state.trip.phase,
                isFinishing = state.isFinishing,
                gate = gate,
                actions = actions,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun WideDashboard(
    state: DashboardUiState,
    gate: LocationGate,
    actions: DashboardActions,
    formatter: UiFormatter,
    availableWidth: Dp,
    availableHeight: Dp,
) {
    val gaugeWidth = minOf((availableWidth - ScreenPadding * 3) / 2, (availableHeight - 72.dp) / GAUGE_HEIGHT_FRACTION)
        .coerceAtLeast(MIN_GAUGE_WIDTH_DP.dp)
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(ScreenPadding),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DashboardHeader(state.unit, state.gps, Modifier.fillMaxWidth(), onOpenHud = actions.onOpenHud)
            DashboardGauge(state.gauge, state.trip.phase, state.trip.awaitingFix, Modifier.width(gaugeWidth))
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            DashboardPanel(state, gate, actions, formatter, Modifier.fillMaxWidth())
            if (showsPinnedControls(state.trip.phase, state.trip.recovered, gate)) {
                TripControls(state.trip.phase, state.isFinishing, gate, actions, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Whether the trip controls are shown (pinned to the bottom on compact screens). */
internal fun showsPinnedControls(phase: TripPhase, recovered: Boolean, gate: LocationGate): Boolean = when (phase) {
    TripPhase.IDLE -> gate == LocationGate.Ready
    TripPhase.RUNNING -> true
    TripPhase.PAUSED -> !recovered
    TripPhase.FINISHED -> false
}
