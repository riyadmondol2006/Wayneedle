package app.wayneedle.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.wayneedle.alert.signalSpeedAlert
import app.wayneedle.domain.CommandResult
import app.wayneedle.ui.permission.rememberBackgroundAccess
import app.wayneedle.ui.permission.rememberLocationAccess
import app.wayneedle.ui.permission.rememberNotificationAccess
import app.wayneedle.ui.permission.rememberNotificationRequester

/** Connects [DashboardViewModel] and the platform (permissions, settings screens) to [DashboardScreen]. */
@Composable
fun DashboardRoute(
    onOpenTrip: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenHud: () -> Unit = {},
) {
    val viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val access = rememberLocationAccess(
        permission = state.permission,
        providerEnabled = state.gps.providerEnabled,
        hasGpsHardware = state.hasGpsHardware,
        onPermissionResult = viewModel::refreshEnvironment,
    )
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.speedAlerts.collect { sound -> context.signalSpeedAlert(sound) }
    }
    val requestNotifications = rememberNotificationRequester()
    val notifications = rememberNotificationAccess()
    val background = rememberBackgroundAccess()
    val actions = remember(viewModel, access, requestNotifications, notifications, background, onOpenTrip, onOpenHud) {
        DashboardActions(
            onStart = {
                if (viewModel.startTrip() is CommandResult.Ok) requestNotifications()
            },
            onPause = viewModel::pauseTrip,
            onResume = { viewModel.resumeTrip() },
            onRequestFinish = viewModel::requestFinish,
            onRequestDiscard = viewModel::requestDiscard,
            onConfirmFinish = viewModel::confirmFinish,
            onConfirmDiscard = viewModel::confirmDiscard,
            onDismissDialog = viewModel::dismissDialog,
            onDone = viewModel::dismissFinished,
            onViewTrip = onOpenTrip,
            onRequestPermission = access.requestPermission,
            onOpenAppSettings = access.openAppSettings,
            onOpenLocationSettings = access.openLocationSettings,
            onOpenNotificationSettings = notifications.openSettings,
            onOpenBatterySaverSettings = background.openBatterySaverSettings,
            onDismissBatterySaverWarning = background.dismissBatterySaverWarning,
            onOpenHud = onOpenHud,
        )
    }
    DashboardScreen(
        state = state.copy(notificationsBlocked = notifications.blocked, backgroundRisks = background.risks),
        gate = access.gate,
        actions = actions,
        modifier = modifier,
    )
}
