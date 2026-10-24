package com.pocketpath.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pocketpath.R
import com.pocketpath.feature.accounts.AccountsScreen
import com.pocketpath.feature.activity.ActivityScreen
import com.pocketpath.feature.goals.GoalsScreen
import com.pocketpath.feature.home.HomeScreen
import com.pocketpath.feature.plan.PlanScreen
import com.pocketpath.feature.settings.SettingsScreen
import com.pocketpath.feature.transaction.AddScreen
import com.pocketpath.ui.theme.PocketPathTheme
import kotlin.reflect.KClass

private data class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    val icon: ImageVector,
)

private val tabs = listOf(
    TopLevelDestination(Home, Home::class, R.string.nav_home, Icons.Default.Home),
    TopLevelDestination(Activity, Activity::class, R.string.nav_activity, Icons.AutoMirrored.Filled.List),
    TopLevelDestination(Plan, Plan::class, R.string.nav_plan, Icons.Default.DateRange),
    TopLevelDestination(Goals, Goals::class, R.string.nav_goals, Icons.Default.Star),
)

private fun NavDestination?.matches(route: KClass<*>): Boolean = this?.hasRoute(route) == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketPathApp(
    email: String? = null,
    authBusy: Boolean = false,
    authError: String? = null,
    onSignOut: () -> Unit = {},
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    var menuExpanded by remember { mutableStateOf(false) }
    val selectedTab = tabs.firstOrNull { destination.matches(it.routeClass) }
    val isTopLevel = selectedTab != null || destination == null
    val title = when {
        destination.matches(Add::class) -> R.string.nav_add
        destination.matches(Accounts::class) -> R.string.nav_accounts
        destination.matches(Settings::class) -> R.string.nav_settings
        else -> selectedTab?.label ?: R.string.nav_home
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    if (!isTopLevel) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back))
                        }
                    }
                },
                actions = {
                    if (isTopLevel) {
                        Box {
                            TextButton(onClick = { menuExpanded = true }) {
                                Text(stringResource(R.string.nav_more))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.nav_accounts)) },
                                    onClick = {
                                        menuExpanded = false
                                        navController.navigate(Accounts) { launchSingleTop = true }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.nav_settings)) },
                                    onClick = {
                                        menuExpanded = false
                                        navController.navigate(Settings) { launchSingleTop = true }
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = destination.matches(tab.routeClass) ||
                                (destination == null && tab.route == Home),
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (isTopLevel) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(Add) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.nav_add)) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Home,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable<Home> { HomeScreen() }
            composable<Activity> { ActivityScreen() }
            composable<Plan> { PlanScreen() }
            composable<Goals> { GoalsScreen() }
            composable<Add> { AddScreen() }
            composable<Accounts> { AccountsScreen() }
            composable<Settings> {
                SettingsScreen(
                    email = email,
                    isSigningOut = authBusy,
                    errorMessage = authError,
                    onSignOut = onSignOut,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PocketPathAppPreview() {
    PocketPathTheme { PocketPathApp() }
}
