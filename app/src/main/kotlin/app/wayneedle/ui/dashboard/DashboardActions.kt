package app.wayneedle.ui.dashboard

import androidx.compose.runtime.Immutable

/** Everything the dashboard can ask for, so the screen stays stateless. Defaults are no-ops for previews/tests. */
@Immutable
class DashboardActions(
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    val onRequestFinish: () -> Unit = {},
    val onRequestDiscard: () -> Unit = {},
    val onConfirmFinish: () -> Unit = {},
    val onConfirmDiscard: () -> Unit = {},
    val onDismissDialog: () -> Unit = {},
    val onDone: () -> Unit = {},
    val onViewTrip: (String) -> Unit = {},
    val onRequestPermission: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onOpenLocationSettings: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onOpenBatterySaverSettings: () -> Unit = {},
    val onDismissBatterySaverWarning: () -> Unit = {},
    val onOpenHud: () -> Unit = {},
)
