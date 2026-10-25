package app.wayneedle.tracking

import android.Manifest
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
import app.wayneedle.data.db.RoomTripCheckpointStore
import app.wayneedle.data.db.RoomTripRepository
import app.wayneedle.data.db.WayneedleDatabase
import app.wayneedle.domain.CommandResult
import app.wayneedle.domain.FinishResult
import app.wayneedle.domain.TripForegroundLauncher
import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.SpeedUnit
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.TripPhase
import app.wayneedle.permissions.AndroidLocationPermissionChecker
import app.wayneedle.tracking.platform.AndroidLocationSource
import app.wayneedle.tracking.platform.SystemTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the production location pipeline on a real device: the platform `LocationManager`
 * (fed through its test provider), [AndroidLocationSource], [TripTracker] and a real Room database. Only the
 * foreground-service launcher is replaced by a recorder.
 *
 * **These fixes are scripted, not satellite measurements.** The test proves that fixes carrying speed travel
 * through the real Android location stack into correct live speed and trip statistics; it says nothing about
 * GPS accuracy in the field.
 *
 * Needs the mock-location app-op for the app under test; without it every test is skipped (not failed):
 * `adb -s <serial> shell appops set app.wayneedle.debug android:mock_location allow`
 * and afterwards `... appops set app.wayneedle.debug android:mock_location default`.
 */
