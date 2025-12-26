package com.cointracker.pro.data.supabase

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for Supabase Database operations
 */
class SupabaseDatabaseRepository {

    private val database = SupabaseModule.database

    companion object {
        private const val TAG = "SupabaseDB"

        // Table names
        const val TABLE_PROFILES = "profiles"
        const val TABLE_PORTFOLIO = "portfolio_holdings"
        const val TABLE_TRADES = "trades"
        const val TABLE_PRICE_ALERTS = "price_alerts"
        const val TABLE_SIGNAL_HISTORY = "signal_history"
        const val TABLE_WHALE_ALERTS = "whale_alerts"
        const val TABLE_NOTIFICATION_PREFS = "notification_preferences"
    }

    // ==================== USER PROFILE ====================

    suspend fun getProfile(userId: String): Result<UserProfile?> = withContext(Dispatchers.IO) {
        try {
            val profile = database
                .from(TABLE_PROFILES)
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<UserProfile>()

            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get profile", e)
            Result.failure(e)
        }
    }

    suspend fun updateProfile(profile: UserProfile): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database
                .from(TABLE_PROFILES)
                .upsert(profile)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update profile", e)
            Result.failure(e)
        }
    }

    suspend fun updateFcmToken(userId: String, fcmToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database
                .from(TABLE_PROFILES)
                .update({
                    set("fcm_token", fcmToken)
                }) {
                    filter { eq("id", userId) }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update FCM token", e)
            Result.failure(e)
        }
    }

    // ==================== PORTFOLIO ====================

    suspend fun getPortfolio(userId: String): Result<List<PortfolioHolding>> = withContext(Dispatchers.IO) {
        try {
            val holdings = database
                .from(TABLE_PORTFOLIO)
                .select {
                    filter { eq("user_id", userId) }
                    order("symbol", Order.ASCENDING)
                }
                .decodeList<PortfolioHolding>()

            Result.success(holdings)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get portfolio", e)
            Result.failure(e)
        }
    }

    suspend fun upsertHolding(holding: PortfolioHolding): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database
                .from(TABLE_PORTFOLIO)
                .upsert(holding)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upsert holding", e)
            Result.failure(e)
        }
    }

    suspend fun deleteHolding(holdingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database
                .from(TABLE_PORTFOLIO)
                .delete {
                    filter { eq("id", holdingId) }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete holding", e)
            Result.failure(e)
        }
    }

    // ==================== TRADES ====================

    suspend fun getTrades(userId: String, limit: Int = 50): Result<List<TradeRecord>> = withContext(Dispatchers.IO) {
        try {
            val trades = database
                .from(TABLE_TRADES)
                .select {
                    filter { eq("user_id", userId) }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<TradeRecord>()

            Result.success(trades)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get trades", e)
            Result.failure(e)
        }
    }

    suspend fun insertTrade(trade: TradeRecord): Result<TradeRecord> = withContext(Dispatchers.IO) {
        try {
            val inserted = database
                .from(TABLE_TRADES)
                .insert(trade) {
                    select()
                }
                .decodeSingle<TradeRecord>()

            Result.success(inserted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert trade", e)
            Result.failure(e)
        }
    }

    // ==================== PRICE ALERTS ====================

    suspend fun getPriceAlerts(userId: String, activeOnly: Boolean = true): Result<List<PriceAlert>> = withContext(Dispatchers.IO) {
        try {
            val alerts = database
                .from(TABLE_PRICE_ALERTS)
                .select {
                    filter {
                        eq("user_id", userId)
                        if (activeOnly) {
                            eq("is_active", true)
                        }
                    }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<PriceAlert>()

            Result.success(alerts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get price alerts", e)
            Result.failure(e)
        }
    }

    suspend fun createPriceAlert(alert: PriceAlert): Result<PriceAlert> = withContext(Dispatchers.IO) {
        try {
            val created = database
                .from(TABLE_PRICE_ALERTS)
                .insert(alert) {
                    select()
                }
                .decodeSingle<PriceAlert>()

            Result.success(created)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create price alert", e)
            Result.failure(e)
        }
    }

    suspend fun deletePriceAlert(alertId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database
                .from(TABLE_PRICE_ALERTS)
                .delete {
                    filter { eq("id", alertId) }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete price alert", e)
            Result.failure(e)
        }
    }

    // ==================== SIGNAL HISTORY ====================

    suspend fun getSignalHistory(symbol: String? = null, limit: Int = 100): Result<List<SignalHistory>> = withContext(Dispatchers.IO) {
        try {
            val signals = database
                .from(TABLE_SIGNAL_HISTORY)
                .select {
                    if (symbol != null) {
                        filter { eq("symbol", symbol) }
                    }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<SignalHistory>()

            Result.success(signals)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get signal history", e)
            Result.failure(e)
        }
    }

    // ==================== WHALE ALERTS ====================

    suspend fun getRecentWhaleAlerts(symbol: String? = null, limit: Int = 20): Result<List<WhaleAlert>> = withContext(Dispatchers.IO) {
        try {
            val alerts = database
                .from(TABLE_WHALE_ALERTS)
                .select {
                    if (symbol != null) {
                        filter { eq("symbol", symbol) }
                    }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<WhaleAlert>()

            Result.success(alerts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get whale alerts", e)
            Result.failure(e)
        }
    }

    // ==================== NOTIFICATION PREFERENCES ====================

    suspend fun getNotificationPreferences(userId: String): Result<NotificationPreferences?> = withContext(Dispatchers.IO) {
        try {
            val prefs = database
                .from(TABLE_NOTIFICATION_PREFS)
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeSingleOrNull<NotificationPreferences>()

            Result.success(prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get notification preferences", e)
            Result.failure(e)
        }
    }

    suspend fun updateNotificationPreferences(prefs: NotificationPreferences): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database
                .from(TABLE_NOTIFICATION_PREFS)
                .upsert(prefs)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification preferences", e)
            Result.failure(e)
        }
    }
}
