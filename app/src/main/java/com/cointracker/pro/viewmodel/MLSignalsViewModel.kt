package com.cointracker.pro.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cointracker.pro.data.supabase.MLAnalysisLog
import com.cointracker.pro.data.supabase.MLSignalDisplay
import com.cointracker.pro.data.supabase.SignalFilter
import com.cointracker.pro.data.supabase.SupabaseDatabaseRepository
import com.cointracker.pro.data.supabase.SupabaseRealtimeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI State for ML Signals screen
 */
data class MLSignalsUiState(
    val isLoading: Boolean = true,
    val isLive: Boolean = false,
    val error: String? = null,
    val signals: List<MLSignalDisplay> = emptyList(),
    val selectedFilter: SignalFilter = SignalFilter.ALL,
    val lastUpdate: String? = null,
    val totalAnalyzed: Int = 0
)

/**
 * ViewModel for ML Signals functionality
 * Fetches analysis results from Supabase and provides real-time updates
 */
class MLSignalsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MLSignalsVM"
    }

    private val databaseRepository = SupabaseDatabaseRepository()
    private val realtimeService = SupabaseRealtimeService()

    private val _uiState = MutableStateFlow(MLSignalsUiState())
    val uiState: StateFlow<MLSignalsUiState> = _uiState.asStateFlow()

    init {
        loadMLSignals()
        connectRealtime()
        observeRealtimeUpdates()
    }

    /**
     * Load ML signals from Supabase
     */
    fun loadMLSignals() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val filter = _uiState.value.selectedFilter
                val result = databaseRepository.getAnalysisLogsBySignal(
                    signalType = filter.value,
                    limit = 200
                )

                result.onSuccess { logs ->
                    // Group by coin and take latest for each
                    val latestByCoin = logs
                        .groupBy { it.coin }
                        .mapValues { (_, entries) -> entries.first() }
                        .values
                        .sortedByDescending { it.mlScoreInt }
                        .map { MLSignalDisplay.from(it) }

                    val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date())

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            signals = latestByCoin,
                            lastUpdate = timestamp,
                            totalAnalyzed = latestByCoin.size
                        )
                    }
                    Log.d(TAG, "Loaded ${latestByCoin.size} ML signals")
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, error = error.message)
                    }
                    Log.e(TAG, "Failed to load ML signals", error)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exception loading ML signals", e)
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /**
     * Connect to real-time updates
     */
    private fun connectRealtime() {
        realtimeService.connect()
        _uiState.update { it.copy(isLive = true) }
    }

    /**
     * Observe real-time ML analysis updates
     */
    private fun observeRealtimeUpdates() {
        viewModelScope.launch {
            realtimeService.mlAnalysisUpdates.collect { newAnalysis ->
                handleNewAnalysis(newAnalysis)
            }
        }
    }

    /**
     * Handle incoming real-time analysis update
     */
    private fun handleNewAnalysis(analysis: MLAnalysisLog) {
        val currentFilter = _uiState.value.selectedFilter

        // Check if this analysis matches current filter
        if (currentFilter.value != null && currentFilter.value != analysis.mlSignal) {
            return
        }

        val newDisplay = MLSignalDisplay.from(analysis)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date())

        _uiState.update { state ->
            // Replace existing signal for this coin or add new
            val updatedSignals = state.signals
                .filter { it.analysisLog.coin != analysis.coin }
                .plus(newDisplay)
                .sortedByDescending { it.analysisLog.mlScoreInt }

            state.copy(
                signals = updatedSignals,
                lastUpdate = timestamp,
                totalAnalyzed = updatedSignals.size
            )
        }

        Log.d(TAG, "Real-time update: ${analysis.coin} -> ${analysis.mlSignal}")
    }

    /**
     * Set signal filter and reload data
     */
    fun setFilter(filter: SignalFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        loadMLSignals()
    }

    /**
     * Refresh signals
     */
    fun refresh() {
        loadMLSignals()
    }

    override fun onCleared() {
        super.onCleared()
        realtimeService.disconnect()
    }
}
