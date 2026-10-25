package app.wayneedle.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import app.wayneedle.WayneedleApplication
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TripPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service (type `location`) that hosts the ongoing "recording" notification and keeps the
 * process prioritised while a trip is RUNNING or PAUSED. It never talks to `LocationManager`: the
 * `TrackingController` owns the only location subscription. This service observes
 * [TrackingController.state] and forwards Pause/Resume taps from the notification; it does no polling and
 * holds no wake lock.
 *
 * Lifecycle: started by [ServiceTripForegroundLauncher] as a direct result of a user action while the app is
 * visible; promotes itself to the foreground in `onStartCommand` before doing anything else; stops itself
 * as soon as the trip is neither RUNNING nor PAUSED. `START_NOT_STICKY`: after process death nothing is
 * restarted from the background; the trip is recovered as PAUSED when the user reopens the app.
 * `onTaskRemoved` is deliberately not overridden and the manifest keeps the default `stopWithTask="false"`:
 * swiping the app from recents does not end the service or the trip.
 *
 * ## Compatibility notes (minSdk 26, compile/target 37)
 *
 * * **Android 8 (26)**: `startForegroundService` obliges `startForeground` within a few seconds, so promotion is
 *   the first thing `onStartCommand` does, with a unit-independent placeholder notification.
 * * **Android 10 (29)**: the service type is passed to `startForeground` (below 29 the type-less overload is used
 *   via `ServiceCompat`); the manifest declares `foregroundServiceType="location"`.
 * * **Android 12 (31)**: apps may not start a foreground service from the background
 *   (`ForegroundServiceStartNotAllowedException`). Exempt: the app is visible, or the user taps a
 *   notification action (our Pause/Resume `PendingIntent.getService`). Notification trampolines are avoided: the
 *   content intent is a direct activity `PendingIntent`. Notifications carry `FOREGROUND_SERVICE_IMMEDIATE` so
 *   they appear at once instead of after the 10 s delay.
 * * **Android 13 (33)**: `POST_NOTIFICATIONS` is a runtime permission. A foreground service still runs without
 *   it; the notification is then only listed in the Foreground Services Task Manager, not the shade.
 *   Users can stop the app from that Task Manager; no callback is delivered (no `onDestroy`), so trip state must
 *   already be checkpointed (the tracker does this).
 * * **Android 14 (34)**: a service type and the matching `FOREGROUND_SERVICE_LOCATION` permission are mandatory,
 *   and `ACCESS_COARSE_LOCATION` or `ACCESS_FINE_LOCATION` must be granted before `startForeground`, otherwise
 *   `SecurityException`. A location foreground service cannot be created from the background (without
 *   `ACCESS_BACKGROUND_LOCATION`, which Wayneedle deliberately does not request); the failure surfaces as
 *   `SecurityException` and is reported through [TrackingServiceStatus], never thrown. Ongoing
 *   notifications became user-dismissible: the service keeps running, so a delete intent stops further
 *   refreshes (re-posting every second would make the notification impossible to dismiss) until the trip
 *   phase changes.
 * * **Android 15 (35)**: foreground-service timeouts exist only for `dataSync`, `mediaProcessing` and
 *   `shortService`; `location` has none. `onTimeout` is still implemented defensively (pause the trip, leave the
 *   foreground), because ignoring a timeout is a fatal `RemoteServiceException`.
 * * **Android 16 (36)** and **Android 17 (37)**: no documented change to location foreground services. Android 17
 *   adds app memory limits (a process killed that way looks like process death and is recovered as PAUSED), and
 *   the while-in-use enforcement it documents targets background audio only. Custom notification views are
 *   size-limited; this notification uses the standard template.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy(LazyThreadSafetyMode.NONE) { TrackingNotification(this) }
    private val gate = NotificationUpdateGate()
    private val startGraceExpired = MutableStateFlow(false)

    private var controller: TrackingController? = null
    private var observeJob: Job? = null
    private var graceJob: Job? = null
    private var isForeground = false
    private var hasSeenActiveTrip = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val command = ServiceCommand.fromAction(intent?.action)
        val dependencies = resolveDependencies()
        if (dependencies == null) {
            // Not wired (or wiring failed): still honour the startForegroundService() contract, then leave.
            if (command == ServiceCommand.START) {
                enterForeground(NotificationContent.placeholder(TripPhase.IDLE, notifications.labels))
            }
            stopCleanly()
            return START_NOT_STICKY
        }
        controller = dependencies.controller
        when (command) {
            ServiceCommand.PAUSE -> dependencies.controller.pauseTrip()
            ServiceCommand.RESUME -> dependencies.controller.resumeTrip()
            ServiceCommand.NOTIFICATION_DISMISSED -> gate.onDismissedByUser(dependencies.controller.state.value.trip.phase)
            ServiceCommand.START, ServiceCommand.UNKNOWN -> Unit
        }
        if (command == ServiceCommand.START) armStartGrace()
        val current = dependencies.controller.state.value.trip.phase
        val mustBeForeground = command == ServiceCommand.START || TrackingServicePolicy.shouldRun(current)
        if (mustBeForeground && !isForeground) {
            if (!enterForeground(NotificationContent.placeholder(current, notifications.labels))) return START_NOT_STICKY
        } else if (!isForeground) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startObserving(dependencies)
        return START_NOT_STICKY
    }

    /**
     * Android 15+ calls this when a foreground-service type exceeds its time limit. `location` has none, but
     * a timeout that is not answered with a stop is fatal, so the trip is paused (persisting its checkpoint)
     * and the service leaves the foreground. The user resumes from the app, which also resets any limits.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    /** Android 14 variant of [onTimeout]; only delivered for `shortService`, which this service never uses. */
    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    override fun onDestroy() {
        observeJob = null
        graceJob = null
        scope.cancel()
        isForeground = false
        super.onDestroy()
    }

    private class Dependencies(val controller: TrackingController, val settings: SettingsRepository)

    private fun resolveDependencies(): Dependencies? = try {
        val container = (application as? WayneedleApplication)?.container
        container?.let { Dependencies(it.trackingController, it.settingsRepository) }
    } catch (e: RuntimeException) {
        // "Not wired yet" is an IllegalStateException; anything else thrown while building the graph must not crash a service either.
        Log.w(TAG, "Dependencies unavailable; stopping", e)
        null
    }

    /** Returns false (after stopping the service) when the platform refused the foreground state. */
    private fun enterForeground(content: NotificationContent): Boolean {
        val result = promote(notifications.build(content))
        TrackingServiceStatus.record(result)
        return if (result is ForegroundStartResult.Failed) {
            Log.w(TAG, "Could not enter the foreground: ${result.failure}", result.cause)
            stopCleanly()
            false
        } else {
            isForeground = true
            true
        }
    }

    private fun promote(notification: Notification): ForegroundStartResult = try {
        ServiceCompat.startForeground(
            this,
            TrackingNotificationIds.NOTIFICATION_ID,
            notification,
            locationServiceType(),
        )
        ForegroundStartResult.Started
    } catch (e: RuntimeException) {
        // SecurityException (permission / while-in-use), ForegroundServiceStartNotAllowedException (an
        // IllegalStateException), or the Android 14 service-type exceptions: none may crash the app.
        ForegroundStartResult.Failed(ForegroundFailure.classify(e), e)
    }

    /** The type argument only exists from Android 10; `ServiceCompat` ignores it below that. */
    private fun locationServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    private fun armStartGrace() {
        graceJob?.cancel()
        hasSeenActiveTrip = false
        startGraceExpired.value = false
        graceJob = scope.launch {
            delay(TrackingServicePolicy.START_GRACE_MILLIS)
            startGraceExpired.value = true
        }
    }

    private fun startObserving(dependencies: Dependencies) {
        if (observeJob?.isActive == true) return
        val controller = dependencies.controller
        observeJob = scope.launch {
            launch { observeLifecycle(controller) }
            launch { observeContent(controller, dependencies.settings) }
        }
    }

    private suspend fun observeLifecycle(controller: TrackingController) {
        combine(
            controller.state.map { it.trip.phase }.distinctUntilChanged(),
            startGraceExpired,
        ) { phase, expired -> phase to expired }
            .collect { (phase, expired) ->
                if (TrackingServicePolicy.shouldRun(phase)) hasSeenActiveTrip = true
                if (TrackingServicePolicy.directive(phase, hasSeenActiveTrip, expired) == ServiceDirective.STOP) {
                    stopCleanly()
                }
            }
    }

    private suspend fun observeContent(controller: TrackingController, settings: SettingsRepository) {
        val unit = settings.settings
            .map { it.speedUnit }
            .distinctUntilChanged()
            .catch { emit(SpeedUnit.KMH) }
        combine(controller.state, unit) { state, speedUnit ->
            NotificationContent.from(state, speedUnit, notifications.labels, Locale.getDefault())
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collectLatest { content -> postWhenAllowed(content) }
    }

    /** Applies [NotificationUpdateGate]: skips unchanged content, delays to at most one update per second. */
    private suspend fun postWhenAllowed(content: NotificationContent) {
        while (isForeground) {
            when (val decision = gate.decide(content, SystemClock.elapsedRealtime())) {
                NotificationUpdateGate.Decision.Skip -> return
                NotificationUpdateGate.Decision.PostNow -> {
                    notifications.post(content)
                    gate.onPosted(content, SystemClock.elapsedRealtime())
                    return
                }
                is NotificationUpdateGate.Decision.PostAfter -> delay(decision.delayMillis)
            }
        }
    }

    private fun handleTimeout() {
        Log.w(TAG, "Foreground service timeout; pausing the trip and leaving the foreground")
        controller?.pauseTrip()
        stopCleanly()
    }

    private fun stopCleanly() {
        observeJob?.cancel()
        observeJob = null
        graceJob?.cancel()
        graceJob = null
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf(lastStartId)
    }

    companion object {
        private const val TAG = "WayneedleService"

        internal fun startIntent(context: Context): Intent = commandIntent(context, TrackingServiceActions.START)

        /** Explicit intent for this service; actions double as identity for the notification `PendingIntent`s. */
        internal fun commandIntent(context: Context, action: String): Intent =
            Intent(context, TrackingService::class.java).setAction(action)
    }
}
