package app.wayneedle.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.wayneedle.ui.dashboard.DashboardRoute
import app.wayneedle.ui.history.HistoryRoute
import app.wayneedle.ui.settings.SettingsRoute
import app.wayneedle.ui.theme.WayneedleMotion
import app.wayneedle.ui.tripdetail.TripDetailRoute

private const val SCALE_FROM = 0.94f

/** The navigation graph. Transitions use the Expressive motion scheme and collapse when motion is reduced. */
@Composable
fun WayneedleNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val fade = WayneedleMotion.effects<Float>()
    val scale = WayneedleMotion.spatial<Float>()
    val enter: () -> EnterTransition = { fadeIn(fade) + scaleIn(scale, initialScale = SCALE_FROM) }
    val exit: () -> ExitTransition = { fadeOut(fade) }
    val popExit: () -> ExitTransition = { fadeOut(fade) + scaleOut(scale, targetScale = SCALE_FROM) }
    val openTrip: (String) -> Unit = { tripId -> navController.navigate(Destination.TripDetail.routeFor(tripId)) }

    NavHost(
        navController = navController,
        startDestination = Destination.Dashboard.route,
        modifier = modifier,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { enter() },
        popExitTransition = { popExit() },
    ) {
        composable(Destination.Dashboard.route) { DashboardRoute(onOpenTrip = openTrip) }
        composable(Destination.History.route) { HistoryRoute(onOpenTrip = openTrip) }
        composable(Destination.Settings.route) { SettingsRoute() }
        composable(
            route = Destination.TripDetail.route,
            arguments = listOf(navArgument(TRIP_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            TripDetailRoute(
                tripId = entry.arguments?.getString(TRIP_ID_ARG).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
