package app.wayneedle.ui.navigation

import app.wayneedle.R
import app.wayneedle.ui.components.NavigationItem
import app.wayneedle.ui.icons.WayneedleIcons

/** Name of the navigation argument that carries a trip id. */
const val TRIP_ID_ARG = "tripId"

/**
 * Every screen of the app with its route pattern. Routes are built only through this type, so a typo in a
 * route string cannot compile. (The compiler-plugin based `@Serializable` routes need the Kotlin
 * serialization plugin, which the project does not apply; see the UI report.)
 */
sealed class Destination(val route: String) {
    data object Dashboard : Destination("dashboard")

    data object History : Destination("history")

    data object Settings : Destination("settings")

    /** Full-screen head-up display, opened from the dashboard. */
    data object Hud : Destination("hud")

    data object TripDetail : Destination("trip/{$TRIP_ID_ARG}") {
        fun routeFor(tripId: String): String = "trip/$tripId"
    }

    companion object {
        /** The destinations shown in the navigation bar, in order. */
        val topLevel: List<NavigationItem> = listOf(
            NavigationItem(Dashboard.route, R.string.nav_dashboard, WayneedleIcons.Speed),
            NavigationItem(History.route, R.string.nav_history, WayneedleIcons.History),
            NavigationItem(Settings.route, R.string.nav_settings, WayneedleIcons.Settings),
        )
    }
}
