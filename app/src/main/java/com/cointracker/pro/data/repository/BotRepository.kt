package com.cointracker.pro.data.repository

import android.util.Log
import com.cointracker.pro.data.supabase.BotBalance
import com.cointracker.pro.data.supabase.BotPosition
import com.cointracker.pro.data.supabase.BotSettings
import com.cointracker.pro.data.supabase.BotTrade
import com.cointracker.pro.data.supabase.BotPerformanceDaily
import com.cointracker.pro.data.supabase.SupabaseModule
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for Autonomous Trading Bot Data
 * Fetches bot balance, positions, trades, and performance from Supabase
 */
class BotRepository {

    private val database = SupabaseModule.database

    companion object {
        private const val TAG = "BotRepository"
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
