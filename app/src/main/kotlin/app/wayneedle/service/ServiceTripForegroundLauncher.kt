package app.wayneedle.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.wayneedle.domain.LocationPermissionChecker
import app.wayneedle.domain.TripForegroundLauncher
import app.wayneedle.domain.model.LocationPermissionState
import app.wayneedle.permissions.AndroidLocationPermissionChecker

/** The two platform calls the launcher needs, so the decision logic runs on the JVM with a fake. */
internal interface ServiceHost {
    fun startForegroundService()

    fun stopService()
}

/** Where outcomes go. Android implementation logs; tests record. Kept separate so no `android.util.Log` runs on the JVM. */
internal interface LauncherEvents {
    fun onStartResult(result: ForegroundStartResult)

    fun onStopFailed(error: RuntimeException)
}

private object LogLauncherEvents : LauncherEvents {
    private const val TAG = "WayneedleLauncher"

    override fun onStartResult(result: ForegroundStartResult) {
        when (result) {
            ForegroundStartResult.Started -> Unit
            ForegroundStartResult.SkippedNoLocationPermission ->
                Log.w(TAG, "Not starting the tracking service: no location permission")
            is ForegroundStartResult.Failed ->
                Log.w(TAG, "Tracking service start rejected: ${result.failure}", result.cause)
        }
    }

    override fun onStopFailed(error: RuntimeException) {
        Log.w(TAG, "stopService failed", error)
    }
}

private class AndroidServiceHost(private val context: Context) : ServiceHost {
    override fun startForegroundService() {
        ContextCompat.startForegroundService(context, TrackingService.startIntent(context))
    }

    override fun stopService() {
        context.stopService(Intent(context, TrackingService::class.java))
    }
}

/**
 * Starts and stops [TrackingService] for the tracking engine.
 *
 * Both calls are safe from any thread, idempotent and never throw: a rejected start (background-start
 * limits, missing permission) is logged and recorded in [TrackingServiceStatus] instead of crashing the app.
 * Repeated [start] calls are harmless because the service handles each START command idempotently, and
 * no state is kept here that could go stale when the service stops itself.
 */
class ServiceTripForegroundLauncher internal constructor(
    private val host: ServiceHost,
    private val permissionChecker: LocationPermissionChecker,
    private val events: LauncherEvents,
) : TripForegroundLauncher {
    constructor(
        context: Context,
        permissionChecker: LocationPermissionChecker = AndroidLocationPermissionChecker(context),
    ) : this(AndroidServiceHost(context.applicationContext), permissionChecker, LogLauncherEvents)

    override fun start() {
        launch()
    }

    override fun stop() {
        try {
            host.stopService()
        } catch (e: RuntimeException) {
            events.onStopFailed(e)
        }
    }

    internal fun launch(): ForegroundStartResult {
        val result = attemptStart()
        TrackingServiceStatus.record(result)
        events.onStartResult(result)
        return result
    }

    private fun attemptStart(): ForegroundStartResult {
        // A location foreground service needs at least one location permission (Android 14+ throws otherwise).
        if (permissionChecker.current() == LocationPermissionState.DENIED) {
            return ForegroundStartResult.SkippedNoLocationPermission
        }
        return try {
            host.startForegroundService()
            ForegroundStartResult.Started
        } catch (e: RuntimeException) {
            ForegroundStartResult.Failed(ForegroundFailure.classify(e), e)
        }
    }
}
