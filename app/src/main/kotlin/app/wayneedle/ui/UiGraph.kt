package app.wayneedle.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import app.wayneedle.AppContainer
import app.wayneedle.WayneedleApplication

/** The application, as seen from a `viewModelFactory { initializer { ... } }` block. */
internal fun CreationExtras.wayneedleApplication(): WayneedleApplication =
    checkNotNull(this[APPLICATION_KEY]) as WayneedleApplication

/** The app's dependency container, as seen from a `viewModelFactory { initializer { ... } }` block. */
internal fun CreationExtras.appContainer(): AppContainer = wayneedleApplication().container
