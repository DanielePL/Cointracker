package com.cointracker.pro.ui.screens

import android.app.Application
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cointracker.pro.data.analysis.SignalGenerator
import com.cointracker.pro.data.api.FearGreedApi
import com.cointracker.pro.data.binance.BinanceRepository
import com.cointracker.pro.data.models.FearGreedIndex
import com.cointracker.pro.data.models.TradingSignal
import com.cointracker.pro.data.supabase.MLSignalDisplay
import com.cointracker.pro.data.supabase.SignalColorType
import com.cointracker.pro.data.supabase.SignalFilter
import com.cointracker.pro.ui.components.GlassCard
import com.cointracker.pro.ui.components.GradientBackground
import com.cointracker.pro.ui.theme.*
import com.cointracker.pro.viewmodel.MLSignalsUiState
import com.cointracker.pro.viewmodel.MLSignalsViewModel
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

    // Tab state: 0 = Local Analysis, 1 = ML Analysis
    var selectedTab by remember { mutableStateOf(0) }

    // Local signals state
    var signals by remember { mutableStateOf<List<TradingSignal>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fearGreed by remember { mutableStateOf<FearGreedIndex?>(null) }
    var lastUpdate by remember { mutableStateOf<String?>(null) }

    // ML ViewModel
    val mlViewModel: MLSignalsViewModel = viewModel()
    val mlUiState by mlViewModel.uiState.collectAsState()

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

            // Tab Row
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = ElectricBlue
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "Local Analysis",
                            color = if (selectedTab == 0) ElectricBlue else TextMuted
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (mlUiState.isLive) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(BullishGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                "ML Analysis",
                                color = if (selectedTab == 1) ElectricBlue else TextMuted
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content based on selected tab
            when (selectedTab) {
                0 -> LocalSignalsContent(
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    signals = signals,
                    onRetry = { loadSignals() }
                )
                1 -> MLSignalsContent(
                    uiState = mlUiState,
                    onFilterChange = { mlViewModel.setFilter(it) },
                    onRefresh = { mlViewModel.refresh() }
                )
            }
        }
    }
}

@Composable
private fun LocalSignalsContent(
    isLoading: Boolean,
    errorMessage: String?,
    signals: List<TradingSignal>,
    onRetry: () -> Unit
) {
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
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BearishRed
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRetry,
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

// ==================== ML SIGNALS CONTENT ====================

@Composable
private fun MLSignalsContent(
    uiState: MLSignalsUiState,
    onFilterChange: (SignalFilter) -> Unit,
    onRefresh: () -> Unit
) {
    Column {
        // Filter Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            items(SignalFilter.entries.toList()) { filter ->
                FilterChip(
                    selected = uiState.selectedFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = {
                        Text(
                            text = filter.displayName,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricBlue.copy(alpha = 0.2f),
                        selectedLabelColor = ElectricBlue
                    )
                )
            }
        }

        // Status Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (uiState.isLive) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(BullishGreen)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Live",
                        style = MaterialTheme.typography.labelMedium,
                        color = BullishGreen
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = "${uiState.totalAnalyzed} Coins analysiert",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMuted
                )
            }
            uiState.lastUpdate?.let {
                Text(
                    text = "Updated: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }

        // Content
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ElectricBlue)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Lade ML Signale...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = BearishRed
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onRefresh,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Text("Erneut versuchen")
                        }
                    }
                }
            }

            uiState.signals.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Keine ML Signale verfügbar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }

            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.signals, key = { it.analysisLog.coin }) { signal ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically()
                        ) {
                            MLSignalCard(signal)
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

@Composable
private fun MLSignalCard(signal: MLSignalDisplay) {
    val log = signal.analysisLog
    val signalColor = when (signal.signalColor) {
        SignalColorType.BULLISH -> BullishGreen
        SignalColorType.BEARISH -> BearishRed
        SignalColorType.NEUTRAL -> NeutralYellow
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = log.coin,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = signal.formattedPrice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = signal.formattedChange,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (log.priceChange24h ?: 0.0 >= 0) BullishGreen else BearishRed
                        )
                    }
                }

                // Score Ring
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(60.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { log.mlScoreInt / 100f },
                        modifier = Modifier.fillMaxSize(),
                        color = signalColor,
                        trackColor = GlassWhite,
                        strokeWidth = 5.dp
                    )
                    Text(
                        text = "${log.mlScoreInt}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = signalColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Signal Badge
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = signalColor.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = log.mlSignal.replace("_", " "),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = signalColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                log.mlConfidence?.let { conf ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${(conf * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }

            // Top Reasons
            if (log.topReasons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = GlassBorder)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Warum dieses Signal?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentOrange
                )
                Spacer(modifier = Modifier.height(6.dp))

                log.topReasons.take(3).forEachIndexed { index, reason ->
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = ElectricBlue,
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Technical Indicators
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = GlassBorder)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                log.rsi?.let { rsi ->
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

                log.macd?.let { macd ->
                    IndicatorChip(
                        label = "MACD",
                        value = if (macd >= 0) "+" else "-",
                        color = if (macd >= 0) BullishGreen else BearishRed
                    )
                }

                // BB Position based on price vs bands
                if (log.bbUpper != null && log.bbLower != null) {
                    val bbPos = when {
                        log.price <= log.bbLower -> "▼▼"
                        log.price >= log.bbUpper -> "▲▲"
                        log.price < (log.bbLower + log.bbUpper) / 2 -> "▼"
                        else -> "▲"
                    }
                    val bbColor = when {
                        log.price <= log.bbLower -> BullishGreen
                        log.price >= log.bbUpper -> BearishRed
                        else -> TextMuted
                    }
                    IndicatorChip(label = "BB", value = bbPos, color = bbColor)
                }

                // Tech vs ML comparison
                log.techSignal?.let { tech ->
                    val techColor = when (tech) {
                        "STRONG_BUY", "BUY" -> BullishGreen
                        "STRONG_SELL", "SELL" -> BearishRed
                        else -> NeutralYellow
                    }
                    IndicatorChip(
                        label = "Tech",
                        value = when (tech) {
                            "STRONG_BUY" -> "↑↑"
                            "BUY" -> "↑"
                            "STRONG_SELL" -> "↓↓"
                            "SELL" -> "↓"
                            else -> "→"
                        },
                        color = techColor
                    )
                }
            }
        }
    }
}