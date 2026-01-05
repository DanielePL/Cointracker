package com.cointracker.pro.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cointracker.pro.data.supabase.BotPosition
import com.cointracker.pro.data.supabase.BotTrade
import com.cointracker.pro.data.supabase.BullrunCoin
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.theme.*
import com.cointracker.pro.ui.viewmodel.BotViewModel
import com.cointracker.pro.viewmodel.BullrunViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen() {
    val botViewModel: BotViewModel = viewModel()
    val botState by botViewModel.uiState.collectAsState()

    val bullrunViewModel: BullrunViewModel = viewModel()
    val bullrunState by bullrunViewModel.uiState.collectAsState()

    // Pulsing animation for live indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
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
                        Text(
                            text = "CoinTracker",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (botState.settings.isActive) BullishGreen.copy(alpha = pulseAlpha)
                                        else BearishRed
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (botState.settings.isActive) "Bot Active" else "Bot Inactive",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (botState.settings.isActive) BullishGreen else TextMuted
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = {
                            botViewModel.refresh()
                            bullrunViewModel.refresh()
                        }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = ElectricBlue
                            )
                        }
                    }
                }
            }

            // Bot Balance Card
            if (!botState.isLoading) {
                item {
                    BotBalanceCard(
                        balance = botState.balance.balanceUsdt,
                        totalPnl = botState.balance.totalPnl,
                        totalPnlPercent = botState.balance.totalPnlPercent,
                        unrealizedPnl = botState.unrealizedPnl,
                        totalInPositions = botState.positions.sumOf { it.totalInvested },
                        winRate = botState.balance.winRate,
                        totalTrades = botState.balance.totalTrades
                    )
                }
            }

            // Bullrun Scanner Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Rocket,
                            contentDescription = null,
                            tint = AccentOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Bullrun Scanner",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    if (bullrunState.marketSummary != null) {
                        val sentiment = bullrunState.marketSummary!!.marketSentiment
                        Text(
                            text = sentiment,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (sentiment) {
                                "BULLISH" -> BullishGreen
                                "BEARISH" -> BearishRed
                                else -> AccentOrange
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when (sentiment) {
                                        "BULLISH" -> BullishGreen.copy(alpha = 0.15f)
                                        "BEARISH" -> BearishRed.copy(alpha = 0.15f)
                                        else -> AccentOrange.copy(alpha = 0.15f)
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Bullrun Coins List
            if (bullrunState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(24.dp))
                    }
                }
            } else if (bullrunState.coins.isNotEmpty()) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(bullrunState.coins) { coin ->
                            BullrunCoinCard(coin = coin)
                        }
                    }
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No coins in bullrun detected",
                            color = TextMuted
                        )
                    }
                }
            }

            // Active Positions Section
            if (botState.positions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccountBalance,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Active Positions (${botState.positions.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
                items(botState.positions) { position ->
                    ActivePositionCard(position = position)
                }
            }

            // Trade History Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = CyanGlow,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Trade History",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            if (botState.trades.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No trades yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else {
                items(botState.trades.take(10)) { trade ->
                    TradeHistoryCard(trade = trade)
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun BotBalanceCard(
    balance: Double,
    totalPnl: Double,
    totalPnlPercent: Double,
    unrealizedPnl: Double,
    totalInPositions: Double,
    winRate: Double,
    totalTrades: Int
) {
    val isProfitable = totalPnl >= 0
    val isUnrealizedProfitable = unrealizedPnl >= 0
    val unrealizedPnlPercent = if (totalInPositions > 0) (unrealizedPnl / totalInPositions) * 100 else 0.0

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bot Balance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Balance
            Text(
                text = "$${String.format("%,.2f", balance)}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Realized PnL
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isProfitable) BullishGreen.copy(alpha = 0.15f) else BearishRed.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    if (isProfitable) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null,
                    tint = if (isProfitable) BullishGreen else BearishRed,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Realized: ${if (isProfitable) "+" else ""}$${String.format("%.2f", totalPnl)} (${if (isProfitable) "+" else ""}${String.format("%.1f", totalPnlPercent)}%)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isProfitable) BullishGreen else BearishRed
                )
            }

            // Unrealized PnL (if positions open)
            if (totalInPositions > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isUnrealizedProfitable) BullishGreen.copy(alpha = 0.08f) else BearishRed.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Open: ${if (isUnrealizedProfitable) "+" else ""}$${String.format("%.2f", unrealizedPnl)} (${if (isUnrealizedProfitable) "+" else ""}${String.format("%.1f", unrealizedPnlPercent)}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isUnrealizedProfitable) BullishGreen else BearishRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = GlassWhite, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${String.format("%.0f", winRate)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (winRate >= 50) BullishGreen else BearishRed
                    )
                    Text(
                        text = "Win Rate",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$totalTrades",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricBlue
                    )
                    Text(
                        text = "Trades",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$${String.format("%.0f", totalInPositions)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange
                    )
                    Text(
                        text = "In Positions",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun BullrunCoinCard(coin: BullrunCoin) {
    val scoreColor = when {
        coin.bullrunScore >= 80 -> BullishGreen
        coin.bullrunScore >= 60 -> CyanGlow
        else -> AccentOrange
    }

    GlassCard(
        modifier = Modifier.width(160.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = coin.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(scoreColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${coin.bullrunScore}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor
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

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (coin.priceChange24h >= 0) BullishGreen.copy(alpha = 0.15f)
                        else BearishRed.copy(alpha = 0.15f)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(
                    if (coin.priceChange24h >= 0) Icons.AutoMirrored.Filled.TrendingUp
                    else Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null,
                    tint = if (coin.priceChange24h >= 0) BullishGreen else BearishRed,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${if (coin.priceChange24h >= 0) "+" else ""}${String.format("%.1f", coin.priceChange24h)}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (coin.priceChange24h >= 0) BullishGreen else BearishRed
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Top signals
            coin.signals.take(2).forEach { signal ->
                Text(
                    text = signal,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ActivePositionCard(position: BotPosition) {
    val isProfitable = position.calculatedUnrealizedPnl >= 0

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
                        .background(ElectricBlue.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = position.coin.take(2),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricBlue
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = position.coin,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = "Entry: $${formatPrice(position.entryPrice)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${String.format("%.2f", position.totalInvested)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isProfitable) BullishGreen.copy(alpha = 0.15f)
                            else BearishRed.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(
                        if (isProfitable) Icons.AutoMirrored.Filled.TrendingUp
                        else Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        tint = if (isProfitable) BullishGreen else BearishRed,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${if (isProfitable) "+" else ""}$${String.format("%.2f", position.calculatedUnrealizedPnl)} (${if (isProfitable) "+" else ""}${String.format("%.1f", position.calculatedUnrealizedPnlPercent)}%)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isProfitable) BullishGreen else BearishRed
                    )
                }
            }
        }
    }
}

@Composable
private fun TradeHistoryCard(trade: BotTrade) {
    val isBuy = trade.side == "BUY"
    val isProfitable = (trade.pnl ?: 0.0) > 0
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val tradeDate = try {
        trade.openedAt?.let {
            dateFormat.format(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(it) ?: Date())
        } ?: ""
    } catch (e: Exception) { "" }

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
                            text = trade.coin.replace("USDT", ""),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
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
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$${formatPrice(trade.totalValue)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                if (trade.pnl != null && trade.isClosed) {
                    Text(
                        text = "${if (isProfitable) "+" else ""}$${formatPrice(trade.pnl)} (${String.format("%+.1f", trade.pnlPercent ?: 0.0)}%)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isProfitable) BullishGreen else BearishRed
                    )
                }
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
