package com.cointracker.pro.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Primary Colors - Deep Blue Theme
val DeepBlue = Color(0xFF0A1628)
val DarkBlue = Color(0xFF0D1B2A)
val NavyBlue = Color(0xFF1B263B)
val MidnightBlue = Color(0xFF152238)
val SpaceBlack = Color(0xFF050A12)

// Accent Colors
val ElectricBlue = Color(0xFF00D4FF)
val CyanGlow = Color(0xFF00E5FF)
val AccentOrange = Color(0xFFFF6B35)
val WarmOrange = Color(0xFFFF8C42)

// Neon Accent Colors (Modern)
val NeonPurple = Color(0xFFBF40FF)
val NeonPink = Color(0xFFFF2D92)
val NeonMint = Color(0xFF00FFA3)
val NeonYellow = Color(0xFFFFE600)
val NeonBlue = Color(0xFF4D9FFF)

// Status Colors
val BullishGreen = Color(0xFF00E676)
val BearishRed = Color(0xFFFF5252)
val NeutralYellow = Color(0xFFFFD740)
val ErrorRed = Color(0xFFFF5252)

// Premium Gradient Status Colors
val ProfitGradientStart = Color(0xFF00E676)
val ProfitGradientEnd = Color(0xFF00FFA3)
val LossGradientStart = Color(0xFFFF5252)
val LossGradientEnd = Color(0xFFFF2D92)

// Glassmorphism
val GlassWhite = Color(0x1AFFFFFF)
val GlassBorder = Color(0x33FFFFFF)
val GlassHighlight = Color(0x0DFFFFFF)
val GlassDark = Color(0x1A000000)

// Text Colors
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xB3FFFFFF)
val TextMuted = Color(0x66FFFFFF)
val TextAccent = Color(0xFF00D4FF)

// Gradient Colors for Background
val GradientStart = Color(0xFF0A1628)
val GradientMid = Color(0xFF162447)
val GradientEnd = Color(0xFF1F4068)

// Premium Background Gradients
val GradientPurpleStart = Color(0xFF1A0A2E)
val GradientPurpleMid = Color(0xFF2D1B4E)
val GradientPurpleEnd = Color(0xFF3D2A5C)

// Chart Colors
val ChartLine = Color(0xFF00D4FF)
val ChartFill = Color(0x3300D4FF)
val ChartGrid = Color(0x1AFFFFFF)
val ChartGradientTop = Color(0x6600D4FF)
val ChartGradientBottom = Color(0x0000D4FF)

// Card Surface Colors
val CardSurface = Color(0xFF0F1A2A)
val CardSurfaceElevated = Color(0xFF142232)
val CardBorder = Color(0x20FFFFFF)

// Interactive States
val RippleColor = Color(0x2000D4FF)
val SelectedBg = Color(0x1500D4FF)
val HoverBg = Color(0x0AFFFFFF)

// Pre-built gradient brushes
val ProfitGradient = Brush.linearGradient(
    colors = listOf(ProfitGradientStart, ProfitGradientEnd)
)

val LossGradient = Brush.linearGradient(
    colors = listOf(LossGradientStart, LossGradientEnd)
)

val PremiumGradient = Brush.linearGradient(
    colors = listOf(ElectricBlue, NeonPurple, NeonPink)
)

val GoldGradient = Brush.linearGradient(
    colors = listOf(Color(0xFFFFD700), Color(0xFFFFAA00), Color(0xFFFF8C00))
)

val SilverGradient = Brush.linearGradient(
    colors = listOf(Color(0xFFE8E8E8), Color(0xFFC0C0C0), Color(0xFF909090))
)

val NeonGradient = Brush.linearGradient(
    colors = listOf(NeonBlue, NeonPurple, NeonPink)
)
