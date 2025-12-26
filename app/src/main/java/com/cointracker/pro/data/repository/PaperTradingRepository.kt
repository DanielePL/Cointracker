package com.cointracker.pro.data.repository

import android.util.Log
import com.cointracker.pro.data.supabase.PaperBalance
import com.cointracker.pro.data.supabase.PaperHolding
import com.cointracker.pro.data.supabase.PaperTrade
import com.cointracker.pro.data.supabase.SupabaseModule
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for Paper Trading operations
 * Handles virtual trading with fake money for learning/testing
 */
class PaperTradingRepository {

    private val database = SupabaseModule.database

    companion object {
        private const val TAG = "PaperTrading"
        const val TABLE_PAPER_BALANCE = "paper_balance"
        const val TABLE_PAPER_TRADES = "paper_trades"
        const val TABLE_PAPER_HOLDINGS = "paper_holdings"
        const val DEFAULT_BALANCE = 10000.0
    }

    // ==================== BALANCE ====================

    /**
     * Get or create paper trading balance for user
     * Auto-creates with $10,000 if not exists
     */
    suspend fun getOrCreateBalance(userId: String): Result<PaperBalance> = withContext(Dispatchers.IO) {
        try {
            // Try to get existing balance
            var balance = database
                .from(TABLE_PAPER_BALANCE)
                .select {
                    filter { eq("user_id", userId) }
                }
                .decodeSingleOrNull<PaperBalance>()

            // Create if not exists
            if (balance == null) {
                val newBalance = PaperBalance(
                    userId = userId,
                    balanceUsdt = DEFAULT_BALANCE,
                    initialBalance = DEFAULT_BALANCE
                )
                balance = database
                    .from(TABLE_PAPER_BALANCE)
                    .insert(newBalance) {
                        select()
                    }
                    .decodeSingle<PaperBalance>()
                Log.d(TAG, "Created new paper balance for user: $userId")
            }

            Result.success(balance)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get/create paper balance", e)
            Result.failure(e)
        }
    }

