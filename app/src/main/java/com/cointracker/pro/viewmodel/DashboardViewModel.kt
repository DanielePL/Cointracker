package com.cointracker.pro.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cointracker.pro.data.api.FearGreedApi
import com.cointracker.pro.data.binance.Binance24hrTicker
import com.cointracker.pro.data.binance.BinanceKline
import com.cointracker.pro.data.binance.BinanceRepository
import com.cointracker.pro.data.binance.BinanceWebSocket
import com.cointracker.pro.data.binance.CoinNames
import com.cointracker.pro.data.binance.TickerUpdate
import com.cointracker.pro.data.models.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val isLive: Boolean = false,
    val ticker: Ticker? = null,
    val ohlcv: List<OHLCV> = emptyList(),
    val indicators: TechnicalIndicators? = null,
    val fearGreed: FearGreedIndex? = null,
    val signal: TradingSignal? = null,
    val onChainMetrics: OnChainMetrics? = null,
    val whaleTransactions: List<WhaleTransaction> = emptyList(),
    val aggregatedSentiment: AggregatedSentiment? = null,
    val topGainers: List<MarketCoin> = emptyList(),
    val topLosers: List<MarketCoin> = emptyList(),
    val error: String? = null
)

data class MarketCoin(
    val symbol: String,
    val name: String,
    val price: Double,
    val change24h: Double,
    val volume: Double
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val binanceRepository = BinanceRepository(application)
    private val webSocket = BinanceWebSocket.getInstance()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _selectedSymbol = MutableStateFlow("BTC/USDT")
    val selectedSymbol: StateFlow<String> = _selectedSymbol.asStateFlow()

    private val _selectedTimeframe = MutableStateFlow("1h")
    val selectedTimeframe: StateFlow<String> = _selectedTimeframe.asStateFlow()

    // Keep track of all symbols for WebSocket
    private val watchedSymbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT")

    init {
        loadDashboard()
        connectWebSocket()
        observeWebSocket()
    }

    private fun currentTimestamp(): String = dateFormat.format(Date())

    private fun connectWebSocket() {
        viewModelScope.launch {
            try {
                webSocket.connect(watchedSymbols)
                Log.d("DashboardVM", "WebSocket connecting to ${watchedSymbols.joinToString()}")
            } catch (e: Exception) {
                Log.e("DashboardVM", "WebSocket connect error", e)
            }
        }
    }

    private fun observeWebSocket() {
        // Observe connection state
        viewModelScope.launch {
            webSocket.connectionState.collect { connected ->
                _uiState.value = _uiState.value.copy(isLive = connected)
                Log.d("DashboardVM", "WebSocket connected: $connected")
            }
        }

        // Observe ticker updates
        viewModelScope.launch {
            webSocket.tickerUpdates.collect { update ->
                handleTickerUpdate(update)
            }
        }
    }

    private fun handleTickerUpdate(update: TickerUpdate) {
        val currentSymbol = _selectedSymbol.value.replace("/", "")

        // Update main ticker if it matches selected symbol
        if (update.symbol == currentSymbol) {
            val currentTicker = _uiState.value.ticker
            if (currentTicker != null) {
                _uiState.value = _uiState.value.copy(
                    ticker = currentTicker.copy(
                        price = update.price,
                        change24h = update.priceChange,
                        changePercent24h = update.priceChangePercent,
                        high = update.high,
                        low = update.low,
                        volume = update.volume,
                        timestamp = currentTimestamp()
                    )
                )
            }
        }

        // Update top gainers/losers if they contain this symbol
        val updatedGainers = _uiState.value.topGainers.map { coin ->
            if (coin.symbol + "USDT" == update.symbol) {
                coin.copy(
                    price = update.price,
                    change24h = update.priceChangePercent
                )
            } else coin
        }

        val updatedLosers = _uiState.value.topLosers.map { coin ->
            if (coin.symbol + "USDT" == update.symbol) {
                coin.copy(
                    price = update.price,
                    change24h = update.priceChangePercent
                )
            } else coin
        }

        if (updatedGainers != _uiState.value.topGainers || updatedLosers != _uiState.value.topLosers) {
            _uiState.value = _uiState.value.copy(
                topGainers = updatedGainers,
                topLosers = updatedLosers
            )
        }
    }

    fun loadDashboard() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val symbol = _selectedSymbol.value
            val binanceSymbol = symbol.replace("/", "") // BTC/USDT -> BTCUSDT

            // Test connection first
            binanceRepository.testConnection().onSuccess {
                _uiState.value = _uiState.value.copy(isConnected = true)
            }.onFailure {
                _uiState.value = _uiState.value.copy(isConnected = false)
            }

            // Load ticker data
            launch {
                Log.d("DashboardVM", "Loading tickers for $binanceSymbol...")
                binanceRepository.getAll24hrTickers().onSuccess { tickers ->
                    Log.d("DashboardVM", "Loaded ${tickers.size} tickers from Binance")
                    val tickerMap = tickers.associateBy { it.symbol }

                    // Get current symbol ticker
                    val currentTicker = tickerMap[binanceSymbol]
                    if (currentTicker != null) {
                        Log.d("DashboardVM", "$binanceSymbol: $${currentTicker.lastPriceDouble} (${currentTicker.priceChangePercentDouble}%)")
                        _uiState.value = _uiState.value.copy(
                            ticker = Ticker(
                                symbol = symbol,
                                price = currentTicker.lastPriceDouble,
                                change24h = currentTicker.priceChange.toDoubleOrNull() ?: 0.0,
                                changePercent24h = currentTicker.priceChangePercentDouble,
                                high = currentTicker.highPriceDouble,
                                low = currentTicker.lowPriceDouble,
                                volume = currentTicker.volumeDouble,
                                timestamp = currentTimestamp()
                            )
                        )
                    } else {
                        Log.w("DashboardVM", "Ticker $binanceSymbol not found in ${tickers.size} tickers")
                    }

                    // Get top gainers
                    val gainers = tickers
                        .filter { it.symbol.endsWith("USDT") && it.volumeDouble > 1_000_000 }
                        .sortedByDescending { it.priceChangePercentDouble }
                        .take(5)
                        .map { it.toMarketCoin() }

                    // Get top losers
                    val losers = tickers
                        .filter { it.symbol.endsWith("USDT") && it.volumeDouble > 1_000_000 }
                        .sortedBy { it.priceChangePercentDouble }
                        .take(5)
                        .map { it.toMarketCoin() }

                    Log.d("DashboardVM", "Top gainers: ${gainers.map { "${it.symbol}: ${it.change24h}%" }}")
                    Log.d("DashboardVM", "Top losers: ${losers.map { "${it.symbol}: ${it.change24h}%" }}")

                    _uiState.value = _uiState.value.copy(
                        topGainers = gainers,
                        topLosers = losers
                    )

                    // Subscribe to top gainers/losers via WebSocket
                    val symbolsToWatch = (gainers + losers).map { it.symbol + "USDT" }.distinct()
                    webSocket.connect(watchedSymbols + symbolsToWatch)

                }.onFailure { e ->
                    Log.e("DashboardVM", "Failed to load tickers: ${e.message}", e)
                    _uiState.value = _uiState.value.copy(error = "Binance API Error: ${e.message}")
                }
            }

            // Load kline/OHLCV data for chart
            launch {
                val timeframe = _selectedTimeframe.value
                val limit = when (timeframe) {
                    "1h" -> 100
                    "4h" -> 100
                    "1d" -> 60
                    "1w" -> 52
                    else -> 100
                }
                Log.d("DashboardVM", "Loading klines: $binanceSymbol, timeframe: $timeframe, limit: $limit")
                binanceRepository.getKlines(binanceSymbol, timeframe, limit).onSuccess { klines ->
                    val ohlcvList = klines.map { kline ->
                        OHLCV(
                            timestamp = dateFormat.format(Date(kline.openTime)),
                            open = kline.open,
                            high = kline.high,
                            low = kline.low,
                            close = kline.close,
                            volume = kline.volume
                        )
                    }
                    _uiState.value = _uiState.value.copy(ohlcv = ohlcvList)

                    // Calculate simple indicators from klines
                    val indicators = calculateIndicators(klines, symbol)
                    _uiState.value = _uiState.value.copy(indicators = indicators)
                }.onFailure { e ->
                    Log.e("DashboardVM", "Failed to load klines", e)
                }
            }

            // Load Fear & Greed from alternative.me API
            launch {
                try {
                    val response = FearGreedApi.service.getFearGreedIndex()
                    val fgData = response.data.firstOrNull()
                    if (fgData != null) {
                        _uiState.value = _uiState.value.copy(
                            fearGreed = FearGreedIndex(
                                value = fgData.value.toIntOrNull() ?: 50,
                                classification = fgData.classification,
                                timestamp = currentTimestamp(),
                                timeUntilUpdate = fgData.timeUntilUpdate
                            )
                        )
                        Log.d("DashboardVM", "Fear & Greed loaded: ${fgData.value} - ${fgData.classification}")
                    }
                } catch (e: Exception) {
                    Log.e("DashboardVM", "Failed to load Fear & Greed", e)
                    // Fallback to neutral if API fails
                    _uiState.value = _uiState.value.copy(
                        fearGreed = FearGreedIndex(
                            value = 50,
                            classification = "Neutral",
                            timestamp = currentTimestamp(),
                            timeUntilUpdate = null
                        )
                    )
                }
            }

            delay(500)
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    private fun calculateIndicators(klines: List<BinanceKline>, symbol: String): TechnicalIndicators {
        val defaultIndicators = TechnicalIndicators(
            symbol = symbol,
            timeframe = "1h",
            timestamp = currentTimestamp(),
            rsi = null,
            rsiSignal = null,
            macdLine = null,
            macdSignal = null,
            macdHistogram = null,
            macdTrend = null,
            bbUpper = null,
            bbMiddle = null,
            bbLower = null,
            bbPosition = null,
            ema50 = null,
            ema200 = null,
            emaTrend = null,
            volumeSma = null,
            volumeRatio = null
        )

        if (klines.size < 14) {
            return defaultIndicators
        }

        val closes = klines.map { it.close }

        // Calculate RSI (14 period)
        val rsi = calculateRSI(closes, 14)
        val rsiSignal = when {
            rsi < 30 -> "oversold"
            rsi > 70 -> "overbought"
            else -> "neutral"
        }

        // Calculate simple EMA trend
        val ema50 = calculateEMA(closes, 50)
        val ema200 = calculateEMA(closes, 200)

        val emaTrend = when {
            ema50 != null && ema200 != null && ema50 > ema200 -> "bullish"
            ema50 != null && ema200 != null && ema50 < ema200 -> "bearish"
            else -> "neutral"
        }

        // Simple MACD calculation
        val ema12 = calculateEMA(closes, 12)
        val ema26 = calculateEMA(closes, 26)
        val macdLine = if (ema12 != null && ema26 != null) ema12 - ema26 else null
        val macdTrend = when {
            macdLine != null && macdLine > 0 -> "bullish"
            macdLine != null && macdLine < 0 -> "bearish"
            else -> "neutral"
        }

        return TechnicalIndicators(
            symbol = symbol,
            timeframe = "1h",
            timestamp = currentTimestamp(),
            rsi = rsi,
            rsiSignal = rsiSignal,
            macdLine = macdLine,
            macdSignal = null,
            macdHistogram = null,
            macdTrend = macdTrend,
            bbUpper = null,
            bbMiddle = null,
            bbLower = null,
            bbPosition = null,
            ema50 = ema50,
            ema200 = ema200,
            emaTrend = emaTrend,
            volumeSma = null,
            volumeRatio = null
        )
    }

    private fun calculateRSI(closes: List<Double>, period: Int): Double {
        if (closes.size < period + 1) return 50.0

        var avgGain = 0.0
        var avgLoss = 0.0

        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) avgGain += change
            else avgLoss += -change
        }
        avgGain /= period
        avgLoss /= period

        for (i in (period + 1) until closes.size) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period
                avgLoss = (avgLoss * (period - 1)) / period
            } else {
                avgGain = (avgGain * (period - 1)) / period
                avgLoss = (avgLoss * (period - 1) + (-change)) / period
            }
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    private fun calculateEMA(closes: List<Double>, period: Int): Double? {
        if (closes.size < period) return null

        val multiplier = 2.0 / (period + 1)
        var ema = closes.take(period).average()

        for (i in period until closes.size) {
            ema = (closes[i] - ema) * multiplier + ema
        }
        return ema
    }

    fun selectSymbol(symbol: String) {
        _selectedSymbol.value = symbol
        loadDashboard()
    }

    fun selectTimeframe(timeframe: String) {
        _selectedTimeframe.value = timeframe
        // Reload only chart data
        viewModelScope.launch {
            val binanceSymbol = _selectedSymbol.value.replace("/", "")
            val limit = when (timeframe) {
                "1h" -> 100
                "4h" -> 100
                "1d" -> 60
                "1w" -> 52
                else -> 100
            }
            Log.d("DashboardVM", "Switching timeframe to $timeframe")
            binanceRepository.getKlines(binanceSymbol, timeframe, limit).onSuccess { klines ->
                val ohlcvList = klines.map { kline ->
                    OHLCV(
                        timestamp = dateFormat.format(Date(kline.openTime)),
                        open = kline.open,
                        high = kline.high,
                        low = kline.low,
                        close = kline.close,
                        volume = kline.volume
                    )
                }
                _uiState.value = _uiState.value.copy(ohlcv = ohlcvList)

                // Recalculate indicators
                val indicators = calculateIndicators(klines, _selectedSymbol.value)
                _uiState.value = _uiState.value.copy(indicators = indicators)
                Log.d("DashboardVM", "Loaded ${ohlcvList.size} candles for $timeframe")
            }.onFailure { e ->
                Log.e("DashboardVM", "Failed to load klines for $timeframe", e)
            }
        }
    }

    fun refresh() {
        loadDashboard()
    }

    override fun onCleared() {
        super.onCleared()
        webSocket.disconnect()
    }
}

private fun Binance24hrTicker.toMarketCoin(): MarketCoin {
    val baseSymbol = symbol.removeSuffix("USDT")
    return MarketCoin(
        symbol = baseSymbol,
        name = CoinNames.getName(baseSymbol),
        price = lastPriceDouble,
        change24h = priceChangePercentDouble,
        volume = volumeDouble
    )
}
