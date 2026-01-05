package com.cointracker.pro.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cointracker.pro.data.repository.BotRepository
import com.cointracker.pro.data.repository.TradeStats
import com.cointracker.pro.data.supabase.BotBalance
import com.cointracker.pro.data.supabase.BotPosition
import com.cointracker.pro.data.supabase.BotSettings
import com.cointracker.pro.data.supabase.BotTrade
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Autonomous Bot Screen
 */
class BotViewModel : ViewModel() {

    private val repository = BotRepository()

    private val _uiState = MutableStateFlow(BotUiState())
    val uiState: StateFlow<BotUiState> = _uiState.asStateFlow()

    init {
        loadBotData()
    }

    fun loadBotData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // Load all bot data in parallel
                // Use live positions from API (includes current prices)
                val balanceResult = repository.getBalance()
                val positionsResult = repository.getPositionsLive()
                val tradesResult = repository.getTrades(50)
                val settingsResult = repository.getSettings()

                val balance = balanceResult.getOrNull() ?: BotBalance()
                val positions = positionsResult.getOrNull() ?: emptyList()
                val trades = tradesResult.getOrNull() ?: emptyList()
                val settings = settingsResult.getOrNull() ?: BotSettings()

                _uiState.value = BotUiState(
                    isLoading = false,
                    balance = balance,
                    positions = positions,
                    trades = trades,
                    settings = settings,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load bot data"
                )
            }
        }
    }

    fun refresh() {
        loadBotData()
    }
}

/**
 * UI State for Bot Screen
 */
data class BotUiState(
    val isLoading: Boolean = true,
    val balance: BotBalance = BotBalance(),
    val positions: List<BotPosition> = emptyList(),
    val trades: List<BotTrade> = emptyList(),
    val settings: BotSettings = BotSettings(),
    val error: String? = null
) {
    val totalValue: Double
        get() = balance.balanceUsdt + positions.sumOf { it.totalInvested }

    val unrealizedPnl: Double
        get() = positions.sumOf { it.calculatedUnrealizedPnl }

    val openTrades: List<BotTrade>
        get() = trades.filter { it.status == "OPEN" }

    val closedTrades: List<BotTrade>
        get() = trades.filter { it.status != "OPEN" }

    val profitableTrades: Int
        get() = closedTrades.count { it.isProfitable }

    val losingTrades: Int
        get() = closedTrades.count { !it.isProfitable && it.pnl != null }
}
