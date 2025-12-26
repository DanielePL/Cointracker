package com.cointracker.pro.data.supabase

import android.util.Log
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Service for Supabase Realtime subscriptions
 * Handles live updates for whale alerts, signals, and price alerts
 */
class SupabaseRealtimeService {

    private val realtime = SupabaseModule.realtime
    private val scope = CoroutineScope(Dispatchers.IO)
    private var connectionJob: Job? = null

    companion object {
        private const val TAG = "SupabaseRealtime"
    }

    // Flows for live updates
    private val _whaleAlerts = MutableSharedFlow<WhaleAlert>(replay = 1)
    val whaleAlerts: Flow<WhaleAlert> = _whaleAlerts.asSharedFlow()

    private val _signalUpdates = MutableSharedFlow<SignalHistory>(replay = 1)
    val signalUpdates: Flow<SignalHistory> = _signalUpdates.asSharedFlow()

    private val _priceAlertTriggered = MutableSharedFlow<PriceAlert>(replay = 1)
    val priceAlertTriggered: Flow<PriceAlert> = _priceAlertTriggered.asSharedFlow()

    private val _tradeUpdates = MutableSharedFlow<TradeRecord>(replay = 1)
    val tradeUpdates: Flow<TradeRecord> = _tradeUpdates.asSharedFlow()

    /**
     * Connect to realtime and subscribe to all relevant channels
     */
    fun connect() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            try {
                Log.d(TAG, "Connecting to Supabase Realtime...")
                realtime.connect()

                subscribeToWhaleAlerts()
                subscribeToSignals()
                subscribeToTrades()

                Log.d(TAG, "Realtime connected and subscribed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to Realtime", e)
            }
        }
    }

    /**
     * Disconnect from realtime
     */
    fun disconnect() {
        scope.launch {
            try {
                realtime.disconnect()
                connectionJob?.cancel()
                Log.d(TAG, "Realtime disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting", e)
            }
        }
    }

    /**
     * Subscribe to whale alerts table for new entries
     */
    private suspend fun subscribeToWhaleAlerts() {
        val channel = realtime.channel("whale-alerts")

        channel.postgresChangeFlow<PostgresAction.Insert>(
            schema = "public"
        ) {
            table = "whale_alerts"
        }.onEach { change ->
            try {
                val alert = Json.decodeFromString<WhaleAlert>(change.record.toString())
                Log.d(TAG, "New whale alert: ${alert.symbol} - $${alert.amountUsd}")
                _whaleAlerts.emit(alert)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse whale alert", e)
            }
        }.catch { e ->
            Log.e(TAG, "Whale alerts stream error", e)
        }.launchIn(scope)

        channel.subscribe()
    }

    /**
     * Subscribe to signal history for new signals
     */
    private suspend fun subscribeToSignals() {
        val channel = realtime.channel("signals")

        channel.postgresChangeFlow<PostgresAction.Insert>(
            schema = "public"
        ) {
            table = "signal_history"
        }.onEach { change ->
            try {
                val signal = Json.decodeFromString<SignalHistory>(change.record.toString())
                Log.d(TAG, "New signal: ${signal.symbol} - ${signal.signal}")
                _signalUpdates.emit(signal)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse signal", e)
            }
        }.catch { e ->
            Log.e(TAG, "Signals stream error", e)
        }.launchIn(scope)

        channel.subscribe()
    }

    /**
     * Subscribe to trade updates for a specific user
     */
    private suspend fun subscribeToTrades() {
        val userId = SupabaseModule.auth.currentUserOrNull()?.id ?: return

        val channel = realtime.channel("user-trades-$userId")

        // Listen for new trades
        channel.postgresChangeFlow<PostgresAction.Insert>(
            schema = "public"
        ) {
            table = "trades"
        }.onEach { change ->
            try {
                val trade = Json.decodeFromString<TradeRecord>(change.record.toString())
                if (trade.userId == userId) {
                    Log.d(TAG, "New trade: ${trade.symbol} ${trade.side}")
                    _tradeUpdates.emit(trade)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse trade", e)
            }
        }.catch { e ->
            Log.e(TAG, "Trades stream error", e)
        }.launchIn(scope)

        // Listen for trade status updates
        channel.postgresChangeFlow<PostgresAction.Update>(
            schema = "public"
        ) {
            table = "trades"
        }.onEach { change ->
            try {
                val trade = Json.decodeFromString<TradeRecord>(change.record.toString())
                if (trade.userId == userId) {
                    Log.d(TAG, "Trade updated: ${trade.id} -> ${trade.status}")
                    _tradeUpdates.emit(trade)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse trade update", e)
            }
        }.catch { e ->
            Log.e(TAG, "Trade updates stream error", e)
        }.launchIn(scope)

        channel.subscribe()
    }

    /**
     * Subscribe to price alert triggers for current user
     */
    suspend fun subscribeToUserPriceAlerts(userId: String) {
        val channel = realtime.channel("price-alerts-$userId")

        channel.postgresChangeFlow<PostgresAction.Update>(
            schema = "public"
        ) {
            table = "price_alerts"
        }.onEach { change ->
            try {
                val alert = Json.decodeFromString<PriceAlert>(change.record.toString())
                if (alert.userId == userId && alert.triggeredAt != null) {
                    Log.d(TAG, "Price alert triggered: ${alert.symbol} at ${alert.targetPrice}")
                    _priceAlertTriggered.emit(alert)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse price alert", e)
            }
        }.catch { e ->
            Log.e(TAG, "Price alerts stream error", e)
        }.launchIn(scope)

        channel.subscribe()
    }
}
