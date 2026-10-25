package app.wayneedle

import android.content.Context
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.TripRepository

/**
 * Manual dependency container (no DI framework: no annotation processing, no reflection).
 * Created once by [WayneedleApplication]. The lead agent wires the real implementations.
 */
interface AppContainer {
    val timeSource: TimeSource
    val settingsRepository: SettingsRepository
    val tripRepository: TripRepository
    val trackingController: TrackingController
}

/** Placeholder wiring; replaced with real implementations during integration. */
class DefaultAppContainer(@Suppress("unused") private val context: Context) : AppContainer {
    override val timeSource: TimeSource get() = notWired("timeSource")
    override val settingsRepository: SettingsRepository get() = notWired("settingsRepository")
    override val tripRepository: TripRepository get() = notWired("tripRepository")
    override val trackingController: TrackingController get() = notWired("trackingController")

    private fun notWired(name: String): Nothing = error("$name is not wired yet")
}
