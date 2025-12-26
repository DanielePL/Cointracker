package com.cointracker.pro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.cointracker.pro.ui.screens.DashboardScreen
import com.cointracker.pro.ui.screens.HistoryScreen
import com.cointracker.pro.ui.screens.LoginScreen
import com.cointracker.pro.ui.screens.PaperTradingScreen
import com.cointracker.pro.ui.screens.PortfolioScreen
import com.cointracker.pro.ui.screens.SettingsScreen
import com.cointracker.pro.ui.screens.SignalsScreen

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Dashboard : Screen(
        route = "dashboard",
        title = "Dashboard",
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard
    )

    data object Signals : Screen(
        route = "signals",
        title = "Signals",
        selectedIcon = Icons.AutoMirrored.Filled.TrendingUp,
        unselectedIcon = Icons.AutoMirrored.Outlined.TrendingUp
    )

    data object Portfolio : Screen(
        route = "portfolio",
        title = "Portfolio",
        selectedIcon = Icons.Filled.Analytics,
        unselectedIcon = Icons.Outlined.Analytics
    )

    data object PaperTrading : Screen(
        route = "paper_trading",
        title = "Paper",
        selectedIcon = Icons.Filled.AccountBalanceWallet,
        unselectedIcon = Icons.Outlined.AccountBalanceWallet
    )

    data object History : Screen(
        route = "history",
        title = "History",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )

    // Non-bottom nav screens
    data object Login : Screen(
        route = "login",
        title = "Login",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
}

val bottomNavItems = listOf(
    Screen.Dashboard,
    Screen.Signals,
    Screen.PaperTrading,
    Screen.History,
    Screen.Settings
)

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Dashboard.route,
    onLoginSuccess: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = onLoginSuccess
            )
        }
        composable(Screen.Dashboard.route) {
            DashboardScreen()
        }
        composable(Screen.Signals.route) {
            SignalsScreen()
        }
        composable(Screen.Portfolio.route) {
            PortfolioScreen()
        }
        composable(Screen.PaperTrading.route) {
            PaperTradingScreen()
        }
        composable(Screen.History.route) {
            HistoryScreen()
        }
        composable(Screen.Settings.route) {
            SettingsScreen(onLogout = onLogout)
        }
    }
}
