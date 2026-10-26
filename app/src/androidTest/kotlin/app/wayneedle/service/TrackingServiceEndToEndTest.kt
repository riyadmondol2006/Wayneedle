package app.wayneedle.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.wayneedle.WayneedleApplication
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.TripPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production foreground-service path on a real device: the app's own container (real tracker, real
 * `AndroidLocationSource`, real `TrackingService`, real notification) driven by scripted mock fixes.
 *
 * It checks the property that makes a speedometer usable outside the app: once the activity is gone, the
 * foreground service keeps GPS delivery alive and the trip keeps accumulating. Fixes are scripted, not
 * satellite measurements. Needs the mock-location app-op (see `GpsPipelineInstrumentedTest`).
 */
@RunWith(AndroidJUnit4::class)
class TrackingServiceEndToEndTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val locationManager: LocationManager = context.getSystemService(LocationManager::class.java)
    private val notificationManager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val container = (context.applicationContext as WayneedleApplication).container
    private val tracker = container.trackingController

    private var mockProviderAdded = false
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var latitude = 52.0
    private var savedTripId: String? = null

    @Before
    fun setUp() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                ProviderProperties.POWER_USAGE_LOW,
                ProviderProperties.ACCURACY_FINE,
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            mockProviderAdded = true
        } catch (_: SecurityException) {
            assumeTrue(
                "Mock location not allowed: adb -s <serial> shell appops set ${context.packageName} " +
                    "android:mock_location allow",
                false,
            )
        }
        // The user is looking at the app when they tap Start; Android only allows starting the location
        // foreground service then.
        activity = ActivityScenario.launch(ComponentActivity::class.java)
        tracker.refreshEnvironment()
        tracker.setUiVisible(true)
    }

    @After
    fun tearDown() {
        // Leave nothing behind: no running trip, no service, no stored test trip, no mock provider.
        val phase = tracker.state.value.trip.phase
        if (phase == TripPhase.RUNNING || phase == TripPhase.PAUSED) tracker.discardTrip()
        tracker.dismissFinished()
        tracker.setUiVisible(false)
        savedTripId?.let { id -> runBlocking { container.tripRepository.deleteTrip(id) } }
        activity?.close()
        if (mockProviderAdded) {
            try {
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (_: IllegalArgumentException) {
                // Already gone.
            }
        }
    }

    @Test
    fun theForegroundServiceKeepsTheTripAliveInTheBackgroundAndObeysTheNotification() {
        pushUntilListening()
        assertEquals(CommandResult.Ok, tracker.startTrip())

        // 1. The service promotes itself and posts the ongoing notification.
        val recording = awaitNotification("recording notification") {
            it.extras.getString(Notification.EXTRA_TITLE) == "Wayneedle – Recording"
        }
        // The notification becomes visible a moment before the service records that it is in the foreground.
        awaitCondition("the service reports being in the foreground") { TrackingServiceStatus.isInForeground }
        assertTrue("the notification is ongoing", recording.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse("Android accepted the foreground service", tracker.state.value.backgroundRecordingUnavailable)
        assertEquals("one action: Pause", 1, recording.actions.size)

        // 2. The user leaves the app: no activity any more, only the foreground service.
        drive(steps = 4)
        val beforeLeaving = tracker.state.value.trip.stats.distanceMeters
        activity?.close()
        activity = null
        tracker.setUiVisible(false)
        drive(steps = 5)
        val afterBackground = tracker.state.value
        assertEquals(TripPhase.RUNNING, afterBackground.trip.phase)
        assertTrue(
            "GPS must keep flowing in the background (before=$beforeLeaving, after=${afterBackground.trip.stats.distanceMeters})",
            afterBackground.trip.stats.distanceMeters >= beforeLeaving + 30.0,
        )
        awaitNotification("distance in the notification") {
            it.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("km")
        }

        // 3. Pause from the notification, as the user would from the shade.
        recording.actions[0].actionIntent.send()
        val paused = awaitState("paused from the notification") { it.trip.phase == TripPhase.PAUSED }
        val frozen = paused.trip.stats
        val pausedNotification = awaitNotification("paused notification") {
            it.extras.getString(Notification.EXTRA_TITLE) == "Wayneedle – Paused"
        }
        assertEquals("one action: Resume", 1, pausedNotification.actions.size)
        assertTrue("the service stays up while paused", TrackingServiceStatus.isInForeground)
        drive(steps = 3)
        assertEquals("nothing accrues while paused", frozen, tracker.state.value.trip.stats)

        // 4. Resume from the notification (the app is still closed: a background start Android must accept).
        pausedNotification.actions[0].actionIntent.send()
        val resumed = awaitState("resumed from the notification") { it.trip.phase == TripPhase.RUNNING }
        assertFalse("resuming from the notification must not be refused", resumed.backgroundRecordingUnavailable)
        awaitNotification("recording again") {
            it.extras.getString(Notification.EXTRA_TITLE) == "Wayneedle – Recording"
        }

        // 5. Finish: the trip is saved and the service goes away on its own.
        val result = runBlocking { tracker.finishTrip() }
        val saved = (result as FinishResult.Saved).trip
        savedTripId = saved.id
        assertNotNull(runBlocking { container.tripRepository.getTrip(saved.id) })
        awaitCondition("the service leaves the foreground") { !TrackingServiceStatus.isInForeground }
        awaitCondition("the notification is removed") { findNotification { true } == null }
    }

    @Test
    fun stoppingImmediatelyAfterStartingNeverCrashesTheProcess() {
        // Regression for ForegroundServiceDidNotStartInTimeException: stopService() before startForeground().
        pushUntilListening()
        assertEquals(CommandResult.Ok, tracker.startTrip())
        val discarded = tracker.discardTrip()
        assertEquals(CommandResult.Ok, discarded)
        // Give the OS well over its start-foreground deadline to deliver a crash if the service were left dangling.
        runBlocking { delay(CRASH_WINDOW_MS) }
        awaitCondition("the service has stopped") { !TrackingServiceStatus.isInForeground }
        assertFalse(tracker.state.value.trip.phase == TripPhase.RUNNING)
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private fun push(speed: Float, northMeters: Double = 0.0) {
        latitude += northMeters / METERS_PER_DEGREE_LATITUDE
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = this@TrackingServiceEndToEndTest.latitude
            longitude = 4.0
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            this.speed = speed
            speedAccuracyMetersPerSecond = 0.5f
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix)
    }

    private fun drive(steps: Int) {
        repeat(steps) {
            push(speed = 10f, northMeters = 10.0)
            runBlocking { delay(FIX_INTERVAL_MS) }
        }
    }

    private fun pushUntilListening() {
        val deadline = SystemClock.elapsedRealtime() + LISTEN_TIMEOUT_MS
        while (tracker.state.value.gps.lastFixAgeMillis == null) {
            check(SystemClock.elapsedRealtime() < deadline) { "No fix reached the tracker: ${tracker.state.value}" }
            push(speed = 10f)
            runBlocking { delay(PRIME_RETRY_MS) }
        }
        runBlocking { delay(SETTLE_MS) }
    }

    private fun awaitState(what: String, condition: (TrackingState) -> Boolean): TrackingState = runBlocking {
        withTimeoutOrNull(AWAIT_MS) { tracker.state.first(condition) }
            ?: error("Timed out waiting for: $what. Last state: ${tracker.state.value}")
    }

    private fun findNotification(predicate: (Notification) -> Boolean): Notification? = notificationManager
        .activeNotifications
        .firstOrNull { it.id == TrackingNotificationIds.NOTIFICATION_ID && predicate(it.notification) }
        ?.notification

    private fun awaitNotification(what: String, predicate: (Notification) -> Boolean): Notification {
        var found: Notification? = null
        awaitCondition(what) { findNotification(predicate).also { found = it } != null }
        return found!!
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + AWAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            runBlocking { delay(POLL_MS) }
        }
        error("Timed out waiting for: $what. Last state: ${tracker.state.value}")
    }

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_194.93
        const val FIX_INTERVAL_MS = 1_000L
        const val PRIME_RETRY_MS = 300L
        const val SETTLE_MS = 1_300L
        const val LISTEN_TIMEOUT_MS = 8_000L
        const val AWAIT_MS = 8_000L
        const val POLL_MS = 150L
        const val CRASH_WINDOW_MS = 12_000L
    }
}
