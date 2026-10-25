package app.wayneedle.ui.permission

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
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
    val currentOnResult by rememberUpdatedState(onPermissionResult)

    LifecycleResumeEffect(Unit) {
        shouldShowRationale = context.fineLocationRationale()
        onPauseOrDispose { }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        preferences.locationRequested = true
        askedBefore = true
        shouldShowRationale = context.fineLocationRationale()
        currentOnResult()
    }

    val gate = resolveLocationGate(permission, providerEnabled, hasGpsHardware, askedBefore, shouldShowRationale)
    return remember(gate, launcher, context) {
        LocationAccess(
            gate = gate,
            requestPermission = { launcher.launch(AppPermissions.location) },
            openAppSettings = {
                context.startSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            openLocationSettings = { context.startSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )
    }
}

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
