package app.wayneedle.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.wayneedle.R
import app.wayneedle.ui.components.NavigationItem
import app.wayneedle.ui.components.WayneedleNavigationBar
import app.wayneedle.ui.components.WayneedleNavigationRail
import app.wayneedle.ui.navigation.Destination
import app.wayneedle.ui.navigation.WayneedleNavHost
import app.wayneedle.ui.theme.WayneedleTheme
import app.wayneedle.ui.theme.isDark
import android.graphics.Color as AndroidColor

private const val RAIL_MIN_WIDTH_DP = 600
private val LightScrim = AndroidColor.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = AndroidColor.argb(0x80, 0x1B, 0x1B, 0x1B)

/** The whole app UI: theme from the saved settings, system bars, and the navigation scaffold. */
@Composable
fun WayneedleApp() {
    val appViewModel: AppViewModel = viewModel(factory = AppViewModel.Factory)
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    val keepAwake by appViewModel.keepAwake.collectAsStateWithLifecycle()
    val current = settings
    if (current == null) {
        // Settings load in a few milliseconds; hold a plain window-coloured frame instead of flashing defaults.
        Box(Modifier.fillMaxSize().background(colorResource(R.color.window_background)))
        return
    }
    WayneedleTheme(themeMode = current.themeMode, dynamicColor = current.dynamicColor) {
        SystemBarsEffect(darkTheme = current.themeMode.isDark())
        KeepScreenOnEffect(enabled = current.keepScreenOn && keepAwake)
        WayneedleScaffold()
    }
}

/** Keeps the status/navigation bar icon colours in step with the app theme, which may differ from the system's. */
@Composable
private fun SystemBarsEffect(darkTheme: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, darkTheme) {
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { darkTheme },
            navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { darkTheme },
        )
        onDispose { }
    }
}

/** Applies "keep screen on" only while the app is in the foreground and the policy wants the display on. */
@Composable
private fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    LifecycleResumeEffect(enabled, view) {
        view.keepScreenOn = enabled
        onPauseOrDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun WayneedleScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val hierarchy = backStackEntry?.destination?.hierarchy
    val selectedRoute = Destination.topLevel.firstOrNull { item ->
        hierarchy?.any { it.route == item.route } == true
    }?.route
    val showNavigation = selectedRoute != null

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val useRail = maxWidth >= RAIL_MIN_WIDTH_DP.dp
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // Top insets are left to each screen so app bars can extend behind the status bar.
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            ),
            bottomBar = {
                if (showNavigation && !useRail) {
                    WayneedleNavigationBar(
                        items = Destination.topLevel,
                        selectedRoute = selectedRoute,
                        onSelect = { navController.navigateTopLevel(it) },
                    )
                }
            },
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val railShown = showNavigation && useRail
            // The rail pads its own start edge; the content beside it must not pad that edge a second time.
            val contentPadding = if (railShown) {
                PaddingValues(
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = innerPadding.calculateBottomPadding(),
                )
            } else {
                innerPadding
            }
            Row(Modifier.fillMaxSize()) {
                if (railShown) {
                    WayneedleNavigationRail(
                        items = Destination.topLevel,
                        selectedRoute = selectedRoute,
                        onSelect = { navController.navigateTopLevel(it) },
                    )
                }
                WayneedleNavHost(
                    navController = navController,
                    modifier = Modifier
                        .weight(1f)
                        .padding(contentPadding)
                        .consumeWindowInsets(innerPadding),
                )
            }
        }
    }
}

private fun NavHostController.navigateTopLevel(item: NavigationItem) {
    navigate(item.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
