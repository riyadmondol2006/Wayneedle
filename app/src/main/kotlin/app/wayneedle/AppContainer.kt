package app.wayneedle

import android.content.Context
import app.wayneedle.data.DataModule
import app.wayneedle.domain.SettingsRepository
import app.wayneedle.domain.TimeSource
import app.wayneedle.domain.TrackingController
import app.wayneedle.domain.TripRepository
import app.wayneedle.permissions.AndroidLocationPermissionChecker
import app.wayneedle.service.ServiceTripForegroundLauncher
import app.wayneedle.tracking.TripTracker
import app.wayneedle.tracking.platform.AndroidLocationSource
import app.wayneedle.tracking.platform.SystemTimeSource
import kotlinx.coroutines.CoroutineScope

/**
 * Manual dependency container (no DI framework: no annotation processing, no reflection).
 * Created once by [WayneedleApplication].
 */
interface AppContainer {
    val timeSource: TimeSource
    val settingsRepository: SettingsRepository
    val tripRepository: TripRepository
    val trackingController: TrackingController
}

/**
 * Production wiring. [trackingController] is the one and only owner of the GPS subscription; the UI and
 * the foreground service receive it from here and never create their own.
 *
 * @param applicationScope process-lifetime scope the tracker runs its listener, ticker and checkpoint
 *   writer on.
 */
class DefaultAppContainer(context: Context, applicationScope: CoroutineScope) : AppContainer {
    private val appContext: Context = context.applicationContext
    private val data = DataModule(appContext)

    override val timeSource: TimeSource = SystemTimeSource
    override val settingsRepository: SettingsRepository get() = data.settingsRepository
    override val tripRepository: TripRepository get() = data.tripRepository

    override val trackingController: TrackingController by lazy {
        TripTracker(
            locationSource = AndroidLocationSource(appContext),
            permissionChecker = AndroidLocationPermissionChecker(appContext),
            tripRepository = data.tripRepository,
            checkpointStore = data.tripCheckpointStore,
            foreground = ServiceTripForegroundLauncher(appContext),
            timeSource = timeSource,
            scope = applicationScope,
        )
    }
}
