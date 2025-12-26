package com.cointracker.pro.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cointracker.pro.ui.components.CandlestickChart
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GlassCardSmall
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.components.TradeDialog
import com.cointracker.pro.ui.theme.*
import com.cointracker.pro.data.supabase.SupabaseAuthRepository
import com.cointracker.pro.viewmodel.DashboardViewModel
import com.cointracker.pro.viewmodel.MarketCoin
import com.cointracker.pro.viewmodel.PaperTradingViewModel
import android.app.Application
import androidx.compose.ui.platform.LocalContext

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: DashboardViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )
    val uiState by viewModel.uiState.collectAsState()
    val selectedSymbol by viewModel.selectedSymbol.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()

    // Check if user is logged in for Paper Trading
    val authRepository = remember { SupabaseAuthRepository() }
    val isLoggedIn = remember { authRepository.isLoggedIn() }

    // Paper Trading ViewModel (only load if logged in)
    val paperViewModel: PaperTradingViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )
    val paperState by paperViewModel.uiState.collectAsState()

    // Trade Dialog State
    var showBuyDialog by remember { mutableStateOf(false) }
    var showSellDialog by remember { mutableStateOf(false) }

    // Get current price and holdings for the selected symbol
    val binanceSymbol = selectedSymbol.replace("/", "")
    val currentPrice = uiState.ticker?.price ?: 0.0
    val availableBalance = if (isLoggedIn) paperState.balance?.balanceUsdt ?: 0.0 else 0.0
    val availableQuantity = if (isLoggedIn) paperViewModel.getHoldingQuantity(binanceSymbol) else 0.0

    val symbols = listOf("BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT")

    GradientBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "CoinTracker Pro",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Live Market Data",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Refresh Button
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = ElectricBlue
                            )
                        }

                        // Live WebSocket Status with pulsing animation
                        if (uiState.isLive) {
                            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                            val pulseAlpha by infiniteTransition.animateFloat(
                                initialValue = 1f,
                                targetValue = 0.4f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulseAlpha"
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(BullishGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(BullishGreen.copy(alpha = pulseAlpha))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LIVE",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BullishGreen
                                    )
                                }
                            }
                        } else {
                            // Fallback: REST API connection status
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (uiState.isConnected) AccentOrange else BearishRed)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (uiState.isConnected) "API" else "Offline",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Symbol Selector
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(symbols) { symbol ->
                        FilterChip(
                            selected = symbol == selectedSymbol,
                            onClick = { viewModel.selectSymbol(symbol) },
                            label = {
                                Text(
                                    text = symbol.replace("/USDT", ""),
                                    color = if (symbol == selectedSymbol) DeepBlue else TextPrimary
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricBlue,
                                containerColor = GlassWhite
                            )
                        )
                    }
                }
            }

            // Debug Status (shows what's happening)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DebugChip(
                        label = "API",
                        value = if (uiState.isConnected) "OK" else "ERR",
                        isOk = uiState.isConnected
                    )
                    DebugChip(
                        label = "WS",
                        value = if (uiState.isLive) "LIVE" else "OFF",
                        isOk = uiState.isLive
                    )
                    DebugChip(
                        label = "Ticker",
                        value = if (uiState.ticker != null) "OK" else "NONE",
                        isOk = uiState.ticker != null
                    )
                    DebugChip(
                        label = "Chart",
                        value = "${uiState.ohlcv.size}",
                        isOk = uiState.ohlcv.isNotEmpty()
                    )
                    DebugChip(
                        label = "F&G",
                        value = uiState.fearGreed?.value?.toString() ?: "-",
                        isOk = uiState.fearGreed != null
                    )
                }
            }

            // Loading
            if (uiState.isLoading && uiState.ticker == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = ElectricBlue)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Loading market data...", color = TextSecondary)
                        }
                    }
                }
            }

            // Price Card with Live Price
            uiState.ticker?.let { ticker ->
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedSymbol,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextSecondary
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (uiState.isLive) {
                                        val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
                                        val liveAlpha by infiniteTransition.animateFloat(
                                            initialValue = 1f,
                                            targetValue = 0.5f,
                                            animationSpec = infiniteRepeatable(
                                                animation = tween(800, easing = LinearEasing),
                                                repeatMode = RepeatMode.Reverse
                                            ),
                                            label = "liveAlpha"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(BullishGreen.copy(alpha = 0.2f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(BullishGreen.copy(alpha = liveAlpha))
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "LIVE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BullishGreen
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Icon(
                                        Icons.Default.Wifi,
                                        contentDescription = null,
                                        tint = if (uiState.isLive) BullishGreen else AccentOrange,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "BINANCE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentOrange
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = "$${formatPrice(ticker.price)}",
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                val isPositive = ticker.changePercent24h >= 0
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (isPositive) BullishGreen.copy(alpha = 0.2f)
                                            else BearishRed.copy(alpha = 0.2f)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        if (isPositive) Icons.AutoMirrored.Filled.TrendingUp
                                        else Icons.AutoMirrored.Filled.TrendingDown,
                                        contentDescription = null,
                                        tint = if (isPositive) BullishGreen else BearishRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${if (isPositive) "+" else ""}${String.format("%.2f", ticker.changePercent24h)}%",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPositive) BullishGreen else BearishRed
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("24h High", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        "$${formatPrice(ticker.high)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BullishGreen
                                    )
                                }
                                Column {
                                    Text("24h Low", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        "$${formatPrice(ticker.low)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BearishRed
                                    )
                                }
                                Column {
                                    Text("24h Volume", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        formatVolume(ticker.volume),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Candlestick Chart
            if (uiState.ohlcv.isNotEmpty()) {
                item {
                    CandlestickChart(
                        ohlcvData = uiState.ohlcv,
                        symbol = selectedSymbol,
                        selectedTimeframe = selectedTimeframe,
                        onTimeframeSelected = { viewModel.selectTimeframe(it) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Paper Trading Buttons
            item {
                Column {
                    // Show login hint if not logged in
                    if (!isLoggedIn) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentOrange.copy(alpha = 0.1f))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Paper Trading",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AccentOrange
                                )
                                Text(
                                    text = "Login für Paper Trading",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                            Icon(
                                Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        // Paper Trading Balance (logged in)
                        if (paperState.balance != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Paper Trading",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AccentOrange
                                    )
                                    Text(
                                        text = "$${String.format("%,.2f", availableBalance)} USDT",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                if (availableQuantity > 0) {
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "Holdings",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                        Text(
                                            text = "${String.format("%.6f", availableQuantity)} ${selectedSymbol.replace("/USDT", "")}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = ElectricBlue
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // BUY / SELL Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // BUY Button
                            Button(
                                onClick = { showBuyDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BullishGreen,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                enabled = currentPrice > 0 && availableBalance > 0
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.TrendingUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "BUY",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }

                            // SELL Button
                            Button(
                                onClick = { showSellDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = BearishRed,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                enabled = currentPrice > 0 && availableQuantity > 0
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.TrendingDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SELL",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // Top Gainers
            if (uiState.topGainers.isNotEmpty()) {
                item {
                    Text(
                        text = "Top Gainers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = BullishGreen
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.topGainers) { coin ->
                            MarketCoinCard(coin = coin, isGainer = true)
                        }
                    }
                }
            }

            // Top Losers
            if (uiState.topLosers.isNotEmpty()) {
                item {
                    Text(
                        text = "Top Losers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = BearishRed
                    )
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.topLosers) { coin ->
                            MarketCoinCard(coin = coin, isGainer = false)
                        }
                    }
                }
            }

            // Technical Indicators
            uiState.indicators?.let { ind ->
                item {
                    Text(
                        text = "Technical Indicators",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        GlassCardSmall(modifier = Modifier.weight(1f)) {
                            Column {
                                Text("RSI (14)", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                                Text(
                                    text = ind.rsi?.let { String.format("%.1f", it) } ?: "-",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = when (ind.rsiSignal) {
                                        "oversold" -> BullishGreen
                                        "overbought" -> BearishRed
                                        else -> TextPrimary
                                    }
                                )
                                Text(
                                    text = ind.rsiSignal?.uppercase() ?: "-",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }

                        GlassCardSmall(modifier = Modifier.weight(1f)) {
                            Column {
                                Text("MACD", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                                Text(
                                    text = ind.macdTrend?.uppercase() ?: "-",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = when (ind.macdTrend) {
                                        "bullish" -> BullishGreen
                                        "bearish" -> BearishRed
                                        else -> TextPrimary
                                    }
                                )
                            }
                        }

                        GlassCardSmall(modifier = Modifier.weight(1f)) {
                            Column {
                                Text("EMA", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                                Text(
                                    text = ind.emaTrend?.uppercase() ?: "-",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = when (ind.emaTrend) {
                                        "bullish" -> BullishGreen
                                        "bearish" -> BearishRed
                                        else -> TextPrimary
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Fear & Greed
            uiState.fearGreed?.let { fg ->
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Fear & Greed Index",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = fg.classification,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted
                                )
                            }

                            val fgColor = when {
                                fg.value <= 25 -> BearishRed
                                fg.value <= 45 -> AccentOrange
                                fg.value <= 55 -> NeutralYellow
                                fg.value <= 75 -> CyanGlow
                                else -> BullishGreen
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(64.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { fg.value / 100f },
                                    modifier = Modifier.fillMaxSize(),
                                    color = fgColor,
                                    trackColor = GlassWhite,
                                    strokeWidth = 6.dp
                                )
                                Text(
                                    text = "${fg.value}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = fgColor
                                )
                            }
                        }
                    }
                }
            }

            // Error display
            uiState.error?.let { error ->
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
                                text = error,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { viewModel.refresh() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricBlue)
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // Trade Dialogs
        if (showBuyDialog) {
            TradeDialog(
                symbol = binanceSymbol,
                currentPrice = currentPrice,
                availableBalance = availableBalance,
                availableQuantity = 0.0, // Not needed for buy
                isBuy = true,
                isLoading = paperState.isTrading,
                onConfirm = { amount, _ ->
                    paperViewModel.executeBuy(
                        symbol = binanceSymbol,
                        amountUsdt = amount,
                        currentPrice = currentPrice
                    )
                    showBuyDialog = false
                },
                onDismiss = { showBuyDialog = false }
            )
        }

        if (showSellDialog) {
            TradeDialog(
                symbol = binanceSymbol,
                currentPrice = currentPrice,
                availableBalance = 0.0, // Not needed for sell
                availableQuantity = availableQuantity,
                isBuy = false,
                isLoading = paperState.isTrading,
                onConfirm = { _, quantity ->
                    paperViewModel.executeSell(
                        symbol = binanceSymbol,
                        quantity = quantity,
                        currentPrice = currentPrice
                    )
                    showSellDialog = false
                },
                onDismiss = { showSellDialog = false }
            )
        }
    }
}

@Composable
private fun MarketCoinCard(coin: MarketCoin, isGainer: Boolean) {
    GlassCard(
        modifier = Modifier.width(140.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isGainer) BullishGreen.copy(alpha = 0.2f) else BearishRed.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = coin.symbol.take(2),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isGainer) BullishGreen else BearishRed
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = coin.symbol,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = coin.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$${formatPrice(coin.price)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isGainer) BullishGreen.copy(alpha = 0.2f)
                        else BearishRed.copy(alpha = 0.2f)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(
                    if (isGainer) Icons.AutoMirrored.Filled.TrendingUp
                    else Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null,
                    tint = if (isGainer) BullishGreen else BearishRed,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${if (coin.change24h >= 0) "+" else ""}${String.format("%.2f", coin.change24h)}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isGainer) BullishGreen else BearishRed
                )
            }
        }
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

private fun formatVolume(volume: Double): String {
    return when {
        volume >= 1_000_000_000 -> String.format("%.2fB", volume / 1_000_000_000)
        volume >= 1_000_000 -> String.format("%.2fM", volume / 1_000_000)
        volume >= 1_000 -> String.format("%.0fK", volume / 1_000)
        else -> String.format("%.0f", volume)
    }
}

@Composable
private fun DebugChip(
    label: String,
    value: String,
    isOk: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = TextMuted
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (isOk) BullishGreen.copy(alpha = 0.2f) else BearishRed.copy(alpha = 0.2f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = value,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isOk) BullishGreen else BearishRed
            )
        }
    }
}
