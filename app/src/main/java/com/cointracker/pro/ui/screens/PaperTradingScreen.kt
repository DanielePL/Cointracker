package com.cointracker.pro.ui.screens

import android.app.Application
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cointracker.pro.data.repository.AutoTradingRepository
import com.cointracker.pro.data.repository.AutoTradingSettings
import com.cointracker.pro.data.supabase.PaperHoldingWithPrice
import com.cointracker.pro.data.supabase.PaperTrade
import com.cointracker.pro.data.supabase.SupabaseAuthRepository
import com.cointracker.pro.data.trading.AutoTradeAction
import com.cointracker.pro.data.trading.AutoTradingService
import kotlinx.coroutines.launch
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.theme.*
import com.cointracker.pro.viewmodel.PaperTradingViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PaperTradingScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: PaperTradingViewModel = viewModel(
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    )
    val uiState by viewModel.uiState.collectAsState()

    // Check if user is logged in
    val authRepository = remember { SupabaseAuthRepository() }
    val isLoggedIn = remember { authRepository.isLoggedIn() }

    // Auto Trading - Cloud-based via Edge Function
    val autoTradingRepository = remember { AutoTradingRepository() }
    val scope = rememberCoroutineScope()

    // Also keep local service for immediate feedback
    val autoTradingService = remember { AutoTradingService.getInstance(context) }
    val localIsRunning by autoTradingService.isRunning.collectAsState()
    val lastAction by autoTradingService.lastAction.collectAsState()
    val tradeLog by autoTradingService.tradeLog.collectAsState()

    // Cloud settings
    var cloudSettings by remember { mutableStateOf<AutoTradingSettings?>(null) }
    var isLoadingSettings by remember { mutableStateOf(true) }
    val userId = authRepository.getCurrentUser()?.id

    // Load cloud settings
    LaunchedEffect(userId) {
        if (userId != null) {
            autoTradingRepository.getSettings(userId)
                .onSuccess { settings ->
                    cloudSettings = settings
                    // Sync local service with cloud
                    if (settings.enabled && !localIsRunning) {
                        autoTradingService.start()
                    } else if (!settings.enabled && localIsRunning) {
                        autoTradingService.stop()
                    }
                }
            isLoadingSettings = false
        }
    }

    val isAutoTrading = cloudSettings?.enabled ?: false

    var showResetDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    // Refresh periodically when auto-trading
    LaunchedEffect(isAutoTrading) {
        if (isAutoTrading) {
            while (true) {
                kotlinx.coroutines.delay(30_000) // Refresh every 30 seconds
                viewModel.loadPaperPortfolio()
            }
        }
    }

    GradientBackground {
        // Show login prompt if not logged in
        if (!isLoggedIn) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = AccentOrange,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Paper Trading",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bitte logge dich ein um Paper Trading zu nutzen",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Gehe zu Settings → Login",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ElectricBlue
                    )
                }
            }
            return@GradientBackground
        }
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
                            text = "Paper Trading",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Virtual Portfolio",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentOrange
                        )
                    }

                    Row {
                        IconButton(onClick = { viewModel.loadPaperPortfolio() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = ElectricBlue
                            )
                        }
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = "Reset Account",
                                tint = BearishRed
                            )
                        }
                    }
                }
            }

            // Auto Trading Card
            item {
                AutoTradingCard(
                    isRunning = isAutoTrading,
                    isLoading = isLoadingSettings,
                    lastAction = lastAction,
                    onToggle = {
                        if (userId != null) {
                            val newEnabled = !isAutoTrading
                            scope.launch {
                                // Update cloud settings
                                autoTradingRepository.setEnabled(userId, newEnabled)
                                    .onSuccess {
                                        // Update local state
                                        cloudSettings = cloudSettings?.copy(enabled = newEnabled)
                                        // Sync local service
                                        if (newEnabled) {
                                            autoTradingService.start()
                                        } else {
                                            autoTradingService.stop()
                                        }
                                    }
                            }
                        }
                    },
                    onShowLog = { showLogDialog = true },
                    logCount = tradeLog.size
                )
            }

            // Loading
            if (uiState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ElectricBlue)
                    }
                }
            }

            // Portfolio Value Card
            uiState.balance?.let { balance ->
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(
                                text = "Total Portfolio Value",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$${String.format("%,.2f", uiState.totalPortfolioValue)}",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // P&L Display
                            val pnl = uiState.totalPortfolioValue - balance.initialBalance
                            val pnlPercent = if (balance.initialBalance > 0)
                                (pnl / balance.initialBalance) * 100 else 0.0
                            val isPositive = pnl >= 0

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isPositive) BullishGreen.copy(alpha = 0.15f)
                                        else BearishRed.copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    if (isPositive) Icons.AutoMirrored.Filled.TrendingUp
                                    else Icons.AutoMirrored.Filled.TrendingDown,
                                    contentDescription = null,
                                    tint = if (isPositive) BullishGreen else BearishRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${if (isPositive) "+" else ""}$${String.format("%,.2f", pnl)} (${if (isPositive) "+" else ""}${String.format("%.2f", pnlPercent)}%)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPositive) BullishGreen else BearishRed
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                            Spacer(modifier = Modifier.height(16.dp))

                            // Balance breakdown
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Available USDT", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        "$${String.format("%,.2f", balance.balanceUsdt)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = ElectricBlue
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Holdings Value", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        "$${String.format("%,.2f", uiState.holdingsValue)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = AccentOrange
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Performance Stats
            uiState.stats?.let { stats ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = "Total Trades",
                            value = "${stats.totalTrades}",
                            color = ElectricBlue
                        )
                        StatCard(
                            modifier = Modifier.weight(1f),
                            label = "Win Rate",
                            value = "${String.format("%.1f", stats.winRate)}%",
                            color = if (stats.winRate >= 50) BullishGreen else BearishRed
                        )
                    }
                }
            }

            // Holdings Section
            if (uiState.holdings.isNotEmpty()) {
                item {
                    Text(
                        text = "Holdings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                items(uiState.holdings) { holdingWithPrice ->
                    HoldingCard(holdingWithPrice)
                }
            }

            // Recent Trades Section
            if (uiState.recentTrades.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recent Trades",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                items(uiState.recentTrades.take(10)) { trade ->
                    TradeCard(trade)
                }
            }

            // Empty state
            if (!uiState.isLoading && uiState.holdings.isEmpty() && uiState.recentTrades.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (isAutoTrading) Icons.Default.AutoMode else Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = if (isAutoTrading) BullishGreen else TextMuted,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (isAutoTrading) "Auto Trading aktiv" else "Keine Trades",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = if (isAutoTrading) "Warte auf starke Signale..." else "Aktiviere Auto Trading oben!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
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
                                text = "Error",
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
                        }
                    }
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }

        // Reset Confirmation Dialog
        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = {
                    Text("Reset Account?", color = TextPrimary)
                },
                text = {
                    Text(
                        "This will reset your paper trading account to $10,000 and delete all holdings and trade history.",
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.resetAccount()
                            showResetDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BearishRed)
                    ) {
                        Text("Reset")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showResetDialog = false },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = DeepBlue,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Trade Log Dialog
        if (showLogDialog) {
            AlertDialog(
                onDismissRequest = { showLogDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = ElectricBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Auto Trading Log", color = TextPrimary)
                    }
                },
                text = {
                    if (tradeLog.isEmpty()) {
                        Text("Noch keine Aktivitäten", color = TextMuted)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(tradeLog) { log ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when (log.type) {
                                                "BUY" -> BullishGreen.copy(alpha = 0.1f)
                                                "SELL" -> AccentOrange.copy(alpha = 0.1f)
                                                "ERROR" -> BearishRed.copy(alpha = 0.1f)
                                                else -> Color.White.copy(alpha = 0.05f)
                                            }
                                        )
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = log.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary
                                        )
                                    }
                                    Text(
                                        text = log.timestamp,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLogDialog = false }) {
                        Text("Schließen", color = ElectricBlue)
                    }
                },
                containerColor = DeepBlue,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun AutoTradingCard(
    isRunning: Boolean,
    isLoading: Boolean,
    lastAction: AutoTradeAction?,
    onToggle: () -> Unit,
    onShowLog: () -> Unit,
    logCount: Int
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRunning) BullishGreen.copy(alpha = pulseAlpha)
                                else TextMuted.copy(alpha = 0.5f)
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Auto Trading",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isRunning) "Aktiv - Handelt automatisch" else "Inaktiv",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRunning) BullishGreen else TextMuted
                        )
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = ElectricBlue,
                        strokeWidth = 2.dp
                    )
                } else {
                    Switch(
                        checked = isRunning,
                        onCheckedChange = { onToggle() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BullishGreen,
                            checkedTrackColor = BullishGreen.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                }
            }

            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Current Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (lastAction) {
                                    is AutoTradeAction.Analyzing -> Icons.Default.Search
                                    is AutoTradeAction.Buying -> Icons.AutoMirrored.Filled.TrendingUp
                                    is AutoTradeAction.Selling -> Icons.AutoMirrored.Filled.TrendingDown
                                    is AutoTradeAction.Waiting -> Icons.Default.Schedule
                                    null -> Icons.Default.Schedule
                                },
                                contentDescription = null,
                                tint = when (lastAction) {
                                    is AutoTradeAction.Buying -> BullishGreen
                                    is AutoTradeAction.Selling -> BearishRed
                                    else -> ElectricBlue
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (lastAction) {
                                    is AutoTradeAction.Analyzing -> "Analysiere Signale..."
                                    is AutoTradeAction.Buying -> "Kaufe ${lastAction.symbol}..."
                                    is AutoTradeAction.Selling -> "Verkaufe ${lastAction.symbol}..."
                                    is AutoTradeAction.Waiting -> lastAction.message
                                    null -> "Warte..."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }

                        // Log button
                        TextButton(onClick = onShowLog) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = ElectricBlue
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Log ($logCount)", color = ElectricBlue)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Cloud info badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(ElectricBlue.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "24/7 Cloud Trading aktiv",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricBlue
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Info text
                    Text(
                        text = "• Kauft bei STRONG_BUY Signalen (Score ≥70)\n• Verkauft bei STRONG_SELL oder Stop-Loss (-5%)\n• Take-Profit bei +10%\n• Max. 3 Positionen gleichzeitig",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    GlassCard(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
private fun HoldingCard(holdingWithPrice: PaperHoldingWithPrice) {
    val holding = holdingWithPrice.holding
    val isPositive = holdingWithPrice.pnl >= 0
    val coinSymbol = holding.symbol.replace("USDT", "")

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ElectricBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = coinSymbol.take(2),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricBlue
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = coinSymbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${formatQuantity(holding.quantity)} @ $${formatPrice(holding.avgEntryPrice)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${formatPrice(holdingWithPrice.currentValue)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isPositive) BullishGreen.copy(alpha = 0.15f)
                            else BearishRed.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        if (isPositive) Icons.AutoMirrored.Filled.TrendingUp
                        else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        tint = if (isPositive) BullishGreen else BearishRed,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${if (isPositive) "+" else ""}${String.format("%.2f", holdingWithPrice.pnlPercent)}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isPositive) BullishGreen else BearishRed
                    )
                }
            }
        }
    }
}

