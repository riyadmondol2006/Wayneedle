package app.wayneedle

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class WayneedleApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Lazily created so process start stays cheap. */
    val container: AppContainer by lazy { DefaultAppContainer(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        // A trip interrupted by process death comes back PAUSED; the user decides in the UI. This never
        // starts the foreground service (not allowed from the background), see docs/ARCHITECTURE.md.
        applicationScope.launch {
            try {
                container.trackingController.recoverInterruptedTrip()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not recover an interrupted trip", e)
            }
        }
    }

    private companion object {
        const val TAG = "WayneedleApp"
    }
}
