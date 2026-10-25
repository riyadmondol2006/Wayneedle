package app.wayneedle

import android.app.Application

class WayneedleApplication : Application() {
    /** Lazily created so process start stays cheap. */
    val container: AppContainer by lazy { DefaultAppContainer(this) }
}
