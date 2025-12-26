package com.cointracker.pro.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = DeepBlue,
    primaryContainer = NavyBlue,
    onPrimaryContainer = ElectricBlue,
    secondary = AccentOrange,
    onSecondary = DeepBlue,
    secondaryContainer = NavyBlue,
    onSecondaryContainer = AccentOrange,
    tertiary = CyanGlow,
    onTertiary = DeepBlue,
    background = DeepBlue,
    onBackground = TextPrimary,
    surface = DarkBlue,
    onSurface = TextPrimary,
    surfaceVariant = NavyBlue,
    onSurfaceVariant = TextSecondary,
    error = BearishRed,
    onError = TextPrimary,
    outline = GlassBorder,
    outlineVariant = GlassHighlight
)

@Composable
fun CoinTrackerTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DeepBlue.toArgb()
            window.navigationBarColor = DeepBlue.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
