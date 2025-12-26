package com.cointracker.pro.data.trading

import android.content.Context
import android.util.Log
import com.cointracker.pro.data.analysis.SignalGenerator
import com.cointracker.pro.data.api.FearGreedApi
import com.cointracker.pro.data.binance.BinanceRepository
import com.cointracker.pro.data.models.FearGreedIndex
import com.cointracker.pro.data.models.TradingSignal
import com.cointracker.pro.data.repository.PaperTradingRepository
import com.cointracker.pro.data.supabase.SupabaseAuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Auto Trading Service
 * Automatically executes paper trades based on strong signals
 */
class AutoTradingService private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AutoTrading"

        // Trading parameters
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val MIN_SIGNAL_SCORE = 70 // Minimum score to trade
        private const val TRADE_PERCENTAGE = 0.1 // 10% of balance per trade
        private const val MAX_POSITIONS = 3 // Maximum concurrent positions

        @Volatile
        private var instance: AutoTradingService? = null

        fun getInstance(context: Context): AutoTradingService {
            return instance ?: synchronized(this) {
                instance ?: AutoTradingService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tradingJob: Job? = null

    private val binanceRepository = BinanceRepository(context)
    private val paperTradingRepository = PaperTradingRepository()
    private val signalGenerator = SignalGenerator()
    private val authRepository = SupabaseAuthRepository()

    // State
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastAction = MutableStateFlow<AutoTradeAction?>(null)
    val lastAction: StateFlow<AutoTradeAction?> = _lastAction.asStateFlow()

    private val _tradeLog = MutableStateFlow<List<AutoTradeLog>>(emptyList())
    val tradeLog: StateFlow<List<AutoTradeLog>> = _tradeLog.asStateFlow()

    private val symbols = listOf(
        "BTCUSDT" to "BTC/USDT",
        "ETHUSDT" to "ETH/USDT",
        "SOLUSDT" to "SOL/USDT",
        "XRPUSDT" to "XRP/USDT",
        "ADAUSDT" to "ADA/USDT"
    )

    /**
     * Start auto trading
     */
    fun start() {
        if (_isRunning.value) {
            Log.d(TAG, "Auto trading already running")
            return
        }

        val userId = authRepository.getCurrentUser()?.id
        if (userId == null) {
            Log.e(TAG, "Cannot start auto trading - user not logged in")
            addLog("ERROR", "Nicht eingeloggt - Auto Trading nicht möglich")
            return
        }

        _isRunning.value = true
        addLog("START", "Auto Trading gestartet")

        tradingJob = scope.launch {
            Log.d(TAG, "Auto trading started for user: $userId")

            while (isActive && _isRunning.value) {
                try {
                    runTradingCycle(userId)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in trading cycle", e)
                    addLog("ERROR", "Fehler: ${e.message}")
                }

                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop auto trading
     */
    fun stop() {
        _isRunning.value = false
        tradingJob?.cancel()
        tradingJob = null
        addLog("STOP", "Auto Trading gestoppt")
        Log.d(TAG, "Auto trading stopped")
    }

    /**
     * Run a single trading cycle
     */
    private suspend fun runTradingCycle(userId: String) {
        Log.d(TAG, "Running trading cycle...")
        _lastAction.value = AutoTradeAction.Analyzing

        // 1. Check positions for stop-loss/take-profit
        checkPositions(userId)

        // 2. Load Fear & Greed Index
        val fearGreed = loadFearGreed()

        // 3. Generate signals for all symbols
        val signals = generateSignals(fearGreed)

        // 4. Filter for strong signals
        val strongSignals = signals.filter { signal ->
            (signal.signal == "STRONG_BUY" || signal.signal == "STRONG_SELL") &&
            signal.signalScore >= MIN_SIGNAL_SCORE
        }

        if (strongSignals.isEmpty()) {
            Log.d(TAG, "No strong signals found")
            _lastAction.value = AutoTradeAction.Waiting("Keine starken Signale")
            return
        }

        // 5. Get current balance and positions
        val balance = paperTradingRepository.getOrCreateBalance(userId).getOrNull()
        if (balance == null) {
            Log.e(TAG, "Could not get balance")
            return
        }

        val holdings = paperTradingRepository.getHoldings(userId).getOrDefault(emptyList())
        val currentPositions = holdings.size

        // 6. Execute trades based on signals
        for (signal in strongSignals) {
            try {
                executeTrade(userId, signal, balance.balanceUsdt, holdings, currentPositions)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing trade for ${signal.symbol}", e)
                addLog("ERROR", "Trade-Fehler ${signal.symbol}: ${e.message}")
            }
        }

        _lastAction.value = AutoTradeAction.Waiting("Nächster Check in 5 Min")
    }

    /**
     * Check open positions for stop-loss and take-profit
     */
    private suspend fun checkPositions(userId: String) {
        val holdings = paperTradingRepository.getHoldings(userId).getOrDefault(emptyList())

        // Get all tickers once
        val tickers = binanceRepository.getAll24hrTickers().getOrDefault(emptyList())
            .associateBy { it.symbol }

        for (holding in holdings) {
            try {
                // Get current price
                val binanceSymbol = holding.symbol.replace("/", "")
                val ticker = tickers[binanceSymbol]

                if (ticker != null) {
                    val currentPrice = ticker.lastPrice.toDoubleOrNull() ?: continue
                    val entryPrice = holding.avgEntryPrice
                    val pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100

                    // Check stop-loss (-5%)
                    if (pnlPercent <= -5.0) {
                        Log.d(TAG, "Stop-loss triggered for ${holding.symbol} at $pnlPercent%")
                        executeSell(userId, holding.symbol, holding.quantity, currentPrice, "STOP_LOSS")
                    }
                    // Check take-profit (+10%)
                    else if (pnlPercent >= 10.0) {
                        Log.d(TAG, "Take-profit triggered for ${holding.symbol} at $pnlPercent%")
                        executeSell(userId, holding.symbol, holding.quantity, currentPrice, "TAKE_PROFIT")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking position ${holding.symbol}", e)
            }
        }
    }

    /**
     * Load Fear & Greed Index
     */
    private suspend fun loadFearGreed(): FearGreedIndex? {
        return try {
            val response = FearGreedApi.service.getFearGreedIndex()
            val data = response.data.firstOrNull()
            if (data != null) {
                FearGreedIndex(
                    value = data.value.toIntOrNull() ?: 50,
                    classification = data.classification,
                    timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
                    timeUntilUpdate = data.timeUntilUpdate
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Fear & Greed", e)
            FearGreedIndex(50, "Neutral", "", null)
        }
    }

    /**
     * Generate signals for all tracked symbols
     */
    private suspend fun generateSignals(fearGreed: FearGreedIndex?): List<TradingSignal> {
        return coroutineScope {
            symbols.map { (binanceSymbol, displaySymbol) ->
                async {
                    try {
                        binanceRepository.getKlines(binanceSymbol, "1h", 250)
                            .map { klines ->
                                signalGenerator.generateSignal(
                                    symbol = displaySymbol,
                                    klines = klines,
                                    fearGreed = fearGreed
                                )
                            }
                            .getOrNull()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating signal for $binanceSymbol", e)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Execute a trade based on signal
     */
    private suspend fun executeTrade(
        userId: String,
        signal: TradingSignal,
        availableBalance: Double,
        holdings: List<com.cointracker.pro.data.supabase.PaperHolding>,
        currentPositions: Int
    ) {
        val symbol = signal.symbol
        val currentHolding = holdings.find { it.symbol == symbol }

        when (signal.signal) {
            "STRONG_BUY" -> {
                // Only buy if we don't already have a position and haven't reached max
                if (currentHolding == null && currentPositions < MAX_POSITIONS) {
                    val price = signal.entryPrice ?: return
                    val tradeAmount = availableBalance * TRADE_PERCENTAGE
                    val quantity = tradeAmount / price

                    if (tradeAmount < 10) {
                        Log.d(TAG, "Trade amount too small: $tradeAmount")
                        return
                    }

                    Log.d(TAG, "Executing BUY: $quantity $symbol @ $price")
                    _lastAction.value = AutoTradeAction.Buying(symbol, quantity, price)

                    paperTradingRepository.executeBuy(userId, symbol, quantity, price)
                        .onSuccess {
                            addLog("BUY", "$symbol: ${String.format("%.6f", quantity)} @ $${String.format("%.2f", price)} (Score: ${signal.signalScore})")
                        }
                        .onFailure { e ->
                            addLog("ERROR", "BUY $symbol fehlgeschlagen: ${e.message}")
                        }
                }
            }

            "STRONG_SELL" -> {
                // Only sell if we have a position
                if (currentHolding != null && currentHolding.quantity > 0) {
                    val binanceSymbol = symbol.replace("/", "")
                    val tickers = binanceRepository.getAll24hrTickers().getOrDefault(emptyList())
                    val ticker = tickers.find { it.symbol == binanceSymbol }
                    val price = ticker?.lastPrice?.toDoubleOrNull() ?: signal.entryPrice ?: return

                    Log.d(TAG, "Executing SELL: ${currentHolding.quantity} $symbol @ $price")
                    _lastAction.value = AutoTradeAction.Selling(symbol, currentHolding.quantity, price)

                    executeSell(userId, symbol, currentHolding.quantity, price, "SIGNAL")
                }
            }
        }
    }

    /**
     * Execute a sell order
     */
    private suspend fun executeSell(
        userId: String,
        symbol: String,
        quantity: Double,
        price: Double,
        reason: String
    ) {
        paperTradingRepository.executeSell(userId, symbol, quantity, price)
            .onSuccess { trade ->
                val pnl = trade.pnl ?: 0.0
                val pnlStr = if (pnl >= 0) "+$${String.format("%.2f", pnl)}" else "-$${String.format("%.2f", -pnl)}"
                addLog("SELL", "$symbol: ${String.format("%.6f", quantity)} @ $${String.format("%.2f", price)} ($reason, P&L: $pnlStr)")
            }
            .onFailure { e ->
                addLog("ERROR", "SELL $symbol fehlgeschlagen: ${e.message}")
            }
    }

    /**
     * Add entry to trade log
     */
    private fun addLog(type: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val log = AutoTradeLog(timestamp, type, message)
        _tradeLog.value = listOf(log) + _tradeLog.value.take(49) // Keep last 50 entries
    }
}

/**
 * Current auto trade action
 */
sealed class AutoTradeAction {
    object Analyzing : AutoTradeAction()
    data class Buying(val symbol: String, val quantity: Double, val price: Double) : AutoTradeAction()
    data class Selling(val symbol: String, val quantity: Double, val price: Double) : AutoTradeAction()
    data class Waiting(val message: String) : AutoTradeAction()
}

/**
 * Trade log entry
 */
data class AutoTradeLog(
    val timestamp: String,
    val type: String,
    val message: String
)