@Composable
private fun TradeCard(trade: PaperTrade) {
    val isBuy = trade.side == "BUY"
    val coinSymbol = trade.symbol.replace("USDT", "")
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val tradeDate = try {
        trade.createdAt?.let { dateFormat.format(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(it) ?: Date()) } ?: ""
    } catch (e: Exception) {
        ""
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            if (isBuy) BullishGreen.copy(alpha = 0.2f)
                            else BearishRed.copy(alpha = 0.2f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isBuy) Icons.AutoMirrored.Filled.TrendingUp
                        else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        tint = if (isBuy) BullishGreen else BearishRed,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = trade.side,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isBuy) BullishGreen else BearishRed
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = coinSymbol,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = tradeDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${formatQuantity(trade.quantity)} $coinSymbol",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = "@ $${formatPrice(trade.entryPrice)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                if (trade.pnl != null && trade.pnl != 0.0) {
                    val isProfit = trade.pnl > 0
                    Text(
                        text = "${if (isProfit) "+" else ""}$${formatPrice(trade.pnl)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isProfit) BullishGreen else BearishRed
                    )
                }
            }
        }
    }
}

private fun formatPrice(price: Double): String {
    return when {
        price >= 1000 -> String.format("%,.2f", price)
        price >= 1 -> String.format("%.2f", price)
        price >= 0.01 -> String.format("%.4f", price)
        else -> String.format("%.8f", price)
    }
}

private fun formatQuantity(qty: Double): String {
    return when {
        qty >= 1000 -> String.format("%,.2f", qty)
        qty >= 1 -> String.format("%.4f", qty)
        else -> String.format("%.8f", qty)
    }
}
