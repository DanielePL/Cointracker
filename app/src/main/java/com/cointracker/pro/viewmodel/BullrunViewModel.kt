package com.cointracker.pro.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cointracker.pro.data.repository.BullrunRepository
import com.cointracker.pro.data.supabase.BullrunCoin
import com.cointracker.pro.data.supabase.BullrunMarketSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BullrunUiState(
    val isLoading: Boolean = true,
    val coins: List<BullrunCoin> = emptyList(),
    val marketSummary: BullrunMarketSummary? = null,
    val error: String? = null
)

class BullrunViewModel : ViewModel() {

    private val repository = BullrunRepository()

    private val _uiState = MutableStateFlow(BullrunUiState())
    val uiState: StateFlow<BullrunUiState> = _uiState.asStateFlow()

    init {
        loadBullrunCoins()
    }

    fun refresh() {
        loadBullrunCoins()
    }

    private fun loadBullrunCoins() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            repository.getBullrunCoins(limit = 10).fold(
                onSuccess = { response ->
                    _uiState.value = BullrunUiState(
                        isLoading = false,
                        coins = response.topBullrunCoins,
                        marketSummary = response.marketSummary,
                        error = null
                    )
                },
                onFailure = { e ->
                    _uiState.value = BullrunUiState(
                        isLoading = false,
                        coins = emptyList(),
                        marketSummary = null,
                        error = e.message
                    )
                }
            )
        }
    }
}
