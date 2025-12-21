package com.autoglm.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autoglm.android.R
import com.autoglm.android.ui.home.HomeScreen
import com.autoglm.android.ui.logs.LogScreen
import com.autoglm.android.ui.settings.SettingsScreen

sealed class Screen(val route: String, val labelRes: Int, val icon: @Composable () -> Unit) {
    object Home : Screen("home", R.string.nav_home, { Icon(Icons.Default.Home, contentDescription = null) })
    object Settings : Screen("settings", R.string.nav_settings, { Icon(Icons.Default.Settings, contentDescription = null) })
    object Logs : Screen("logs", R.string.nav_logs, { })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoGLMApp() {
    val navController = rememberNavController()
    val screens = listOf(Screen.Home, Screen.Settings)
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = screen.icon,
                        label = { Text(stringResource(screen.labelRes)) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) { HomeScreen() }
            composable(Screen.Settings.route) { 
                SettingsScreen(
                    onNavigateToLogs = { navController.navigate(Screen.Logs.route) }
                )
            }
            composable(Screen.Logs.route) {
                LogScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