    /**
     * Reset paper trading account to initial $10,000
     */
    suspend fun resetAccount(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Delete all holdings
            database
                .from(TABLE_PAPER_HOLDINGS)
                .delete {
                    filter { eq("user_id", userId) }
                }

            // Delete all trades
            database
                .from(TABLE_PAPER_TRADES)
                .delete {
                    filter { eq("user_id", userId) }
                }

            // Reset balance
            database
                .from(TABLE_PAPER_BALANCE)
                .update({
                    set("balance_usdt", DEFAULT_BALANCE)
                    set("initial_balance", DEFAULT_BALANCE)
                    set("total_pnl", 0.0)
                    set("total_trades", 0)
                    set("winning_trades", 0)
                }) {
                    filter { eq("user_id", userId) }
                }

            Log.d(TAG, "Reset paper account for user: $userId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset paper account", e)
            Result.failure(e)
        }
    }

    // ==================== TRADING ====================

    /**
     * Execute a paper BUY order
     * The database trigger will automatically update balance and holdings
     */
    suspend fun executeBuy(
        userId: String,
        symbol: String,
        quantity: Double,
        price: Double
    ): Result<PaperTrade> = withContext(Dispatchers.IO) {
        try {
            val totalValue = quantity * price

            // Check if user has enough balance
            val balance = getOrCreateBalance(userId).getOrThrow()
            if (balance.balanceUsdt < totalValue) {
                return@withContext Result.failure(
                    IllegalStateException("Insufficient balance. Available: ${balance.balanceUsdt}, Required: $totalValue")
                )
            }

            val trade = PaperTrade(
                userId = userId,
                symbol = symbol,
                side = "BUY",
                quantity = quantity,
                entryPrice = price,
                totalValue = totalValue,
                status = "OPEN"
            )

            val inserted = database
                .from(TABLE_PAPER_TRADES)
                .insert(trade) {
                    select()
                }
                .decodeSingle<PaperTrade>()

            Log.d(TAG, "Executed paper BUY: $quantity $symbol @ $price")
            Result.success(inserted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute paper buy", e)
            Result.failure(e)
        }
    }

    /**
     * Execute a paper SELL order
     * Calculates P&L and updates balance/holdings via trigger
     */
    suspend fun executeSell(
        userId: String,
        symbol: String,
        quantity: Double,
        price: Double
    ): Result<PaperTrade> = withContext(Dispatchers.IO) {
        try {
            // Check if user has enough holdings
            val holding = getHolding(userId, symbol).getOrNull()
            if (holding == null || holding.quantity < quantity) {
                val available = holding?.quantity ?: 0.0
                return@withContext Result.failure(
                    IllegalStateException("Insufficient holdings. Available: $available $symbol, Requested: $quantity")
                )
            }

            val totalValue = quantity * price
            val avgEntryPrice = holding.avgEntryPrice
            val costBasis = quantity * avgEntryPrice
            val pnl = totalValue - costBasis
            val pnlPercent = if (costBasis > 0) (pnl / costBasis) * 100 else 0.0

            val trade = PaperTrade(
                userId = userId,
                symbol = symbol,
                side = "SELL",
                quantity = quantity,
                entryPrice = avgEntryPrice,
                totalValue = totalValue,
                exitPrice = price,
                pnl = pnl,
                pnlPercent = pnlPercent,
                status = "CLOSED"
            )

            val inserted = database
                .from(TABLE_PAPER_TRADES)
                .insert(trade) {
                    select()
                }
                .decodeSingle<PaperTrade>()

            Log.d(TAG, "Executed paper SELL: $quantity $symbol @ $price (P&L: $pnl)")
            Result.success(inserted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute paper sell", e)
            Result.failure(e)
        }
    }

    // ==================== HOLDINGS ====================

    /**
     * Get all paper holdings for user
     */
    suspend fun getHoldings(userId: String): Result<List<PaperHolding>> = withContext(Dispatchers.IO) {
        try {
            val holdings = database
                .from(TABLE_PAPER_HOLDINGS)
                .select {
                    filter { eq("user_id", userId) }
                    order("symbol", Order.ASCENDING)
                }
                .decodeList<PaperHolding>()

            Result.success(holdings)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get paper holdings", e)
            Result.failure(e)
        }
    }

    /**
     * Get specific holding for symbol
     */
    suspend fun getHolding(userId: String, symbol: String): Result<PaperHolding?> = withContext(Dispatchers.IO) {
        try {
            val holding = database
                .from(TABLE_PAPER_HOLDINGS)
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("symbol", symbol)
                    }
                }
                .decodeSingleOrNull<PaperHolding>()

            Result.success(holding)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get paper holding", e)
            Result.failure(e)
        }
    }

    // ==================== TRADE HISTORY ====================

    /**
     * Get paper trade history
     */
    suspend fun getTradeHistory(userId: String, limit: Int = 50): Result<List<PaperTrade>> = withContext(Dispatchers.IO) {
        try {
            val trades = database
                .from(TABLE_PAPER_TRADES)
                .select {
                    filter { eq("user_id", userId) }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<PaperTrade>()

            Result.success(trades)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get paper trade history", e)
            Result.failure(e)
        }
    }

    /**
     * Get open trades only
     */
    suspend fun getOpenTrades(userId: String): Result<List<PaperTrade>> = withContext(Dispatchers.IO) {
        try {
            val trades = database
                .from(TABLE_PAPER_TRADES)
                .select {
                    filter {
                        eq("user_id", userId)
                        eq("status", "OPEN")
                    }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<PaperTrade>()

            Result.success(trades)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get open paper trades", e)
            Result.failure(e)
        }
    }

    // ==================== STATS ====================

    /**
     * Get performance statistics
     */
    suspend fun getPerformanceStats(userId: String): Result<PaperPerformanceStats> = withContext(Dispatchers.IO) {
        try {
            val balance = getOrCreateBalance(userId).getOrThrow()
            val trades = getTradeHistory(userId, limit = 1000).getOrDefault(emptyList())

            val closedTrades = trades.filter { it.status == "CLOSED" }
            val winningTrades = closedTrades.filter { (it.pnl ?: 0.0) > 0 }

            val bestTrade = closedTrades.maxByOrNull { it.pnl ?: 0.0 }
            val worstTrade = closedTrades.minByOrNull { it.pnl ?: 0.0 }

            val stats = PaperPerformanceStats(
                totalPnl = balance.totalPnl,
                pnlPercent = balance.pnlPercent,
                winRate = balance.winRate,
                totalTrades = balance.totalTrades,
                winningTrades = balance.winningTrades,
                losingTrades = balance.totalTrades - balance.winningTrades,
                bestTrade = bestTrade,
                worstTrade = worstTrade,
                currentBalance = balance.balanceUsdt,
                initialBalance = balance.initialBalance
            )

            Result.success(stats)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get performance stats", e)
            Result.failure(e)
        }
    }
}

/**
 * Paper trading performance statistics
 */
data class PaperPerformanceStats(
    val totalPnl: Double,
    val pnlPercent: Double,
    val winRate: Double,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val bestTrade: PaperTrade?,
    val worstTrade: PaperTrade?,
    val currentBalance: Double,
    val initialBalance: Double
)
