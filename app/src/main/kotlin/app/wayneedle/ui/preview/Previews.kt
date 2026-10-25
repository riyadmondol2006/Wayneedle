package app.wayneedle.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.wayneedle.domain.model.AppSettings
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.ThemeMode
import app.wayneedle.ui.components.NavigationItem
import app.wayneedle.ui.components.WayneedleNavigationBar
import app.wayneedle.ui.dashboard.DashboardActions
import app.wayneedle.ui.dashboard.DashboardDialog
import app.wayneedle.ui.dashboard.DashboardScreen
import app.wayneedle.ui.dashboard.LocationGate
import app.wayneedle.ui.history.HistoryScreen
import app.wayneedle.ui.history.HistoryUiState
import app.wayneedle.ui.history.groupTripsByDay
import app.wayneedle.ui.navigation.Destination
import app.wayneedle.ui.settings.SettingsCallbacks
import app.wayneedle.ui.settings.SettingsScreen
import app.wayneedle.ui.settings.SettingsUiState
import app.wayneedle.ui.speedometer.GaugeScale
import app.wayneedle.ui.speedometer.GaugeStatus
import app.wayneedle.ui.speedometer.GaugeStatusTone
import app.wayneedle.ui.speedometer.GpsSignalIndicator
import app.wayneedle.ui.speedometer.SpeedReading
import app.wayneedle.ui.speedometer.SpeedUnavailableReason
import app.wayneedle.ui.speedometer.Speedometer
import app.wayneedle.ui.theme.WayneedleTheme
import app.wayneedle.ui.tripdetail.TripDetailScreen
import app.wayneedle.ui.tripdetail.TripDetailUiState
import java.time.ZoneOffset

private const val PIXEL_WIDTH_DP = 411
private const val PIXEL_HEIGHT_DP = 891

/** Light, dark and 200 % font-scale renderings on a Pixel 6 Pro sized canvas. */
@Preview(name = "Light", widthDp = PIXEL_WIDTH_DP, heightDp = PIXEL_HEIGHT_DP, showBackground = true)
@Preview(
    name = "Dark",
    widthDp = PIXEL_WIDTH_DP,
    heightDp = PIXEL_HEIGHT_DP,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(
    name = "Large font",
    widthDp = PIXEL_WIDTH_DP,
    heightDp = PIXEL_HEIGHT_DP,
    showBackground = true,
    fontScale = 2f,
)
annotation class PixelPreviews

/** Smaller canvas for single components. */
@Preview(name = "Light", widthDp = 360, showBackground = true)
@Preview(name = "Dark", widthDp = 360, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large font", widthDp = 360, showBackground = true, fontScale = 2f)
annotation class ComponentPreviews

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    WayneedleTheme(themeMode = ThemeMode.SYSTEM, dynamicColor = false) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

// ---- Dashboard states --------------------------------------------------------------------------------------------

@PixelPreviews
@Composable
internal fun DashboardIdleReadyPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.idleStationary), LocationGate.Ready, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardRunningPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.running), LocationGate.Ready, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardRunningMphPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.running, SpeedUnit.MPH), LocationGate.Ready, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardAwaitingFixPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.runningAwaitingFix), LocationGate.Ready, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardPausedPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.paused), LocationGate.Ready, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardRecoveredPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.recovered), LocationGate.Ready, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardFinishedPreview() = PreviewFrame {
    DashboardScreen(
        PreviewData.dashboard(PreviewData.finished, savedTrip = PreviewData.savedTrip),
        LocationGate.Ready,
        DashboardActions(),
    )
}

@PixelPreviews
@Composable
internal fun DashboardSearchingPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.idleSearching), LocationGate.Ready, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardPermissionPreview() = PreviewFrame {
    DashboardScreen(
        PreviewData.dashboard(PreviewData.permissionDenied),
        LocationGate.NeedsRationale,
        DashboardActions(),
    )
}

@PixelPreviews
@Composable
internal fun DashboardBlockedPreview() = PreviewFrame {
    DashboardScreen(
        PreviewData.dashboard(PreviewData.permissionDenied),
        LocationGate.PermanentlyDenied,
        DashboardActions(),
    )
}

@PixelPreviews
@Composable
internal fun DashboardApproximatePreview() = PreviewFrame {
    DashboardScreen(
        PreviewData.dashboard(PreviewData.approximateOnly),
        LocationGate.ApproximateOnly,
        DashboardActions(),
    )
}

