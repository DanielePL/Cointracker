package com.cointracker.pro.data.repository

import android.util.Log
import com.cointracker.pro.data.supabase.BotBalance
import com.cointracker.pro.data.supabase.BotPosition
import com.cointracker.pro.data.supabase.BotSettings
import com.cointracker.pro.data.supabase.BotStatusResponse
import com.cointracker.pro.data.supabase.BotTrade
import com.cointracker.pro.data.supabase.BotPerformanceDaily
import com.cointracker.pro.data.supabase.SupabaseModule
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URL

/**
 * Repository for Autonomous Trading Bot Data
 * Fetches bot balance, positions, trades, and performance from Supabase
 */
class BotRepository {

    private val database = SupabaseModule.database
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "BotRepository"
        private const val API_BASE_URL = "https://cointracker-or1f.onrender.com"
    }

    /**
     * Get full bot status from API (with live prices)
     */
    suspend fun getBotStatus(): Result<BotStatusResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching bot status from API...")
            val url = URL("$API_BASE_URL/api/v3/analysis/bot/status")
            val connection = url.openConnection()
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val response = connection.getInputStream().bufferedReader().readText()
            Log.d(TAG, "API response length: ${response.length}")

            val status = json.decodeFromString<BotStatusResponse>(response)

            // Log first position's PnL to verify data
            status.positions.firstOrNull()?.let { pos ->
                Log.d(TAG, "First position: ${pos.coin} unrealizedPnl=${pos.unrealizedPnl} currentPrice=${pos.currentPrice}")
            }

            Log.d(TAG, "Got bot status with ${status.positions.size} positions, total unrealized: ${status.positionsSummary.totalUnrealizedPnl}")
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bot status from API: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get positions with live prices from API
     */
    suspend fun getPositionsLive(): Result<List<BotPosition>> = withContext(Dispatchers.IO) {
        try {
            val statusResult = getBotStatus()
            if (statusResult.isSuccess) {
                val positions = statusResult.getOrNull()?.positions?.map { it.toBotPosition() } ?: emptyList()
                Result.success(positions)
            } else {
                // Fallback to database if API fails
                Log.w(TAG, "API failed, falling back to database")
                getPositions()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get live positions", e)
            getPositions() // Fallback
        }
    }

    /**
     * Get current bot balance and stats
     */
    suspend fun getBalance(): Result<BotBalance> = withContext(Dispatchers.IO) {
        try {
            val balance = database
                .from("bot_balance")
                .select()
                .decodeSingleOrNull<BotBalance>()

            if (balance != null) {
                Result.success(balance)
            } else {
                // Return default balance if not found
                Result.success(BotBalance())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bot balance", e)
            Result.failure(e)
        }
    }

    /**
     * Get current open positions
     */
    suspend fun getPositions(): Result<List<BotPosition>> = withContext(Dispatchers.IO) {
        try {
            val positions = database
                .from("bot_positions")
                .select()
                .decodeList<BotPosition>()

            Result.success(positions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bot positions", e)
            Result.failure(e)
        }
    }

    /**
     * Get trade history
     */
    suspend fun getTrades(limit: Int = 50): Result<List<BotTrade>> = withContext(Dispatchers.IO) {
        try {
            val trades = database
                .from("bot_trades")
                .select {
                    order("opened_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<BotTrade>()

            Result.success(trades)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bot trades", e)
            Result.failure(e)
        }
    }

    /**
     * Get bot settings
     */
    suspend fun getSettings(): Result<BotSettings> = withContext(Dispatchers.IO) {
        try {
            val settings = database
                .from("bot_settings")
                .select()
                .decodeSingleOrNull<BotSettings>()

            Result.success(settings ?: BotSettings())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bot settings", e)
            Result.failure(e)
        }
    }

    /**
     * Get daily performance history
     */
    suspend fun getDailyPerformance(days: Int = 30): Result<List<BotPerformanceDaily>> = withContext(Dispatchers.IO) {
        try {
            val performance = database
                .from("bot_performance_daily")
                .select {
                    order("date", Order.DESCENDING)
                    limit(days.toLong())
                }
                .decodeList<BotPerformanceDaily>()

            Result.success(performance)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get daily performance", e)
            Result.failure(e)
        }
    }

    /**
     * Get trade statistics
     */
    suspend fun getTradeStats(): Result<TradeStats> = withContext(Dispatchers.IO) {
        try {
            val balance = getBalance().getOrNull() ?: BotBalance()
            val positions = getPositions().getOrNull() ?: emptyList()
            val recentTrades = getTrades(10).getOrNull() ?: emptyList()

            val totalInPositions = positions.sumOf { it.totalInvested }
            val unrealizedPnl = positions.sumOf { it.unrealizedPnl }

            Result.success(
                TradeStats(
                    balance = balance.balanceUsdt,
                    totalPnl = balance.totalPnl,
                    totalPnlPercent = balance.totalPnlPercent,
                    winRate = balance.winRate,
                    totalTrades = balance.totalTrades,
                    openPositions = positions.size,
                    totalInPositions = totalInPositions,
                    unrealizedPnl = unrealizedPnl,
                    largestWin = balance.largestWin,
                    largestLoss = balance.largestLoss,
                    recentTrades = recentTrades
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get trade stats", e)
            Result.failure(e)
        }
    }
}

/**
 * Combined trading statistics
 */
data class TradeStats(
    val balance: Double,
    val totalPnl: Double,
    val totalPnlPercent: Double,
    val winRate: Double,
    val totalTrades: Int,
    val openPositions: Int,
    val totalInPositions: Double,
    val unrealizedPnl: Double,
    val largestWin: Double,
    val largestLoss: Double,
    val recentTrades: List<BotTrade>
)
