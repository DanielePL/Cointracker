package com.cointracker.pro.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cointracker.pro.data.binance.BinanceRepository
import com.cointracker.pro.data.binance.BinanceWebSocket
import com.cointracker.pro.data.repository.PaperPerformanceStats
import com.cointracker.pro.data.repository.PaperTradingRepository
import com.cointracker.pro.data.supabase.PaperBalance
import com.cointracker.pro.data.supabase.PaperHolding
import com.cointracker.pro.data.supabase.PaperHoldingWithPrice
import com.cointracker.pro.data.supabase.PaperTrade
import com.cointracker.pro.data.supabase.SupabaseAuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for Paper Trading functionality
 */
class PaperTradingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "PaperTradingVM"
    }

    private val paperTradingRepository = PaperTradingRepository()
    private val binanceRepository = BinanceRepository(application)
    private val authRepository = SupabaseAuthRepository()
    private val webSocket = BinanceWebSocket.getInstance()

    // UI State
    private val _uiState = MutableStateFlow(PaperTradingUiState())
    val uiState: StateFlow<PaperTradingUiState> = _uiState.asStateFlow()

    // Live prices cache
    private val priceCache = mutableMapOf<String, Double>()

    init {
        loadPaperPortfolio()
        observeTickerUpdates()
    }

    /**
     * Load paper trading portfolio data
     */
    fun loadPaperPortfolio() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val userId = authRepository.getCurrentUser()?.id
                if (userId == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Not logged in") }
                    return@launch
                }

                // Load balance
                val balanceResult = paperTradingRepository.getOrCreateBalance(userId)
                val balance = balanceResult.getOrNull()

                // Load holdings
                val holdingsResult = paperTradingRepository.getHoldings(userId)
                val holdings = holdingsResult.getOrDefault(emptyList())

                // Load recent trades
                val tradesResult = paperTradingRepository.getTradeHistory(userId, limit = 20)
                val trades = tradesResult.getOrDefault(emptyList())

                // Load performance stats
                val statsResult = paperTradingRepository.getPerformanceStats(userId)
                val stats = statsResult.getOrNull()

                // Get live prices for holdings
                val holdingsWithPrices = enrichHoldingsWithPrices(holdings)

                // Calculate total portfolio value
                val holdingsValue = holdingsWithPrices.sumOf { it.currentValue }
                val totalPortfolioValue = (balance?.balanceUsdt ?: 0.0) + holdingsValue

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        balance = balance,
                        holdings = holdingsWithPrices,
                        recentTrades = trades,
                        stats = stats,
                        totalPortfolioValue = totalPortfolioValue,
                        holdingsValue = holdingsValue
                    )
                }

                // Subscribe to WebSocket for holdings
                if (holdings.isNotEmpty()) {
                    val symbols = holdings.map { it.symbol }
                    webSocket.connect(symbols)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load paper portfolio", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Execute a paper BUY trade
     */
    fun executeBuy(symbol: String, amountUsdt: Double, currentPrice: Double) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTrading = true, tradeError = null) }

            try {
                val userId = authRepository.getCurrentUser()?.id
                    ?: throw IllegalStateException("Not logged in")

                val quantity = amountUsdt / currentPrice

                paperTradingRepository.executeBuy(
                    userId = userId,
                    symbol = symbol,
                    quantity = quantity,
                    price = currentPrice
                ).onSuccess { trade ->
                    Log.d(TAG, "Buy executed: ${trade.quantity} $symbol")
                    _uiState.update { it.copy(isTrading = false, lastTrade = trade) }
                    loadPaperPortfolio() // Reload data
                }.onFailure { error ->
                    _uiState.update { it.copy(isTrading = false, tradeError = error.message) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute buy", e)
                _uiState.update { it.copy(isTrading = false, tradeError = e.message) }
            }
        }
    }

    /**
     * Execute a paper SELL trade
     */
    fun executeSell(symbol: String, quantity: Double, currentPrice: Double) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTrading = true, tradeError = null) }

            try {
                val userId = authRepository.getCurrentUser()?.id
                    ?: throw IllegalStateException("Not logged in")

                paperTradingRepository.executeSell(
                    userId = userId,
                    symbol = symbol,
                    quantity = quantity,
                    price = currentPrice
                ).onSuccess { trade ->
                    Log.d(TAG, "Sell executed: ${trade.quantity} $symbol (P&L: ${trade.pnl})")
                    _uiState.update { it.copy(isTrading = false, lastTrade = trade) }
                    loadPaperPortfolio() // Reload data
                }.onFailure { error ->
                    _uiState.update { it.copy(isTrading = false, tradeError = error.message) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute sell", e)
                _uiState.update { it.copy(isTrading = false, tradeError = e.message) }
            }
        }
    }

    /**
     * Reset paper trading account to $10,000
     */
    fun resetAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val userId = authRepository.getCurrentUser()?.id
                    ?: throw IllegalStateException("Not logged in")

                paperTradingRepository.resetAccount(userId)
                    .onSuccess {
                        Log.d(TAG, "Account reset successful")
                        loadPaperPortfolio()
                    }
                    .onFailure { error ->
                        _uiState.update { it.copy(isLoading = false, error = error.message) }
                    }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset account", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Get current price for a symbol
     */
    fun getCurrentPrice(symbol: String): Double {
        return priceCache[symbol] ?: 0.0
    }

    /**
     * Get holding quantity for a symbol
     */
    fun getHoldingQuantity(symbol: String): Double {
        return _uiState.value.holdings.find { it.holding.symbol == symbol }?.holding?.quantity ?: 0.0
    }

    /**
     * Clear trade error
     */
    fun clearTradeError() {
        _uiState.update { it.copy(tradeError = null) }
    }

    /**
     * Enrich holdings with live price data
     */
    private suspend fun enrichHoldingsWithPrices(holdings: List<PaperHolding>): List<PaperHoldingWithPrice> {
        if (holdings.isEmpty()) return emptyList()

        // Get prices from Binance
        val pricesResult = binanceRepository.getAllPrices()
        val prices = pricesResult.getOrDefault(emptyMap())

        return holdings.map { holding ->
            val price = prices[holding.symbol] ?: priceCache[holding.symbol] ?: 0.0
            priceCache[holding.symbol] = price
            PaperHoldingWithPrice.fromHolding(holding, price)
        }
    }

    /**
     * Observe WebSocket ticker updates for real-time prices
     */
    private fun observeTickerUpdates() {
        viewModelScope.launch {
            webSocket.tickerUpdates.collect { update ->
                priceCache[update.symbol] = update.price

                // Update holdings with new prices
                _uiState.update { state ->
                    val updatedHoldings = state.holdings.map { hwp ->
                        if (hwp.holding.symbol == update.symbol) {
                            PaperHoldingWithPrice.fromHolding(hwp.holding, update.price)
                        } else {
                            hwp
                        }
                    }

                    val holdingsValue = updatedHoldings.sumOf { it.currentValue }
                    val totalValue = (state.balance?.balanceUsdt ?: 0.0) + holdingsValue

                    state.copy(
                        holdings = updatedHoldings,
                        holdingsValue = holdingsValue,
                        totalPortfolioValue = totalValue
                    )
                }
            }
        }
    }
}

/**
 * UI State for Paper Trading
 */
data class PaperTradingUiState(
    val isLoading: Boolean = false,
    val isTrading: Boolean = false,
    val error: String? = null,
    val tradeError: String? = null,
    val balance: PaperBalance? = null,
    val holdings: List<PaperHoldingWithPrice> = emptyList(),
    val recentTrades: List<PaperTrade> = emptyList(),
    val stats: PaperPerformanceStats? = null,
    val totalPortfolioValue: Double = 0.0,
    val holdingsValue: Double = 0.0,
    val lastTrade: PaperTrade? = null
)
