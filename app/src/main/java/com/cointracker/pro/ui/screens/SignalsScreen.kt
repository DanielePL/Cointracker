package com.cointracker.pro.ui.screens

import android.app.Application
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cointracker.pro.data.analysis.SignalGenerator
import com.cointracker.pro.data.api.FearGreedApi
import com.cointracker.pro.data.binance.BinanceRepository
import com.cointracker.pro.data.models.FearGreedIndex
import com.cointracker.pro.data.models.TradingSignal
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalsScreen() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val binanceRepository = remember { BinanceRepository(application) }
    val signalGenerator = remember { SignalGenerator() }

    var signals by remember { mutableStateOf<List<TradingSignal>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fearGreed by remember { mutableStateOf<FearGreedIndex?>(null) }
    var lastUpdate by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    val symbols = listOf(
        "BTCUSDT" to "BTC/USDT",
        "ETHUSDT" to "ETH/USDT",
        "SOLUSDT" to "SOL/USDT",
        "XRPUSDT" to "XRP/USDT",
        "ADAUSDT" to "ADA/USDT"
    )

    fun loadSignals() {
        scope.launch {
            isLoading = true
            errorMessage = null
            signals = emptyList()

            try {
                // Fear & Greed laden
                try {
                    val fgResponse = FearGreedApi.service.getFearGreedIndex()
                    val fgData = fgResponse.data.firstOrNull()
                    if (fgData != null) {
                        fearGreed = FearGreedIndex(
                            value = fgData.value.toIntOrNull() ?: 50,
                            classification = fgData.classification,
                            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
                            timeUntilUpdate = fgData.timeUntilUpdate
                        )
                        Log.d("SignalsScreen", "Fear & Greed: ${fearGreed?.value} - ${fearGreed?.classification}")
                    }
                } catch (e: Exception) {
                    Log.e("SignalsScreen", "Fear & Greed Error", e)
                    // Fallback
                    fearGreed = FearGreedIndex(50, "Neutral", "", null)
                }

                // Signale parallel für alle Symbole berechnen
                val deferredSignals = symbols.map { (binanceSymbol, displaySymbol) ->
                    async {
                        try {
                            // 250 Kerzen für akkurate Berechnung (brauchen 200 für EMA200)
                            binanceRepository.getKlines(binanceSymbol, "1h", 250)
                                .map { klines ->
                                    Log.d("SignalsScreen", "$binanceSymbol: ${klines.size} Kerzen geladen")
                                    signalGenerator.generateSignal(
                                        symbol = displaySymbol,
                                        klines = klines,
                                        fearGreed = fearGreed
                                    )
                                }
                                .getOrNull()
                        } catch (e: Exception) {
                            Log.e("SignalsScreen", "Error generating signal for $binanceSymbol", e)
                            null
                        }
                    }
                }

                val results = deferredSignals.awaitAll().filterNotNull()
                signals = results.sortedByDescending { it.signalScore }

                lastUpdate = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                Log.d("SignalsScreen", "Loaded ${signals.size} signals")

            } catch (e: Exception) {
                Log.e("SignalsScreen", "Error loading signals", e)
                errorMessage = "Fehler beim Laden: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadSignals()
    }

    GradientBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Trading Signals",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Echte technische Analyse",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    lastUpdate?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(
                        onClick = { loadSignals() },
                        enabled = !isLoading
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = if (isLoading) TextMuted else ElectricBlue
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Fear & Greed Anzeige
            fearGreed?.let { fg ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
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
                                        when {
                                            fg.value <= 25 -> BearishRed
                                            fg.value <= 45 -> NeutralYellow
                                            fg.value >= 75 -> BullishGreen
                                            fg.value >= 55 -> NeutralYellow
                                            else -> TextMuted
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Fear & Greed Index",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${fg.value}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    fg.value <= 25 -> BearishRed
                                    fg.value <= 45 -> NeutralYellow
                                    fg.value >= 75 -> BullishGreen
                                    fg.value >= 55 -> NeutralYellow
                                    else -> TextPrimary
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = fg.classification,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = ElectricBlue)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Berechne Signale...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "RSI • MACD • EMA • Bollinger Bands",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }

                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = BearishRed
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { loadSignals() },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                            ) {
                                Text("Erneut versuchen")
                            }
                        }
                    }
                }

                signals.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Keine Signale verfügbar",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(signals, key = { it.symbol }) { signal ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + slideInVertically()
                            ) {
                                SignalDetailCard(signal)
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalDetailCard(signal: TradingSignal) {
    val signalColor = when (signal.signal) {
        "BUY", "STRONG_BUY" -> BullishGreen
        "SELL", "STRONG_SELL" -> BearishRed
        else -> NeutralYellow
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = signal.symbol,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = signal.signal.replace("_", " "),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = signalColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "•",
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Score: ${signal.signalScore}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = signalColor
                        )
                    }
                }

                // Score Ring
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(60.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { signal.signalScore / 100f },
                        modifier = Modifier.fillMaxSize(),
                        color = signalColor,
                        trackColor = GlassWhite,
                        strokeWidth = 5.dp
                    )
                    Text(
                        text = "${signal.signalScore}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = signalColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = GlassBorder)
            Spacer(modifier = Modifier.height(16.dp))

            // WHY Section (Das Herzstück!)
            Text(
                text = "Warum dieses Signal?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AccentOrange
            )
            Spacer(modifier = Modifier.height(8.dp))

            signal.reasons.forEachIndexed { index, reason ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricBlue,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Suggested Action
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = signal.suggestedAction,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Trade Parameters
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                signal.entryPrice?.let { entry ->
                    Column {
                        Text(
                            text = "Entry",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = formatPrice(entry),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }

                signal.stopLoss?.let { sl ->
                    Column {
                        Text(
                            text = "Stop Loss",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = formatPrice(sl),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = BearishRed
                        )
                    }
                }

                signal.takeProfit?.let { tp ->
                    Column {
                        Text(
                            text = "Take Profit",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = formatPrice(tp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = BullishGreen
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Risk & Confidence
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    Text(
                        text = "Risk: ",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                    Text(
                        text = signal.riskLevel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when (signal.riskLevel) {
                            "LOW" -> BullishGreen
                            "HIGH", "VERY_HIGH" -> BearishRed
                            else -> NeutralYellow
                        }
                    )
                }
                Row {
                    Text(
                        text = "Confidence: ",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextMuted
                    )
                    Text(
                        text = "${(signal.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricBlue
                    )
                }
            }

            // Indicator Details (collapsible could be added)
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = GlassBorder)
            Spacer(modifier = Modifier.height(8.dp))

            // Mini Indicator Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                signal.indicators.rsi?.let { rsi ->
                    IndicatorChip(
                        label = "RSI",
                        value = String.format("%.0f", rsi),
                        color = when {
                            rsi < 30 -> BullishGreen
                            rsi > 70 -> BearishRed
                            else -> TextMuted
                        }
                    )
                }

                signal.indicators.emaTrend?.let { trend ->
                    IndicatorChip(
                        label = "Trend",
                        value = when (trend) {
                            "strong_bullish", "bullish" -> "↑"
                            "strong_bearish", "bearish" -> "↓"
                            else -> "→"
                        },
                        color = when (trend) {
                            "strong_bullish", "bullish" -> BullishGreen
                            "strong_bearish", "bearish" -> BearishRed
                            else -> TextMuted
                        }
                    )
                }

                signal.indicators.macdTrend?.let { macd ->
                    IndicatorChip(
                        label = "MACD",
                        value = when (macd) {
                            "bullish", "bullish_crossing" -> "+"
                            "bearish", "bearish_crossing" -> "-"
                            else -> "○"
                        },
                        color = when (macd) {
                            "bullish", "bullish_crossing" -> BullishGreen
                            "bearish", "bearish_crossing" -> BearishRed
                            else -> TextMuted
                        }
                    )
                }

                signal.indicators.bbPosition?.let { bb ->
                    IndicatorChip(
                        label = "BB",
                        value = when (bb) {
                            "below_lower" -> "▼▼"
                            "lower_half" -> "▼"
                            "above_upper" -> "▲▲"
                            "upper_half" -> "▲"
                            else -> "○"
                        },
                        color = when (bb) {
                            "below_lower" -> BullishGreen
                            "above_upper" -> BearishRed
                            else -> TextMuted
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun IndicatorChip(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

private fun formatPrice(price: Double): String {
    return when {
        price >= 1000 -> "$${String.format("%,.0f", price)}"
        price >= 1 -> "$${String.format("%.2f", price)}"
        else -> "$${String.format("%.4f", price)}"
    }
}