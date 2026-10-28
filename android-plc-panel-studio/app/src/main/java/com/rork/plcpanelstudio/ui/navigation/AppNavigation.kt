package com.rork.plcpanelstudio.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rork.plcpanelstudio.ui.devices.DevicesScreen
import com.rork.plcpanelstudio.ui.editor.EditorScreen
import com.rork.plcpanelstudio.ui.iostatus.IoStatusScreen
import com.rork.plcpanelstudio.ui.monitor.MonitorScreen
import com.rork.plcpanelstudio.ui.panels.PanelsScreen
import com.rork.plcpanelstudio.ui.theme.Ink
import com.rork.plcpanelstudio.ui.theme.SignalOrange
import com.rork.plcpanelstudio.ui.theme.TextLow

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val TABS = listOf(
    TabItem("panels", "Panels", Icons.Default.GridView),
    TabItem("io", "I/O", Icons.Default.SettingsInputComponent),
    TabItem("devices", "Devices", Icons.Default.Memory)
)

private const val TAB_MS = 300
private const val PAGE_MS = 340

private fun tabIndex(route: String?): Int = TABS.indexOfFirst { it.route == route }

/** +1 / -1 when moving between two tabs to the right / left, 0 for any other kind of move. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int {
    val from = tabIndex(initialState.destination.route)
    val to = tabIndex(targetState.destination.route)
    return if (from >= 0 && to >= 0) (if (to > from) 1 else -1) else 0
}

// Switching tabs: the old page fades out quickly while the new one slides in a little and fades in,
// in the direction of the tab. Opening a page (Monitor, Editor) slides it in from the right over the
// previous page, which moves away slightly; going back does the reverse.
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnter(): EnterTransition {
    val direction = tabDirection()
    return if (direction != 0) {
        slideInHorizontally(tween(TAB_MS, easing = FastOutSlowInEasing)) { it / 8 * direction } +
            fadeIn(tween(TAB_MS, delayMillis = 90))
    } else {
        slideInHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { it / 3 } +
            fadeIn(tween(PAGE_MS))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageExit(): ExitTransition {
    val direction = tabDirection()
    return if (direction != 0) {
        slideOutHorizontally(tween(TAB_MS, easing = FastOutSlowInEasing)) { -it / 8 * direction } +
            fadeOut(tween(150))
    } else {
        slideOutHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { -it / 6 } +
            fadeOut(tween(PAGE_MS))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopEnter(): EnterTransition {
    val direction = tabDirection()
    return if (direction != 0) {
        slideInHorizontally(tween(TAB_MS, easing = FastOutSlowInEasing)) { it / 8 * direction } +
            fadeIn(tween(TAB_MS, delayMillis = 90))
    } else {
        slideInHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { -it / 6 } +
            fadeIn(tween(PAGE_MS))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pagePopExit(): ExitTransition {
    val direction = tabDirection()
    return if (direction != 0) {
        slideOutHorizontally(tween(TAB_MS, easing = FastOutSlowInEasing)) { -it / 8 * direction } +
            fadeOut(tween(150))
    } else {
        slideOutHorizontally(tween(PAGE_MS, easing = FastOutSlowInEasing)) { it / 3 } +
            fadeOut(tween(PAGE_MS))
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showTabBar = TABS.any { tab -> currentRoute?.startsWith(tab.route) == true }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            AnimatedVisibility(
                visible = showTabBar,
                enter = slideInVertically(tween(280)) { it } + fadeIn(tween(280)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220))
            ) {
                NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    TABS.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any {
                            it.route?.startsWith(tab.route) == true
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = SignalOrange,
                                selectedTextColor = SignalOrange,
                                indicatorColor = SignalOrange.copy(alpha = 0.16f),
                                unselectedIconColor = TextLow,
                                unselectedTextColor = TextLow
                            )
                        )
                    }
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = "panels",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { pageEnter() },
            exitTransition = { pageExit() },
            popEnterTransition = { pagePopEnter() },
            popExitTransition = { pagePopExit() }
        ) {
            composable("panels") {
                PanelsScreen(
                    onOpenMonitor = { id -> navController.navigate("monitor/$id") },
                    onEditPanel = { id -> navController.navigate("editor?panelId=$id") },
                    contentPadding = inner
                )
            }
            composable("editor?panelId={panelId}") { entry ->
                EditorScreen(
                    panelId = entry.arguments?.getString("panelId"),
                    onBack = { navController.popBackStack() },
                    contentPadding = inner
                )
            }
            composable("io") {
                IoStatusScreen(contentPadding = inner)
            }
            composable("devices") {
                DevicesScreen(contentPadding = inner)
            }
            composable("monitor/{panelId}") { entry ->
                val panelId = entry.arguments?.getString("panelId").orEmpty()
                MonitorScreen(
                    panelId = panelId,
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate("editor?panelId=$id") }
                )
            }
        }
    }
}
