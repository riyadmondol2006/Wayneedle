package app.wayneedle.tracking

import app.wayneedle.domain.model.LocationSample
import app.wayneedle.testing.aSample
import app.wayneedle.testing.latitudeAfterNorth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GpsFixFilterTest {
    private val filter = GpsFixFilter()

    private fun evaluate(sample: LocationSample, nowMillis: Long = sample.elapsedRealtimeNanos / 1_000_000L) =
        filter.evaluate(sample, nowMillis * 1_000_000L)

    private fun assertAccepted(decision: FixDecision): AcceptedFix = assertIs<FixDecision.Accepted>(decision).fix

    private fun assertRejected(expected: RejectReason, decision: FixDecision) {
        assertEquals(expected, assertIs<FixDecision.Rejected>(decision).reason)
    }

    @Test
    fun `good fix is accepted with its raw speed and position`() {
        val fix = assertAccepted(evaluate(aSample(speedMps = 13.4f, elapsedRealtimeMillis = 1_000L)))
        assertEquals(13.4f, fix.speedMps)
        assertEquals(52.0, fix.latitude)
        assertEquals(4.0, fix.longitude)
        assertEquals(5f, fix.horizontalAccuracyM)
        assertEquals(1_000_000_000L, fix.elapsedRealtimeNanos)
    }

    @Test
    fun `genuine zero speed is accepted as zero`() {
        assertEquals(0f, assertAccepted(evaluate(aSample(speedMps = 0f, elapsedRealtimeMillis = 1_000L))).speedMps)
    }

    @Test
    fun `tiny speeds below the stationary threshold become zero`() {
        assertEquals(0f, assertAccepted(evaluate(aSample(speedMps = 0.49f, elapsedRealtimeMillis = 1_000L))).speedMps)
        assertEquals(0.5f, assertAccepted(evaluate(aSample(speedMps = 0.5f, elapsedRealtimeMillis = 2_000L))).speedMps)
    }

    @Test
    fun `duplicate and out-of-order timestamps are rejected`() {
        assertAccepted(evaluate(aSample(elapsedRealtimeMillis = 2_000L)))
        assertRejected(RejectReason.NON_INCREASING_TIMESTAMP, evaluate(aSample(elapsedRealtimeMillis = 2_000L)))
        assertRejected(
            RejectReason.NON_INCREASING_TIMESTAMP,
            evaluate(aSample(elapsedRealtimeMillis = 1_500L), nowMillis = 2_000L),
        )
    }

    @Test
    fun `a rejected fix still advances the newest timestamp`() {
        assertRejected(
            RejectReason.POOR_ACCURACY,
            evaluate(aSample(horizontalAccuracyM = 80f, elapsedRealtimeMillis = 3_000L)),
        )
        assertRejected(
            RejectReason.NON_INCREASING_TIMESTAMP,
            evaluate(aSample(elapsedRealtimeMillis = 2_500L), nowMillis = 3_000L),
        )
    }

    @Test
    fun `fixes older than five seconds are stale`() {
        assertRejected(RejectReason.STALE, evaluate(aSample(elapsedRealtimeMillis = 4_000L), nowMillis = 9_001L))
        assertAccepted(evaluate(aSample(elapsedRealtimeMillis = 5_000L), nowMillis = 10_000L))
    }

    @Test
    fun `fixes from the future are rejected`() {
        assertRejected(
            RejectReason.TIMESTAMP_IN_FUTURE,
            evaluate(aSample(elapsedRealtimeMillis = 12_000L), nowMillis = 10_000L),
        )
        assertAccepted(evaluate(aSample(elapsedRealtimeMillis = 13_000L), nowMillis = 12_500L))
    }

    @Test
    fun `invalid coordinates are rejected`() {
        assertRejected(
            RejectReason.INVALID_COORDINATES,
            evaluate(aSample(latitude = Double.NaN, elapsedRealtimeMillis = 1_000L)),
        )
        assertRejected(
            RejectReason.INVALID_COORDINATES,
            evaluate(aSample(latitude = 90.5, elapsedRealtimeMillis = 2_000L)),
        )
        assertRejected(
            RejectReason.INVALID_COORDINATES,
            evaluate(aSample(longitude = -180.5, elapsedRealtimeMillis = 3_000L)),
        )
    }

    @Test
    fun `missing or malformed horizontal accuracy is rejected`() {
        assertRejected(
            RejectReason.MISSING_ACCURACY,
            evaluate(aSample(horizontalAccuracyM = null, elapsedRealtimeMillis = 1_000L)),
        )
        assertRejected(
            RejectReason.MISSING_ACCURACY,
            evaluate(aSample(horizontalAccuracyM = Float.NaN, elapsedRealtimeMillis = 2_000L)),
        )
        assertRejected(
            RejectReason.MISSING_ACCURACY,
            evaluate(aSample(horizontalAccuracyM = -1f, elapsedRealtimeMillis = 3_000L)),
        )
    }

    @Test
    fun `horizontal accuracy worse than 25 m is rejected`() {
        assertRejected(
            RejectReason.POOR_ACCURACY,
            evaluate(aSample(horizontalAccuracyM = 25.1f, elapsedRealtimeMillis = 1_000L)),
        )
        assertAccepted(evaluate(aSample(horizontalAccuracyM = 25f, elapsedRealtimeMillis = 2_000L)))
    }

    @Test
    fun `missing speed is rejected and never treated as zero`() {
        assertRejected(RejectReason.MISSING_SPEED, evaluate(aSample(speedMps = null, elapsedRealtimeMillis = 1_000L)))
    }

    @Test
    fun `NaN infinite or negative speed is rejected`() {
        assertRejected(
            RejectReason.INVALID_SPEED,
            evaluate(aSample(speedMps = Float.NaN, elapsedRealtimeMillis = 1_000L)),
        )
        assertRejected(
            RejectReason.INVALID_SPEED,
            evaluate(aSample(speedMps = Float.POSITIVE_INFINITY, elapsedRealtimeMillis = 2_000L)),
        )
        assertRejected(RejectReason.INVALID_SPEED, evaluate(aSample(speedMps = -0.1f, elapsedRealtimeMillis = 3_000L)))
    }

    @Test
    fun `poor speed accuracy is rejected only when reported`() {
        assertRejected(
            RejectReason.POOR_SPEED_ACCURACY,
            evaluate(aSample(speedAccuracyMps = 3.1f, elapsedRealtimeMillis = 1_000L)),
        )
        assertRejected(
            RejectReason.POOR_SPEED_ACCURACY,
            evaluate(aSample(speedAccuracyMps = Float.NaN, elapsedRealtimeMillis = 2_000L)),
        )
        assertAccepted(evaluate(aSample(speedAccuracyMps = 3f, elapsedRealtimeMillis = 3_000L)))
        assertAccepted(evaluate(aSample(speedAccuracyMps = null, elapsedRealtimeMillis = 4_000L)))
    }

    @Test
    fun `speed above the physical cap is rejected`() {
        assertRejected(
            RejectReason.SPEED_ABOVE_CAP,
            evaluate(aSample(speedMps = 150.5f, elapsedRealtimeMillis = 1_000L)),
        )
        assertAccepted(evaluate(aSample(speedMps = 150f, elapsedRealtimeMillis = 2_000L)))
    }

    @Test
    fun `speed spike against the previous accepted fix is rejected`() {
        assertAccepted(evaluate(aSample(speedMps = 10f, elapsedRealtimeMillis = 1_000L)))
        val north = latitudeAfterNorth(52.0, 10.0)
        assertRejected(
            RejectReason.ACCELERATION_SPIKE,
            evaluate(aSample(latitude = north, speedMps = 30f, elapsedRealtimeMillis = 2_000L)),
        )
        // The spike did not become the reference: a normal reading one second later is fine.
        val further = latitudeAfterNorth(52.0, 21.0)
        assertAccepted(evaluate(aSample(latitude = further, speedMps = 11f, elapsedRealtimeMillis = 3_000L)))
    }

    @Test
    fun `hard but physical braking is accepted`() {
        assertAccepted(evaluate(aSample(speedMps = 20f, elapsedRealtimeMillis = 1_000L)))
        val north = latitudeAfterNorth(52.0, 15.0)
        assertAccepted(evaluate(aSample(latitude = north, speedMps = 9f, elapsedRealtimeMillis = 2_000L)))
    }

    @Test
    fun `closely spaced fixes are not mistaken for an acceleration spike`() {
        assertAccepted(evaluate(aSample(speedMps = 10f, elapsedRealtimeMillis = 1_000L)))
        // 2 m/s change in 200 ms would be 10 m/s² only per real second; evaluated over at least 1 s.
        val north = latitudeAfterNorth(52.0, 2.0)
        assertAccepted(evaluate(aSample(latitude = north, speedMps = 12f, elapsedRealtimeMillis = 1_200L)))
    }

    @Test
    fun `position jump inconsistent with the reported speed is rejected`() {
        assertAccepted(evaluate(aSample(speedMps = 10f, elapsedRealtimeMillis = 1_000L)))
        val teleport = latitudeAfterNorth(52.0, 200.0)
        assertRejected(
            RejectReason.SPEED_DISPLACEMENT_MISMATCH,
            evaluate(aSample(latitude = teleport, speedMps = 10f, elapsedRealtimeMillis = 2_000L)),
        )
        val consistent = latitudeAfterNorth(52.0, 20.0)
        assertAccepted(evaluate(aSample(latitude = consistent, speedMps = 10f, elapsedRealtimeMillis = 3_000L)))
    }

    @Test
    fun `relative checks expire so one bad reference cannot lock the filter out`() {
        assertAccepted(evaluate(aSample(speedMps = 2f, elapsedRealtimeMillis = 1_000L)))
        // 6 s later, at motorway speed and far away: the old reference is no longer compared against.
        val far = latitudeAfterNorth(52.0, 1_000.0)
        assertAccepted(evaluate(aSample(latitude = far, speedMps = 80f, elapsedRealtimeMillis = 7_000L)))
    }

    @Test
    fun `reset forgets timestamp and reference history`() {
        assertAccepted(evaluate(aSample(speedMps = 2f, elapsedRealtimeMillis = 5_000L)))
        filter.reset()
        assertAccepted(evaluate(aSample(speedMps = 30f, elapsedRealtimeMillis = 4_000L), nowMillis = 5_000L))
    }

    @Test
    fun `every reject reason is reachable`() {
        val seen = mutableSetOf<RejectReason>()
        fun record(decision: FixDecision) {
            if (decision is FixDecision.Rejected) seen += decision.reason
        }
        val f = GpsFixFilter()
        fun at(ms: Long, sample: LocationSample, now: Long = ms) = record(f.evaluate(sample, now * 1_000_000L))
        at(1_000, aSample(elapsedRealtimeMillis = 1_000))
        at(1_000, aSample(elapsedRealtimeMillis = 1_000))
        at(2_000, aSample(elapsedRealtimeMillis = 2_000), now = 8_000)
        at(9_000, aSample(elapsedRealtimeMillis = 11_000), now = 9_000)
        at(12_000, aSample(latitude = 95.0, elapsedRealtimeMillis = 12_000))
        at(13_000, aSample(horizontalAccuracyM = null, elapsedRealtimeMillis = 13_000))
        at(14_000, aSample(horizontalAccuracyM = 50f, elapsedRealtimeMillis = 14_000))
        at(15_000, aSample(speedMps = null, elapsedRealtimeMillis = 15_000))
        at(16_000, aSample(speedMps = -1f, elapsedRealtimeMillis = 16_000))
        at(17_000, aSample(speedAccuracyMps = 9f, elapsedRealtimeMillis = 17_000))
        at(18_000, aSample(speedMps = 200f, elapsedRealtimeMillis = 18_000))
        at(19_000, aSample(speedMps = 10f, elapsedRealtimeMillis = 19_000))
        at(20_000, aSample(latitude = latitudeAfterNorth(52.0, 10.0), speedMps = 40f, elapsedRealtimeMillis = 20_000))
        at(21_000, aSample(latitude = latitudeAfterNorth(52.0, 500.0), speedMps = 10f, elapsedRealtimeMillis = 21_000))
        assertEquals(RejectReason.entries.toSet(), seen)
    }

    @Test
    fun `receiver-quality rejections clear live speed while outliers and late fixes do not`() {
        val clearing = RejectReason.entries.filter { it.clearsLiveSpeed }.toSet()
        assertEquals(
            setOf(
                RejectReason.MISSING_ACCURACY,
                RejectReason.POOR_ACCURACY,
                RejectReason.MISSING_SPEED,
                RejectReason.INVALID_SPEED,
                RejectReason.POOR_SPEED_ACCURACY,
            ),
            clearing,
        )
        val notCurrent = RejectReason.entries.filterNot { it.isCurrentFix }.toSet()
        assertEquals(
            setOf(
                RejectReason.NON_INCREASING_TIMESTAMP,
                RejectReason.STALE,
                RejectReason.TIMESTAMP_IN_FUTURE,
                RejectReason.INVALID_COORDINATES,
            ),
            notCurrent,
        )
    }
}
