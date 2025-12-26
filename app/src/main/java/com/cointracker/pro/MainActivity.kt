package com.cointracker.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cointracker.pro.data.supabase.SupabaseAuthRepository
import com.cointracker.pro.ui.navigation.AppNavHost
import com.cointracker.pro.ui.navigation.Screen
import com.cointracker.pro.ui.navigation.bottomNavItems
import com.cointracker.pro.ui.theme.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CoinTrackerTheme {
                CoinTrackerApp()
            }
        }
    }
}

@Composable
fun CoinTrackerApp() {
    val authRepository = remember { SupabaseAuthRepository() }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Auth state
    var isCheckingAuth by remember { mutableStateOf(true) }
    var isAuthenticated by remember { mutableStateOf(false) }

    // Check authentication state on startup
    LaunchedEffect(Unit) {
        // Small delay for splash effect
        delay(300)
        isAuthenticated = authRepository.isLoggedIn()
        isCheckingAuth = false
    }

    // Observe auth state changes
    LaunchedEffect(Unit) {
        authRepository.observeAuthState().collect { user ->
            isAuthenticated = user != null
        }
    }

    // Determine start destination based on auth state
    val startDestination = if (isAuthenticated) Screen.Dashboard.route else Screen.Login.route

    // Show loading while checking auth
    if (isCheckingAuth) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = ElectricBlue)
        }
        return
    }

    // Hide bottom nav on login screen
    val showBottomNav = currentRoute != Screen.Login.route && isAuthenticated

    Scaffold(
        containerColor = DeepBlue,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomNav,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                NavigationBar(
                    containerColor = DarkBlue.copy(alpha = 0.95f),
                    contentColor = TextPrimary
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.title
                                )
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            selected = selected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(Screen.Dashboard.route) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricBlue,
                                selectedTextColor = ElectricBlue,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = ElectricBlue.copy(alpha = 0.15f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            startDestination = startDestination,
            onLoginSuccess = {
                // Navigate to dashboard and clear login from back stack
                navController.navigate(Screen.Dashboard.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            },
            onLogout = {
                // Navigate to login and clear entire back stack
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        )
    }
}
