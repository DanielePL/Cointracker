package com.cointracker.pro.ui.components

import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cointracker.pro.ui.theme.*

// Extension function for card entrance animation
fun Modifier.cardEntrance(
    visible: Boolean,
    delay: Int = 0
): Modifier = this.graphicsLayer {
    alpha = if (visible) 1f else 0f
    translationY = if (visible) 0f else 50f
}

/**
 * Enhanced Glass Card with modern glassmorphism effects
 * Features: gradient background, glowing border, subtle inner shadow
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    glowColor: Color = ElectricBlue,
    enableGlow: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .then(
                if (enableGlow) {
                    Modifier.drawBehind {
                        // Outer glow effect
                        drawRoundRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    glowColor.copy(alpha = 0.3f),
                                    glowColor.copy(alpha = 0.1f),
                                    Color.Transparent
                                ),
                                center = Offset(size.width / 2, size.height / 2),
                                radius = size.maxDimension * 0.8f
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx())
                        )
                    }
                } else Modifier
            )
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.3f),
                spotColor = Color.Black.copy(alpha = 0.3f)
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.05f),
                        Color.White.copy(alpha = 0.02f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.4f),
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.05f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                ),
                shape = shape
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * Glass Card with animated shimmer effect for premium feel
 */
@Composable
fun GlassCardShimmer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.4f),
                spotColor = Color.Black.copy(alpha = 0.4f)
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            )
            .drawWithContent {
                drawContent()
                // Shimmer overlay
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        start = Offset(size.width * shimmerOffset, 0f),
                        end = Offset(size.width * (shimmerOffset + 0.5f), size.height)
                    ),
                    blendMode = BlendMode.Plus
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.3f),
                        Color.White.copy(alpha = 0.1f)
                    )
                ),
                shape = shape
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * Neon Glass Card with colored glow effect
 */
@Composable
fun NeonGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    neonColor: Color = ElectricBlue,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    val infiniteTransition = rememberInfiniteTransition(label = "neonPulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = modifier
            .drawBehind {
                // Multi-layer glow
                for (i in 3 downTo 1) {
                    drawRoundRect(
                        color = neonColor.copy(alpha = glowAlpha * (0.2f / i)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                            (cornerRadius.toPx() + (i * 8.dp.toPx()))
                        ),
                        size = size.copy(
                            width = size.width + (i * 16.dp.toPx()),
                            height = size.height + (i * 16.dp.toPx())
                        ),
                        topLeft = Offset(-i * 8.dp.toPx(), -i * 8.dp.toPx())
                    )
                }
            }
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = neonColor.copy(alpha = 0.3f),
                spotColor = neonColor.copy(alpha = 0.5f)
            )
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        neonColor.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.08f),
                        neonColor.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        neonColor.copy(alpha = 0.8f),
                        neonColor.copy(alpha = 0.4f),
                        neonColor.copy(alpha = 0.2f)
                    )
                ),
                shape = shape
            )
            .padding(16.dp),
        content = content
    )
}

/**
 * Frosted Glass Card with blur effect (Android 12+)
 */
@Composable
fun FrostedGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    blurRadius: Dp = 20.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.2f),
                spotColor = Color.Black.copy(alpha = 0.2f)
            )
            .clip(shape)
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.blur(blurRadius)
                } else Modifier
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.1f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.5f),
                        Color.White.copy(alpha = 0.2f)
                    )
                ),
                shape = shape
            )
            .padding(16.dp),
        content = content
    )
}

@Composable
fun GlassCardSmall(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 16.dp,
        content = content
    )
}

@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GradientStart,
                        GradientMid,
                        GradientEnd
                    )
                )
            ),
        content = content
    )
}

@Composable
fun SignalScoreRing(
    score: Int,
    modifier: Modifier = Modifier
) {
    val color = when {
        score >= 70 -> BullishGreen
        score <= 30 -> BearishRed
        else -> NeutralYellow
    }

    Box(
        modifier = modifier
            .background(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        color.copy(alpha = 0.8f),
                        color.copy(alpha = 0.2f),
                        Color.Transparent
                    )
                ),
                shape = RoundedCornerShape(50)
            )
            .border(
                width = 3.dp,
                color = color,
                shape = RoundedCornerShape(50)
            )
    )
}

/**
 * Skeleton Loading Placeholder with shimmer animation
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShimmer"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color.White.copy(alpha = 0.08f))
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        start = Offset(size.width * shimmerOffset, 0f),
                        end = Offset(size.width * (shimmerOffset + 0.5f), size.height)
                    )
                )
            }
    )
}

/**
 * Skeleton Card for loading states
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    val infiniteTransition = rememberInfiniteTransition(label = "skeletonCard")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cardShimmer"
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        start = Offset(size.width * shimmerOffset, 0f),
                        end = Offset(size.width * (shimmerOffset + 0.5f), size.height)
                    )
                )
            }
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.1f),
                shape = shape
            )
    )
}

/**
 * Animated gradient border effect
 */
@Composable
fun AnimatedGradientBorder(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    borderWidth: Dp = 2.dp,
    colors: List<Color> = listOf(ElectricBlue, CyanGlow, AccentOrange, ElectricBlue),
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val infiniteTransition = rememberInfiniteTransition(label = "gradientRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "borderRotation"
    )

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = rotation }
            .clip(shape)
            .background(
                brush = Brush.sweepGradient(colors)
            )
            .padding(borderWidth)
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { rotationZ = -rotation }
                .clip(shape)
                .background(DarkBlue)
                .padding(16.dp),
            content = content
        )
    }
}

/**
 * Premium gradient background with animated color shifts
 */
@Composable
fun AnimatedGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bgGradient")
    val colorShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "colorShift"
    )

    val animatedMid = Color(
        red = GradientMid.red + (0.02f * colorShift),
        green = GradientMid.green + (0.01f * colorShift),
        blue = GradientMid.blue + (0.03f * colorShift)
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GradientStart,
                        animatedMid,
                        GradientEnd
                    )
                )
            ),
        content = content
    )
}

/**
 * Glowing text effect for important values
 */
@Composable
fun GlowingText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    glowRadius: Float = 10f
) {
    Box(modifier = modifier) {
        // Glow layer
        androidx.compose.material3.Text(
            text = text,
            color = color.copy(alpha = 0.5f),
            modifier = Modifier
                .offset(x = 0.dp, y = 0.dp)
                .blur(glowRadius.dp)
        )
        // Main text
        androidx.compose.material3.Text(
            text = text,
            color = color
        )
    }
}

/**
 * Floating card with elevation animation on interaction
 */
@Composable
fun FloatingGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    isElevated: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    val elevation by animateDpAsState(
        targetValue = if (isElevated) 24.dp else 8.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "elevation"
    )

    val scale by animateFloatAsState(
        targetValue = if (isElevated) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = ElectricBlue.copy(alpha = 0.2f),
                spotColor = ElectricBlue.copy(alpha = 0.3f)
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.4f),
                        Color.White.copy(alpha = 0.1f)
                    )
                ),
                shape = shape
            )
            .padding(16.dp),
        content = content
    )
}
