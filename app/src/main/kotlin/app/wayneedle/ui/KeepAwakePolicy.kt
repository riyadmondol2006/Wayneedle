package app.wayneedle.ui

import app.wayneedle.domain.model.LiveSpeed
import app.wayneedle.domain.model.TrackingState
import app.wayneedle.domain.model.TripPhase

/**
 * Decides whether "keep screen on" should hold the display on right now. The display is the biggest battery
 * cost of a phone, so it stays on only while it is being used as a speedometer: while moving, and for
 * [holdMillis] after the last movement or after a trip was started or resumed (time to set off). Parked with
 * the app open, at a long stop, or with a paused trip, Android's normal screen timeout takes over.
 *
 * Pure and single-threaded; times are monotonic nanoseconds.
 */
class KeepAwakePolicy(
    private val holdMillis: Long = HOLD_MILLIS,
    private val movingSpeedMps: Float = MOVING_SPEED_MPS,
) {
    private var lastActivityNanos: Long? = null
    private var lastPhase: TripPhase? = null

    /** Feeds the latest state; returns whether the display should be kept on at [nowNanos]. */
    fun update(state: TrackingState, nowNanos: Long): Boolean {
        val phase = state.trip.phase
        if (phase == TripPhase.RUNNING && lastPhase != TripPhase.RUNNING) lastActivityNanos = nowNanos
        lastPhase = phase
        val live = state.liveSpeed as? LiveSpeed.Available
        if (live != null && live.smoothedMps >= movingSpeedMps) lastActivityNanos = nowNanos
        val last = lastActivityNanos ?: return false
        return nowNanos - last <= holdMillis * NANOS_PER_MILLI
    }

    companion object {
        /** Long enough for traffic lights and short jams, short enough not to burn a parked phone's battery. */
        const val HOLD_MILLIS: Long = 5 * 60_000L

        /** 3.6 km/h: walking pace counts as moving, GPS jitter of a still phone does not. */
        const val MOVING_SPEED_MPS: Float = 1f

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
