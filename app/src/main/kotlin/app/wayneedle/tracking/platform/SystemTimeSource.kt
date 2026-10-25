package app.wayneedle.tracking.platform

import android.os.SystemClock
import app.wayneedle.domain.TimeSource

/** Production clock: monotonic time on the same basis as `Location.getElapsedRealtimeNanos()`. */
object SystemTimeSource : TimeSource {
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun epochMillis(): Long = System.currentTimeMillis()
}