@RunWith(AndroidJUnit4::class)
class GpsPipelineInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val locationManager: LocationManager = context.getSystemService(LocationManager::class.java)

    private class RecordingLauncher : TripForegroundLauncher {
        @Volatile var starts = 0

        @Volatile var stops = 0

        override fun start() {
            starts++
        }

        override fun stop() {
            stops++
        }
    }

    private lateinit var database: WayneedleDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var launcher: RecordingLauncher
    private lateinit var checkpointStore: RoomTripCheckpointStore
    private lateinit var tripRepository: RoomTripRepository
    private lateinit var tracker: TripTracker
    private var mockProviderAdded = false
    private var visibleActivity: ActivityScenario<ComponentActivity>? = null
    private var latitude = START_LATITUDE
    private var longitude = START_LONGITUDE

    @Before
    fun setUp() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
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
        // "While using the app" location is only delivered while the app is visible, exactly as for a user looking at
        // the speedometer. Without a resumed activity Android keeps the registration inactive and sends no fixes.
        visibleActivity = ActivityScenario.launch(ComponentActivity::class.java)
        database = WayneedleDatabase.createInMemory(context)
        tripRepository = RoomTripRepository(database.tripDao())
        checkpointStore = RoomTripCheckpointStore(database.tripCheckpointDao())
        launcher = RecordingLauncher()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        tracker = TripTracker(
            locationSource = AndroidLocationSource(context),
            permissionChecker = AndroidLocationPermissionChecker(context),
            tripRepository = tripRepository,
            checkpointStore = checkpointStore,
            foreground = launcher,
            timeSource = SystemTimeSource,
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        if (::tracker.isInitialized) tracker.setUiVisible(false)
        if (::scope.isInitialized) scope.cancel()
        if (mockProviderAdded) {
            try {
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            } catch (_: IllegalArgumentException) {
                // Already gone.
            }
        }
        if (::database.isInitialized) database.close()
        visibleActivity?.close()
    }

    @Test
    fun aFixWithoutSpeedIsUnavailableNeverAFakeZero() {
        tracker.setUiVisible(true)
        primeUntilListening(speed = null)
        push(speed = null)
        delay1s()
        push(speed = null)
        val state = awaitState("a fix was seen") { it.gps.lastFixAgeMillis != null }
        assertEquals(LiveSpeed.Unavailable, state.liveSpeed)
    }

    @Test
    fun speedFromTheFixIsShownAndConvertsToKmh() {
        tracker.setUiVisible(true)
        primeUntilListening(speed = 0f)
        val available = pushUntil(speed = 10f, what = "10 m/s shown") {
            (it.liveSpeed as? LiveSpeed.Available)?.rawMps == 10f
        }.liveSpeed as LiveSpeed.Available
        assertEquals(10f, available.rawMps, 0f)
        assertEquals(36.0, SpeedUnit.KMH.fromMetersPerSecond(available.rawMps.toDouble()), 1e-6)
    }

    @Test
    fun aGenuineZeroSpeedIsShownAsZero() {
        tracker.setUiVisible(true)
        primeUntilListening(speed = 0f)
        val state = pushUntil(speed = 0f, what = "zero shown") { it.liveSpeed == LiveSpeed.Available(0f, 0f) }
        assertEquals(LiveSpeed.Available(0f, 0f), state.liveSpeed)
    }

    @Test
    fun theGpsListenerIsReleasedWhenTheUiIsHiddenAndNoTripRuns() {
        tracker.setUiVisible(true)
        primeUntilListening(speed = 5f)
        tracker.setUiVisible(false)
        awaitState("speed unavailable once released") { it.liveSpeed == LiveSpeed.Unavailable }
        // Nobody is listening, so this fix must not reach the tracker.
        push(speed = 33f)
        delay1s()
        delay1s()
        assertEquals(LiveSpeed.Unavailable, tracker.state.value.liveSpeed)
        tracker.setUiVisible(true)
        // The platform may replay the cached fix to the new registration; pushing the same speed again keeps
        // the expectation deterministic either way.
        pushUntil(speed = 33f, what = "listening again") { (it.liveSpeed as? LiveSpeed.Available)?.rawMps == 33f }
    }

    @Test
    fun tripStatisticsOnlyAdvanceWhileRunningAndSurviveTheFullLifecycle() {
        tracker.setUiVisible(true)
        primeUntilListening(speed = 10f)

        val beforeStart = SystemClock.elapsedRealtime()
        assertEquals(CommandResult.Ok, tracker.startTrip())
        assertEquals(1, launcher.starts)

        // 8 fixes, 10 m apart, 10 m/s: the first one only anchors, so 7 segments = ~70 m.
        drive(steps = 8, metersPerStep = 10.0, speed = 10f)
        val running = awaitState("distance accumulated") { it.trip.stats.distanceMeters >= 60.0 }.trip
        assertEquals(TripPhase.RUNNING, running.phase)
        assertEquals(70.0, running.stats.distanceMeters, 7.0)
        assertEquals(10f, running.stats.maxSpeedMps, 0.01f)

        assertEquals(CommandResult.Ok, tracker.pauseTrip())
        val pausedAt = SystemClock.elapsedRealtime()
        val paused = tracker.state.value.trip
        assertEquals(TripPhase.PAUSED, paused.phase)
        assertTrue(paused.stats.activeDurationMillis in 7_000..(pausedAt - beforeStart))

        // Moving (and faster) while paused: nothing in the trip may change; live speed keeps working.
        drive(steps = 4, metersPerStep = 10.0, speed = 20f)
        val stillPaused = tracker.state.value
        assertEquals(paused.stats, stillPaused.trip.stats)
        assertEquals(20f, (stillPaused.liveSpeed as LiveSpeed.Available).rawMps, 0.01f)

        assertEquals(CommandResult.Ok, tracker.resumeTrip())
        assertEquals(2, launcher.starts)
        assertTrue(tracker.state.value.trip.awaitingFix)
        // After resume the first fix is only an anchor: the 40 m moved during the pause are never counted.
        drive(steps = 5, metersPerStep = 10.0, speed = 10f)
        val resumed = awaitState("distance after resume") { it.trip.stats.distanceMeters >= 100.0 }.trip
        assertEquals(110.0, resumed.stats.distanceMeters, 9.0)
        assertTrue("paused movement must not be counted", resumed.stats.distanceMeters < 130.0)
        assertEquals(10f, resumed.stats.maxSpeedMps, 0.01f)

        val result = runBlocking { tracker.finishTrip() }
        val saved = (result as FinishResult.Saved).trip
        assertEquals(TripPhase.FINISHED, tracker.state.value.trip.phase)
        assertEquals(1, launcher.stops)
        assertEquals(resumed.stats.distanceMeters, saved.stats.distanceMeters, 0.0)

        val stored = runBlocking { tripRepository.getTrip(saved.id) }
        assertNotNull("the finished trip is in Room", stored)
        assertEquals(saved.stats, stored!!.stats)
        assertNull("the checkpoint is cleared once the trip is saved", runBlocking { checkpointStore.load() })
    }

    @Test
    fun aParkedPhoneAccruesNoDistance() {
        tracker.setUiVisible(true)
        primeUntilListening(speed = 0.1f)
        assertEquals(CommandResult.Ok, tracker.startTrip())
        val jitterMeters = doubleArrayOf(2.0, -3.0, 1.5, -2.5, 3.0, -1.0)
        for (jitter in jitterMeters) {
            push(speed = 0.1f, northOffsetMeters = jitter)
            delay1s()
        }
        val state = tracker.state.value
        assertEquals(0.0, state.trip.stats.distanceMeters, 0.0)
        assertEquals(LiveSpeed.Available(0f, 0f), state.liveSpeed)
    }

    @Test
    fun anImpossibleSpeedSpikeIsRejected() {
        tracker.setUiVisible(true)
        primeUntilListening(speed = 10f)
        assertEquals(CommandResult.Ok, tracker.startTrip())
        drive(steps = 4, metersPerStep = 10.0, speed = 10f)
        push(speed = 90f, northOffsetMeters = 10.0)
        delay1s()
        val state = tracker.state.value
        assertTrue(
            "max speed must ignore the spike, was ${state.trip.stats.maxSpeedMps}",
            state.trip.stats.maxSpeedMps < 15f,
        )
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private fun delay1s() = runBlocking { delay(FIX_INTERVAL_MS) }

    /** Pushes one fix; [northOffsetMeters] moves the reported position from the current reference. */
    private fun push(speed: Float?, northOffsetMeters: Double = 0.0, accuracy: Float = 5f) {
        latitude += northOffsetMeters / METERS_PER_DEGREE_LATITUDE
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = this@GpsPipelineInstrumentedTest.latitude
            this.longitude = this@GpsPipelineInstrumentedTest.longitude
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (speed != null) {
                this.speed = speed
                speedAccuracyMetersPerSecond = 0.5f
            }
        }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix)
    }

    /** One fix per second, each [metersPerStep] further north, starting with the first step. */
    private fun drive(steps: Int, metersPerStep: Double, speed: Float) {
        repeat(steps) {
            push(speed = speed, northOffsetMeters = metersPerStep)
            delay1s()
        }
    }

    /** Pushes fixes until the tracker has received one, i.e. its platform listener is registered. */
    private fun primeUntilListening(speed: Float?) {
        val deadline = SystemClock.elapsedRealtime() + LISTEN_TIMEOUT_MS
        while (tracker.state.value.gps.lastFixAgeMillis == null) {
            check(SystemClock.elapsedRealtime() < deadline) {
                "The tracker never received a fix: ${tracker.state.value}"
            }
            push(speed = speed)
            runBlocking { delay(PRIME_RETRY_MS) }
        }
        // Android drops a fix that follows the last delivered one by less than the 1 s request interval (minus a
        // small slack); let that window pass so the first scripted fix is not silently discarded.
        runBlocking { delay(SETTLE_MS) }
    }

    /** Pushes [speed] once per interval until [condition] holds: a fix pushed during a re-registration is lost. */
    private fun pushUntil(speed: Float, what: String, condition: (TrackingState) -> Boolean): TrackingState {
        val deadline = SystemClock.elapsedRealtime() + LISTEN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            push(speed = speed)
            val state =
                runBlocking { withTimeoutOrNull(FIX_INTERVAL_MS + PRIME_RETRY_MS) { tracker.state.first(condition) } }
            if (state != null) return state
        }
        error("Timed out waiting for: $what. Last state: ${tracker.state.value}")
    }

    private fun awaitState(what: String, timeoutMs: Long = AWAIT_TIMEOUT_MS, condition: (TrackingState) -> Boolean) =
        runBlocking {
            withTimeoutOrNull(timeoutMs) { tracker.state.first(condition) }
                ?: error("Timed out waiting for: $what. Last state: ${tracker.state.value}")
        }

    private companion object {
        const val START_LATITUDE = 52.0
        const val START_LONGITUDE = 4.0
        const val METERS_PER_DEGREE_LATITUDE = 111_194.93
        const val FIX_INTERVAL_MS = 1_000L
        const val PRIME_RETRY_MS = 300L
        const val SETTLE_MS = 1_300L
        const val LISTEN_TIMEOUT_MS = 8_000L
        const val AWAIT_TIMEOUT_MS = 6_000L
    }
}
