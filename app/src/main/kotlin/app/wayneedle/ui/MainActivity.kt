package app.wayneedle.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.wayneedle.WayneedleApplication
import app.wayneedle.domain.TrackingController

/**
 * The single activity. Besides hosting the Compose UI it tells the tracking engine when the UI is on
 * screen (so live speed works without a trip but the GPS is released in the background) and asks it to
 * re-read permission and GPS state whenever the app returns to the foreground.
 */
class MainActivity : ComponentActivity() {
    private val trackingController: TrackingController by lazy {
        (application as WayneedleApplication).container.trackingController
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    trackingController.setUiVisible(true)
                }

                override fun onResume(owner: LifecycleOwner) {
                    trackingController.refreshEnvironment()
                }

                override fun onStop(owner: LifecycleOwner) {
                    trackingController.setUiVisible(false)
                }
            },
        )
        setContent { WayneedleApp() }
    }
}
