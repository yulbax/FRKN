package io.github.yulbax.frkn.ui.screens

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.yulbax.frkn.R
import io.github.yulbax.frkn.ui.screens.apps.Apps
import io.github.yulbax.frkn.ui.screens.frkn.FrknScreen
import io.github.yulbax.frkn.ui.screens.settings.Settings

sealed class Screen(
    val route: String,
    @StringRes val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Main : Screen(
        route = "main",
        titleRes = R.string.frkn,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Filled.Home
    )

    object Apps : Screen(
        route = "apps",
        titleRes = R.string.applications,
        selectedIcon = Icons.AutoMirrored.Filled.List,
        unselectedIcon = Icons.AutoMirrored.Filled.List
    )

    object Settings : Screen(
        route = "settings",
        titleRes = R.string.settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Filled.Settings
    )

    object Logs : Screen(
        route = "logs",
        titleRes = R.string.logs_title,
        selectedIcon = Icons.Filled.Description,
        unselectedIcon = Icons.Filled.Description
    )

    object License : Screen(
        route = "license",
        titleRes = R.string.license_title,
        selectedIcon = Icons.Filled.Gavel,
        unselectedIcon = Icons.Filled.Gavel
    )

    object About : Screen(
        route = "about",
        titleRes = R.string.about_title,
        selectedIcon = Icons.Filled.Info,
        unselectedIcon = Icons.Filled.Info
    )
}

val primaryNavPages = listOf(
    Screen.Main,
    Screen.Apps,
    Screen.Settings,
    Screen.Logs
)

val secondaryNavPages = listOf(
    Screen.License,
    Screen.About
)

val navPages = primaryNavPages + secondaryNavPages

fun NavController.navigateToScreen(screen: Screen) {
    if (currentDestination?.route == screen.route) return
    navigate(screen.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun FrknNavHost(
    navController: NavHostController,
    appsQuery: String,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Main.route,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(200)) },
        exitTransition = { fadeOut(animationSpec = tween(200)) }
    ) {
        composable(Screen.Main.route) {
            FrknScreen(onNavigateToApps = { navController.navigateToScreen(Screen.Apps) })
        }
        composable(Screen.Apps.route) { Apps(query = appsQuery) }
        composable(Screen.Settings.route) { Settings() }
        composable(Screen.Logs.route) { Logs() }
        composable(Screen.License.route) { License() }
        composable(Screen.About.route) { About() }
    }
}
