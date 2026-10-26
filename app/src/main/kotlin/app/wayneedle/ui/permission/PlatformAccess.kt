package app.wayneedle.ui.permission

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.permissions.AppPermissions
import app.wayneedle.permissions.NotificationStatus
import app.wayneedle.permissions.TripNotificationVisibility
import app.wayneedle.ui.dashboard.LocationGate
import app.wayneedle.ui.dashboard.resolveLocationGate

/** The platform actions behind the permission and GPS cards, plus the resolved [gate]. */
@Immutable
class LocationAccess(
    val gate: LocationGate,
    val requestPermission: () -> Unit,
    val openAppSettings: () -> Unit,
    val openLocationSettings: () -> Unit,
)

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.startSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Some devices have no handler for a settings screen; the card's explanation still tells the user what to do.
    }
}

/**
 * Resolves the [LocationGate] from the engine's permission/provider state plus what only the Activity can
 * know, and provides the actions to fix it. [onPermissionResult] runs after every system permission dialog.
 */
@Composable
fun rememberLocationAccess(
    permission: LocationPermissionState,
    providerEnabled: Boolean,
    hasGpsHardware: Boolean,
    onPermissionResult: () -> Unit,
): LocationAccess {
    val context = LocalContext.current
    val preferences = remember(context) { UiPreferences(context) }
    var askedBefore by remember { mutableStateOf(preferences.locationRequested) }
    var shouldShowRationale by remember { mutableStateOf(context.fineLocationRationale()) }
    var rationaleSeen by remember { mutableStateOf(preferences.locationRationaleSeen) }
    var requestReturnedWithoutDialog by remember { mutableStateOf(false) }
    var requestStartedAt by remember { mutableLongStateOf(0L) }
    val currentOnResult by rememberUpdatedState(onPermissionResult)

    fun observeRationale() {
        shouldShowRationale = context.fineLocationRationale()
        if (shouldShowRationale && !rationaleSeen) {
            rationaleSeen = true
            preferences.locationRationaleSeen = true
        }
    }

    LifecycleResumeEffect(Unit) {
        observeRationale()
        onPauseOrDispose { }
    }
    // A later one-time grant that expires must not look like "don't ask again".
    LaunchedEffect(permission) {
        if (permission == LocationPermissionState.GRANTED_PRECISE && rationaleSeen) {
            rationaleSeen = false
            preferences.locationRationaleSeen = false
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        preferences.locationRequested = true
        askedBefore = true
        observeRationale()
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        // Android answers at once, without a dialog, when it will not ask any more.
        requestReturnedWithoutDialog = !granted && !shouldShowRationale &&
            SystemClock.elapsedRealtime() - requestStartedAt < NO_DIALOG_RESULT_MILLIS
        currentOnResult()
    }

    val gate = resolveLocationGate(
        permission = permission,
        providerEnabled = providerEnabled,
        hasGpsHardware = hasGpsHardware,
        askedBefore = askedBefore,
        shouldShowRationale = shouldShowRationale,
        systemStoppedAsking = rationaleSeen || requestReturnedWithoutDialog,
    )
    return remember(gate, launcher, context) {
        LocationAccess(
            gate = gate,
            requestPermission = {
                requestStartedAt = SystemClock.elapsedRealtime()
                launcher.launch(AppPermissions.location)
            },
            openAppSettings = {
                context.startSettings(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
            openLocationSettings = { context.startSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )
    }
}

/** A result faster than this came without a dialog: no human answers a permission dialog that quickly. */
private const val NO_DIALOG_RESULT_MILLIS = 350L

private fun Context.fineLocationRationale(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
}

/**
 * Returns a function that asks for the notification permission (Android 13+) at most once, the first time
 * it is called while the permission is missing. A trip never waits for the answer: the notification only
 * makes the foreground service visible.
 */
@Composable
fun rememberNotificationRequester(): () -> Unit {
    val context = LocalContext.current
    val preferences = remember(context) { UiPreferences(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, launcher) {
        {
            val permission = AppPermissions.notifications
            val granted = permission == null ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (permission != null && !granted && !preferences.notificationsRequested) {
                preferences.notificationsRequested = true
                launcher.launch(permission)
            }
        }
    }
}

/** Whether the trip notification can be seen, re-checked whenever the app returns, and how to fix it. */
@Immutable
class NotificationAccess(val blocked: Boolean, val openSettings: () -> Unit)

@Composable
fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    val status = remember(context) { NotificationStatus(context) }
    var blocked by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        blocked = status.tripNotificationVisibility() != TripNotificationVisibility.VISIBLE
        onPauseOrDispose { }
    }
    return remember(blocked, context) {
        NotificationAccess(
            blocked = blocked,
            openSettings = {
                context.startSettings(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        )
    }
}