@PixelPreviews
@Composable
internal fun DashboardGpsOffPreview() = PreviewFrame {
    DashboardScreen(PreviewData.dashboard(PreviewData.providerOff), LocationGate.GpsOff, DashboardActions())
}

@PixelPreviews
@Composable
internal fun DashboardNoGpsPreview() = PreviewFrame {
    DashboardScreen(
        PreviewData.dashboard(PreviewData.permissionDenied, hasGpsHardware = false),
        LocationGate.NoGpsHardware,
        DashboardActions(),
    )
}

@PixelPreviews
@Composable
internal fun DashboardFinishDialogPreview() = PreviewFrame {
    DashboardScreen(
        PreviewData.dashboard(PreviewData.running, dialog = DashboardDialog.ConfirmFinish),
        LocationGate.Ready,
        DashboardActions(),
    )
}

@PixelPreviews
@Composable
internal fun DashboardTripGpsLostPreview() = PreviewFrame {
    DashboardScreen(
        PreviewData.dashboard(PreviewData.running.copy(gps = PreviewData.gpsOff, liveSpeed = LiveSpeed.Unavailable)),
        LocationGate.GpsOff,
        DashboardActions(),
    )
}

// ---- Components --------------------------------------------------------------------------------------------------

@ComponentPreviews
@Composable
internal fun SpeedometerVariantsPreview() = PreviewFrame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Speedometer(SpeedReading.Available(112f), GaugeScale.base(SpeedUnit.KMH))
        Speedometer(SpeedReading.Available(0f), GaugeScale.base(SpeedUnit.MPH))
        Speedometer(
            SpeedReading.Available(52f),
            GaugeScale.base(SpeedUnit.MPH),
            status = GaugeStatus("Paused", GaugeStatusTone.CAUTION, prominent = true),
            muted = true,
        )
        Speedometer(SpeedReading.Unavailable(SpeedUnavailableReason.SEARCHING), GaugeScale.base(SpeedUnit.KMH))
    }
}

@ComponentPreviews
@Composable
internal fun GpsIndicatorPreview() = PreviewFrame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GpsSignalIndicator(PreviewData.goodGps, SpeedUnit.KMH)
        GpsSignalIndicator(PreviewData.fairGps, SpeedUnit.MPH)
        GpsSignalIndicator(PreviewData.searchingGps, SpeedUnit.KMH)
        GpsSignalIndicator(PreviewData.gpsOff, SpeedUnit.KMH)
    }
}

@ComponentPreviews
@Composable
internal fun NavigationBarPreview() = PreviewFrame {
    WayneedleNavigationBar(
        items = Destination.topLevel,
        selectedRoute = Destination.topLevel[1].route,
        onSelect = { _: NavigationItem -> },
    )
}

// ---- Other screens -----------------------------------------------------------------------------------------------

@PixelPreviews
@Composable
internal fun HistoryPreview() = PreviewFrame {
    HistoryScreen(
        state = HistoryUiState(isLoading = false, days = groupTripsByDay(PreviewData.trips, ZoneOffset.UTC)),
        onOpenTrip = {},
        onRequestDelete = {},
        onConfirmDelete = {},
        onCancelDelete = {},
    )
}

@PixelPreviews
@Composable
internal fun HistoryEmptyPreview() = PreviewFrame {
    HistoryScreen(
        state = HistoryUiState(isLoading = false),
        onOpenTrip = {},
        onRequestDelete = {},
        onConfirmDelete = {},
        onCancelDelete = {},
    )
}

@PixelPreviews
@Composable
internal fun TripDetailPreview() = PreviewFrame {
    TripDetailScreen(
        state = TripDetailUiState.Loaded(PreviewData.trips[1], SpeedUnit.KMH, confirmingDelete = false),
        onBack = {},
        onRequestDelete = {},
        onConfirmDelete = {},
        onCancelDelete = {},
    )
}

@PixelPreviews
@Composable
internal fun SettingsPreview() = PreviewFrame {
    SettingsScreen(
        state = SettingsUiState(settings = AppSettings(), tripCount = PreviewData.trips.size),
        callbacks = SettingsCallbacks(),
        dynamicColorSupported = true,
        versionName = "0.1.0",
    )
}
