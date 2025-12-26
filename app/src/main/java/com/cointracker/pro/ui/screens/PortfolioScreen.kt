package com.cointracker.pro.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cointracker.pro.data.binance.BinanceRepository
import com.cointracker.pro.data.binance.PortfolioAsset
import com.cointracker.pro.data.binance.PortfolioSummary
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GlassCardSmall
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// Farben für die Allocation Chart
private val AllocationColors = listOf(
    Color(0xFF00D4FF), // Electric Blue
    Color(0xFFFF6B35), // Orange
    Color(0xFF00E676), // Green
    Color(0xFFFFD740), // Yellow
    Color(0xFFE040FB), // Purple
    Color(0xFF40C4FF), // Light Blue
    Color(0xFFFF5252), // Red
    Color(0xFF69F0AE), // Light Green
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen() {
    val context = LocalContext.current
    val repository = remember { BinanceRepository(context) }
    val scope = rememberCoroutineScope()

    var portfolio by remember { mutableStateOf<PortfolioSummary?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pullToRefreshState = rememberPullToRefreshState()

    fun loadPortfolio(showRefreshIndicator: Boolean = false) {
        scope.launch {
            if (showRefreshIndicator) isRefreshing = true else isLoading = true
            error = null

            repository.getPortfolioSummary()
                .onSuccess {
                    portfolio = it
                    isLoading = false
                    isRefreshing = false
                }
                .onFailure {
                    error = it.message ?: "Unknown error"
                    isLoading = false
                    isRefreshing = false
                }
        }
    }

    LaunchedEffect(Unit) {
        loadPortfolio()
    }

    GradientBackground {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadPortfolio(showRefreshIndicator = true) },
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically { -it / 2 }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Portfolio",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Your Binance holdings",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }

                            IconButton(
                                onClick = { loadPortfolio(showRefreshIndicator = true) },
                                enabled = !isRefreshing
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = ElectricBlue,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Refresh",
                                        tint = ElectricBlue
                                    )
                                }
                            }
                        }
                    }
                }

                // Loading state with shimmer
                if (isLoading) {
                    item { ShimmerTotalValueCard() }
                    item { ShimmerQuickStats() }
                    items(3) { ShimmerAssetRow() }
                }

                // Error state
                error?.let { err ->
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(
                                    text = "Connection Error",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BearishRed
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Make sure your Binance API keys are configured in Settings with 'Read' permissions enabled.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = { loadPortfolio() },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = ElectricBlue
                                    )
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                // Portfolio content
                portfolio?.let { p ->
                    // Total Value Card with animated counter
                    item {
                        AnimatedTotalValueCard(portfolio = p)
                    }

                    // Portfolio Allocation Chart
                    if (p.assets.isNotEmpty()) {
                        item {
                            AllocationChart(assets = p.assets)
                        }
                    }

                    // Quick Stats with animation
                    item {
                        AnimatedQuickStats(portfolio = p)
                    }

                    // Holdings Header
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Holdings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "${p.assets.size} assets",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }

                    // Holdings List with staggered animation
                    if (p.assets.isEmpty()) {
                        item {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "No holdings found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextMuted
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Deposit some crypto to get started!",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = p.assets,
                            key = { _, asset -> asset.symbol }
                        ) { index, asset ->
                            AnimatedAssetRow(
                                asset = asset,
                                index = index,
                                color = AllocationColors[index % AllocationColors.size]
                            )
                        }
                    }
                }

                // Bottom spacing for nav bar
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun AnimatedTotalValueCard(portfolio: PortfolioSummary) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    val animatedValue by animateFloatAsState(
        targetValue = if (isVisible) portfolio.totalValueUsd.toFloat() else 0f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "totalValue"
    )

    val animatedChange by animateFloatAsState(
        targetValue = if (isVisible) portfolio.totalChangePercent24h.toFloat() else 0f,
        animationSpec = tween(durationMillis = 1000, delayMillis = 300, easing = FastOutSlowInEasing),
        label = "change"
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(500)) +
                expandVertically(animationSpec = tween(500))
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = "Total Value",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "$${formatNumber(animatedValue.toDouble())}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isPositive = portfolio.totalChangePercent24h >= 0
                    Icon(
                        if (isPositive) Icons.AutoMirrored.Filled.TrendingUp
                        else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        tint = if (isPositive) BullishGreen else BearishRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${if (isPositive) "+" else ""}${String.format("%.2f", animatedChange)}%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPositive) BullishGreen else BearishRed
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(${if (isPositive) "+" else ""}$${formatNumber(portfolio.totalChange24h)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Last updated: ${formatTime(portfolio.lastUpdated)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun AllocationChart(assets: List<PortfolioAsset>) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(300)
        isVisible = true
    }

    val animationProgress by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "chartProgress"
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(500)) + scaleIn(initialScale = 0.8f)
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    text = "Allocation",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Donut Chart
                    Box(
                        modifier = Modifier.size(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(120.dp)) {
                            val strokeWidth = 24.dp.toPx()
                            val radius = (size.minDimension - strokeWidth) / 2
                            val topLeft = Offset(
                                (size.width - 2 * radius) / 2,
                                (size.height - 2 * radius) / 2
                            )
                            val arcSize = Size(radius * 2, radius * 2)

                            var startAngle = -90f
                            val topAssets = assets.take(8)

                            topAssets.forEachIndexed { index, asset ->
                                val sweepAngle = (asset.percentOfPortfolio / 100f * 360f * animationProgress).toFloat()
                                val color = AllocationColors[index % AllocationColors.size]

                                drawArc(
                                    color = color,
                                    startAngle = startAngle,
                                    sweepAngle = sweepAngle,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                )
                                startAngle += sweepAngle
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${assets.size}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Assets",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Legend
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        assets.take(5).forEachIndexed { index, asset ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(AllocationColors[index % AllocationColors.size])
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = asset.symbol,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "${String.format("%.1f", asset.percentOfPortfolio)}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        if (assets.size > 5) {
                            Text(
                                text = "+${assets.size - 5} more",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedQuickStats(portfolio: PortfolioSummary) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(500)
        isVisible = true
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically { it / 2 }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val gainers = portfolio.assets.count { it.priceChange24h > 0 }
            val losers = portfolio.assets.count { it.priceChange24h < 0 }

            StatCard(
                label = "Assets",
                value = "${portfolio.assets.size}",
                color = ElectricBlue,
                modifier = Modifier.weight(1f),
                delay = 0
            )

            StatCard(
                label = "Gainers",
                value = "$gainers",
                color = BullishGreen,
                modifier = Modifier.weight(1f),
                delay = 100
            )

            StatCard(
                label = "Losers",
                value = "$losers",
                color = BearishRed,
                modifier = Modifier.weight(1f),
                delay = 200
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    delay: Int = 0
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(delay.toLong())
        isVisible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.8f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    GlassCardSmall(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun AnimatedAssetRow(
    asset: PortfolioAsset,
    index: Int,
    color: Color
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(700L + index * 80L)
        isVisible = true
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(300)) +
                slideInHorizontally(
                    initialOffsetX = { it / 3 },
                    animationSpec = tween(300)
                )
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Coin info
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Coin Icon with color indicator
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            color.copy(alpha = 0.3f),
                                            color.copy(alpha = 0.1f)
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = asset.symbol.take(2),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = color
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = asset.symbol,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = asset.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    // Right: Value and change
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$${formatNumber(asset.valueUsd)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isPositive = asset.priceChange24h >= 0
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isPositive) BullishGreen.copy(alpha = 0.2f)
                                        else BearishRed.copy(alpha = 0.2f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${if (isPositive) "+" else ""}${String.format("%.2f", asset.priceChange24h)}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPositive) BullishGreen else BearishRed
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Allocation bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction = (asset.percentOfPortfolio / 100f).toFloat().coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(color, color.copy(alpha = 0.5f))
                                    )
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${String.format("%.1f", asset.percentOfPortfolio)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Amount info
                Text(
                    text = "${formatAmount(asset.amount)} ${asset.symbol} @ $${formatPrice(asset.currentPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
        }
    }
}

// Shimmer Loading Components
@Composable
private fun ShimmerTotalValueCard() {
    val shimmerColors = listOf(
        Color.White.copy(alpha = 0.1f),
        Color.White.copy(alpha = 0.2f),
        Color.White.copy(alpha = 0.1f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            ShimmerBox(width = 80.dp, height = 16.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(width = 180.dp, height = 36.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(width = 120.dp, height = 20.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
        }
    }
}

@Composable
private fun ShimmerQuickStats() {
    val shimmerColors = listOf(
        Color.White.copy(alpha = 0.1f),
        Color.White.copy(alpha = 0.2f),
        Color.White.copy(alpha = 0.1f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) {
            GlassCardSmall(modifier = Modifier.weight(1f)) {
                Column {
                    ShimmerBox(width = 50.dp, height = 14.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
                    Spacer(modifier = Modifier.height(4.dp))
                    ShimmerBox(width = 30.dp, height = 24.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
                }
            }
        }
    }
}

@Composable
private fun ShimmerAssetRow() {
    val shimmerColors = listOf(
        Color.White.copy(alpha = 0.1f),
        Color.White.copy(alpha = 0.2f),
        Color.White.copy(alpha = 0.1f)
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = shimmerColors,
                                start = Offset(translateAnim, 0f),
                                end = Offset(translateAnim + 200f, 0f)
                            )
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    ShimmerBox(width = 60.dp, height = 18.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
                    Spacer(modifier = Modifier.height(4.dp))
                    ShimmerBox(width = 80.dp, height = 14.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                ShimmerBox(width = 70.dp, height = 18.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
                Spacer(modifier = Modifier.height(4.dp))
                ShimmerBox(width = 50.dp, height = 16.dp, translateAnim = translateAnim, shimmerColors = shimmerColors)
            }
        }
    }
}

@Composable
private fun ShimmerBox(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    translateAnim: Float,
    shimmerColors: List<Color>
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(
                Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(translateAnim, 0f),
                    end = Offset(translateAnim + 200f, 0f)
                )
            )
    )
}

private fun formatNumber(value: Double): String {
    return when {
        value >= 1_000_000 -> String.format("%.2fM", value / 1_000_000)
        value >= 1_000 -> String.format("%,.0f", value)
        value >= 1 -> String.format("%.2f", value)
        else -> String.format("%.4f", value)
    }
}

private fun formatAmount(amount: Double): String {
    return when {
        amount >= 1000 -> String.format("%,.0f", amount)
        amount >= 1 -> String.format("%.4f", amount)
        amount >= 0.0001 -> String.format("%.6f", amount)
        else -> String.format("%.8f", amount)
    }
}

private fun formatPrice(price: Double): String {
    return when {
        price >= 1000 -> String.format("%,.0f", price)
        price >= 1 -> String.format("%.2f", price)
        price >= 0.01 -> String.format("%.4f", price)
        else -> String.format("%.6f", price)
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}