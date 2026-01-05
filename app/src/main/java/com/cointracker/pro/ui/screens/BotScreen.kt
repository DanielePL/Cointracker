package com.cointracker.pro.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cointracker.pro.data.supabase.BotPosition
import com.cointracker.pro.data.supabase.BotTrade
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.theme.*
import com.cointracker.pro.ui.viewmodel.BotUiState
import com.cointracker.pro.ui.viewmodel.BotViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BotScreen() {
    val viewModel: BotViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    // Pulsing animation for live indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Autonomous Bot",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (uiState.settings.isActive) BullishGreen.copy(alpha = pulseAlpha)
                                        else BearishRed
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.settings.isActive) "Live Trading" else "Inactive",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (uiState.settings.isActive) BullishGreen else TextMuted
                            )
                        }
                    }

                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = ElectricBlue
                        )
                    }
                }
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

            // Error
            if (uiState.error != null) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = BearishRed
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.error ?: "Error loading bot data",
                                color = BearishRed,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            if (!uiState.isLoading) {
                // Balance Card
                item {
                    BotBalanceCard(uiState)
                }

                // Stats Row
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BotStatCard(
                            modifier = Modifier.weight(1f),
                            label = "Win Rate",
                            value = "${String.format("%.1f", uiState.balance.winRate)}%",
                            color = if (uiState.balance.winRate >= 50) BullishGreen else BearishRed
                        )
                        BotStatCard(
                            modifier = Modifier.weight(1f),
                            label = "Total Trades",
                            value = "${uiState.balance.totalTrades}",
                            color = ElectricBlue
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        BotStatCard(
                            modifier = Modifier.weight(1f),
                            label = "Largest Win",
                            value = "$${String.format("%.2f", uiState.balance.largestWin)}",
                            color = BullishGreen
                        )
                        BotStatCard(
                            modifier = Modifier.weight(1f),
                            label = "Largest Loss",
                            value = "$${String.format("%.2f", uiState.balance.largestLoss)}",
                            color = BearishRed
                        )
                    }
                }

                // Open Positions Section
                if (uiState.positions.isNotEmpty()) {
                    item {
                        Text(
                            text = "Open Positions (${uiState.positions.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }

                    items(uiState.positions) { position ->
                        BotPositionCard(position)
                    }
                }

                // Trade History Section
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Trade History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }

                if (uiState.trades.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No trades yet",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Bot is waiting for signals...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                } else {
                    items(uiState.trades.take(20)) { trade ->
                        BotTradeCard(trade)
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
private fun BotBalanceCard(uiState: BotUiState) {
    val balance = uiState.balance
    val isPositive = balance.totalPnl >= 0

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Bot Balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
                // Settings badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ElectricBlue.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Score: ${uiState.settings.minSignalScore}+",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$${String.format("%,.2f", balance.balanceUsdt)}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // P&L Display
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
                    text = "${if (isPositive) "+" else ""}$${String.format("%,.2f", balance.totalPnl)} (${if (isPositive) "+" else ""}${String.format("%.2f", balance.totalPnlPercent)}%)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isPositive) BullishGreen else BearishRed
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(16.dp))

            // Info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Initial Balance", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        "$${String.format("%,.2f", balance.initialBalance)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Open Positions", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        "${uiState.positions.size} / ${uiState.settings.maxPositions}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentOrange
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Max Drawdown", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        "${String.format("%.2f", balance.maxDrawdown)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (balance.maxDrawdown < -5) BearishRed else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun BotStatCard(
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
private fun BotPositionCard(position: BotPosition) {
    // Use API-provided values directly, fall back to calculated if needed
    val pnl = if (position.unrealizedPnl != 0.0) position.unrealizedPnl else position.calculatedUnrealizedPnl
    val pnlPercent = if (position.unrealizedPnlPercent != 0.0) position.unrealizedPnlPercent else position.calculatedUnrealizedPnlPercent
    val isPositive = pnlPercent >= 0
    val coinSymbol = position.coin.replace("USDT", "")

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
                        .background(AccentOrange.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = coinSymbol.take(2),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = coinSymbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = position.side,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (position.side == "LONG") BullishGreen else BearishRed,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (position.side == "LONG") BullishGreen.copy(alpha = 0.15f)
                                    else BearishRed.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = "Entry: $${formatBotPrice(position.entryPrice)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${formatBotPrice(position.totalInvested)}",
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
                        text = "${if (isPositive) "+" else ""}$${String.format("%.2f", pnl)} (${if (isPositive) "+" else ""}${String.format("%.1f", pnlPercent)}%)",
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
private fun BotTradeCard(trade: BotTrade) {
    val isBuy = trade.side == "BUY"
    val coinSymbol = trade.coin.replace("USDT", "")
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val tradeDate = try {
        trade.openedAt?.let {
            dateFormat.format(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(it) ?: Date())
        } ?: ""
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
                        // Status badge
                        if (trade.isClosed) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (trade.status) {
                                    "STOPPED_OUT" -> "SL"
                                    "TAKE_PROFIT" -> "TP"
                                    else -> "Closed"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = tradeDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    // Show signal reasons if available
                    trade.signalReasons?.take(1)?.firstOrNull()?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricBlue
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${formatBotPrice(trade.totalValue)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = "@ $${formatBotPrice(trade.entryPrice)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                // P&L for closed trades
                if (trade.pnl != null && trade.isClosed) {
                    val isProfit = trade.pnl > 0
                    Text(
                        text = "${if (isProfit) "+" else ""}$${formatBotPrice(trade.pnl)} (${String.format("%+.1f", trade.pnlPercent ?: 0.0)}%)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isProfit) BullishGreen else BearishRed
                    )
                }
            }
        }
    }
}

private fun formatBotPrice(price: Double): String {
    return when {
        price >= 1000 -> String.format("%,.2f", price)
        price >= 1 -> String.format("%.2f", price)
        price >= 0.01 -> String.format("%.4f", price)
        else -> String.format("%.8f", price)
    }
}
