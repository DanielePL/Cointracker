package com.cointracker.pro.data.repository

import android.util.Log
import com.cointracker.pro.data.supabase.SupabaseModule
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Repository for Auto Trading Settings
 * Syncs with Supabase for 24/7 cloud-based auto trading
 */
class AutoTradingRepository {

    private val database = SupabaseModule.database

    companion object {
        private const val TAG = "AutoTradingRepo"
        private const val TABLE_SETTINGS = "auto_trading_settings"
        private const val TABLE_AUTO_TRADES = "auto_trades"
    }

    /**
     * Get or create auto trading settings for user
     */
    suspend fun getSettings(userId: String): Result<AutoTradingSettings> = withContext(Dispatchers.IO) {
        try {
            // Try to get existing settings
            var settings = database
                .from(TABLE_SETTINGS)
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeSingleOrNull<AutoTradingSettings>()

            // Create default settings if not exists
            if (settings == null) {
                val newSettings = AutoTradingSettings(userId = userId)
                settings = database
                    .from(TABLE_SETTINGS)
                    .insert(newSettings) {
                        select()
                    }
                    .decodeSingle<AutoTradingSettings>()
                Log.d(TAG, "Created new auto trading settings for user: $userId")
            }

            Result.success(settings)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get auto trading settings", e)
            Result.failure(e)
        }
    }

    /**
     * Update auto trading enabled status
     */
    suspend fun setEnabled(userId: String, enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // First ensure settings exist
            getSettings(userId)

            database
                .from(TABLE_SETTINGS)
                .update({
                    set("enabled", enabled)
                    set("updated_at", java.time.Instant.now().toString())
                }) {
                    filter { eq("user_id", userId) }
                }

            Log.d(TAG, "Auto trading ${if (enabled) "enabled" else "disabled"} for user: $userId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update auto trading status", e)
            Result.failure(e)
        }
    }

    /**
     * Update all auto trading settings
     */
    suspend fun updateSettings(settings: AutoTradingSettings): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database
                .from(TABLE_SETTINGS)
                .update({
                    set("enabled", settings.enabled)
                    set("min_signal_score", settings.minSignalScore)
                    set("trade_percentage", settings.tradePercentage)
                    set("max_positions", settings.maxPositions)
                    set("stop_loss_percent", settings.stopLossPercent)
                    set("take_profit_percent", settings.takeProfitPercent)
                    set("updated_at", java.time.Instant.now().toString())
                }) {
                    filter { eq("user_id", settings.userId) }
                }

            Log.d(TAG, "Updated auto trading settings for user: ${settings.userId}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update auto trading settings", e)
            Result.failure(e)
        }
    }

    /**
     * Get auto trades history for user
     */
    suspend fun getAutoTrades(userId: String, limit: Int = 50): Result<List<AutoTrade>> = withContext(Dispatchers.IO) {
        try {
            val trades = database
                .from(TABLE_AUTO_TRADES)
                .select {
                    filter { eq("user_id", userId) }
                    limit(limit.toLong())
                }
                .decodeList<AutoTrade>()

            Result.success(trades)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get auto trades", e)
            Result.failure(e)
        }
    }
}

/**
 * Auto Trading Settings Data Class
 */
@Serializable
data class AutoTradingSettings(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val enabled: Boolean = false,
    @SerialName("min_signal_score") val minSignalScore: Int = 70,
    @SerialName("trade_percentage") val tradePercentage: Double = 0.10,
    @SerialName("max_positions") val maxPositions: Int = 3,
    @SerialName("stop_loss_percent") val stopLossPercent: Double = -5.0,
    @SerialName("take_profit_percent") val takeProfitPercent: Double = 10.0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/**
 * Auto Trade Log Entry
 */
@Serializable
data class AutoTrade(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val symbol: String,
    val side: String,
    val quantity: Double,
    val price: Double,
    @SerialName("total_value") val totalValue: Double,
    @SerialName("signal_score") val signalScore: Int? = null,
    val reason: String? = null,
    val pnl: Double? = null,
    @SerialName("pnl_percent") val pnlPercent: Double? = null,
    @SerialName("executed_at") val executedAt: String? = null
)
