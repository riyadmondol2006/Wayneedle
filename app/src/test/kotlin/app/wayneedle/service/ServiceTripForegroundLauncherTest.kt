package app.wayneedle.service

import app.wayneedle.domain.LocationPermissionChecker
import app.wayneedle.domain.model.LocationPermissionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServiceTripForegroundLauncherTest {
    private class FakeHost(
        var startFailure: RuntimeException? = null,
        var stopFailure: RuntimeException? = null,
    ) : ServiceHost {
        var starts = 0
        var stops = 0

        override fun startForegroundService() {
            starts++
            startFailure?.let { throw it }
        }

        override fun stopService() {
            stops++
            stopFailure?.let { throw it }
        }
    }

    private val reports = mutableListOf<ForegroundStartResult>()
    private val stopFailures = mutableListOf<RuntimeException>()

    private val events = object : LauncherEvents {
        override fun onStartResult(result: ForegroundStartResult) {
            reports += result
        }

        override fun onStopFailed(error: RuntimeException) {
            stopFailures += error
        }
    }

    private fun launcher(
        host: ServiceHost,
        permission: LocationPermissionState = LocationPermissionState.GRANTED_PRECISE,
    ) = ServiceTripForegroundLauncher(host, LocationPermissionChecker { permission }, events)

    @Test
    fun `start requests the service once per call and reports success`() {
        val host = FakeHost()
        val launcher = launcher(host)
        launcher.start()
        assertEquals(1, host.starts)
        assertEquals(listOf<ForegroundStartResult>(ForegroundStartResult.Started), reports)
        assertEquals(true, TrackingServiceStatus.lastStartSucceeded)
    }

    @Test
    fun `starting twice is safe`() {
        val host = FakeHost()
        val launcher = launcher(host)
        launcher.start()
        launcher.start()
        assertEquals(2, host.starts)
        assertTrue(reports.all { it == ForegroundStartResult.Started })
    }

    @Test
    fun `a rejected background start is reported and never thrown`() {
        val cause = IllegalStateException("app is in background")
        val launcher = launcher(FakeHost(startFailure = cause))
        launcher.start()
        val failed = assertIs<ForegroundStartResult.Failed>(reports.single())
        assertEquals(ForegroundFailure.START_NOT_ALLOWED, failed.failure)
        assertSame(cause, failed.cause)
        assertEquals(false, TrackingServiceStatus.lastStartSucceeded)
    }

    @Test
    fun `a security exception is classified as a permission failure`() {
        val launcher = launcher(FakeHost(startFailure = SecurityException("location FGS from background")))
        launcher.start()
        assertEquals(ForegroundFailure.PERMISSION, assertIs<ForegroundStartResult.Failed>(reports.single()).failure)
    }

    @Test
    fun `without any location permission the service is not started at all`() {
        val host = FakeHost()
        val launcher = launcher(host, LocationPermissionState.DENIED)
        launcher.start()
        assertEquals(0, host.starts)
        assertEquals(listOf<ForegroundStartResult>(ForegroundStartResult.SkippedNoLocationPermission), reports)
        assertEquals(false, TrackingServiceStatus.lastStartSucceeded)
    }

    @Test
    fun `approximate only still starts the service because Android needs just one location grant`() {
        val host = FakeHost()
        launcher(host, LocationPermissionState.GRANTED_APPROXIMATE_ONLY).start()
        assertEquals(1, host.starts)
    }

    @Test
    fun `stop without start and repeated stop never throw`() {
        val host = FakeHost()
        val launcher = launcher(host)
        launcher.stop()
        launcher.stop()
        assertEquals(2, host.stops)
    }

    @Test
    fun `stop swallows platform failures`() {
        val boom = IllegalStateException("boom")
        val launcher = launcher(FakeHost(stopFailure = boom))
        launcher.stop()
        assertSame(boom, stopFailures.single())
    }

    @Test
    fun `start then stop then start works again`() {
        val host = FakeHost()
        val launcher = launcher(host)
        launcher.start()
        launcher.stop()
        launcher.start()
        assertEquals(2, host.starts)
        assertEquals(1, host.stops)
        assertFalse(reports.any { it is ForegroundStartResult.Failed })
    }
}
