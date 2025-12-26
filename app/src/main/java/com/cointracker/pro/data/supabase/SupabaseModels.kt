package com.cointracker.pro.data.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Supabase Database Models
 * These map to tables in your Supabase PostgreSQL database
 */

/**
 * User profile (extends Supabase auth.users)
 */
@Serializable
data class UserProfile(
    val id: String,
    val email: String? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("fcm_token")
    val fcmToken: String? = null,
    @SerialName("notifications_enabled")
    val notificationsEnabled: Boolean = true,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

/**
 * Portfolio holding
 */
@Serializable
data class PortfolioHolding(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val symbol: String,
    val amount: Double,
    @SerialName("avg_buy_price")
    val avgBuyPrice: Double,
    @SerialName("total_invested")
    val totalInvested: Double,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

/**
 * Trade record
 */
@Serializable
data class TradeRecord(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val symbol: String,
    val side: String, // "BUY" or "SELL"
    val amount: Double,
    val price: Double,
    @SerialName("total_value")
    val totalValue: Double,
    val fee: Double = 0.0,
    @SerialName("signal_score")
    val signalScore: Int? = null,
    @SerialName("signal_reasons")
    val signalReasons: List<String>? = null,
    val status: String = "COMPLETED", // PENDING, COMPLETED, FAILED, CANCELLED
    @SerialName("exchange_order_id")
    val exchangeOrderId: String? = null,
    @SerialName("executed_at")
    val executedAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * Price alert configuration
 */
@Serializable
data class PriceAlert(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val symbol: String,
    @SerialName("target_price")
    val targetPrice: Double,
    val direction: String, // "ABOVE" or "BELOW"
    @SerialName("is_active")
    val isActive: Boolean = true,
    @SerialName("triggered_at")
    val triggeredAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * Trading signal from ML model (stored for history)
 */
@Serializable
data class SignalHistory(
    val id: String? = null,
    val symbol: String,
    val signal: String, // BUY, SELL, HOLD, STRONG_BUY, STRONG_SELL
    @SerialName("signal_score")
    val signalScore: Int,
    val confidence: Double,
    val reasons: List<String>,
    @SerialName("risk_level")
    val riskLevel: String,
    @SerialName("price_at_signal")
    val priceAtSignal: Double,
    @SerialName("price_24h_later")
    val price24hLater: Double? = null,
    @SerialName("was_correct")
    val wasCorrect: Boolean? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * Whale transaction alert
 */
@Serializable
data class WhaleAlert(
    val id: String? = null,
    val symbol: String,
    @SerialName("amount_usd")
    val amountUsd: Double,
    @SerialName("from_address")
    val fromAddress: String? = null,
    @SerialName("to_address")
    val toAddress: String? = null,
    @SerialName("from_label")
    val fromLabel: String? = null,
    @SerialName("to_label")
    val toLabel: String? = null,
    @SerialName("is_exchange_inflow")
    val isExchangeInflow: Boolean = false,
    @SerialName("is_exchange_outflow")
    val isExchangeOutflow: Boolean = false,
    @SerialName("tx_hash")
    val txHash: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)

/**
 * User notification preferences
 */
@Serializable
data class NotificationPreferences(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    @SerialName("trading_signals")
    val tradingSignals: Boolean = true,
    @SerialName("whale_alerts")
    val whaleAlerts: Boolean = true,
    @SerialName("price_alerts")
    val priceAlerts: Boolean = true,
    @SerialName("sentiment_shifts")
    val sentimentShifts: Boolean = true,
    @SerialName("min_signal_score")
    val minSignalScore: Int = 70,
    @SerialName("min_whale_amount_usd")
    val minWhaleAmountUsd: Double = 1000000.0,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

// ==================== PAPER TRADING MODELS ====================

/**
 * Paper Trading Balance (virtual $10k starting balance)
 */
@Serializable
data class PaperBalance(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    @SerialName("balance_usdt")
    val balanceUsdt: Double = 10000.0,
    @SerialName("initial_balance")
    val initialBalance: Double = 10000.0,
    @SerialName("total_pnl")
    val totalPnl: Double = 0.0,
    @SerialName("total_trades")
    val totalTrades: Int = 0,
    @SerialName("winning_trades")
    val winningTrades: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    val winRate: Double
        get() = if (totalTrades > 0) (winningTrades.toDouble() / totalTrades) * 100 else 0.0

    val pnlPercent: Double
        get() = if (initialBalance > 0) (totalPnl / initialBalance) * 100 else 0.0
}

/**
 * Paper Trade record
 */
@Serializable
data class PaperTrade(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val symbol: String,
    val side: String, // "BUY" or "SELL"
    val quantity: Double,
    @SerialName("entry_price")
    val entryPrice: Double,
    @SerialName("total_value")
    val totalValue: Double,
    @SerialName("exit_price")
    val exitPrice: Double? = null,
    val pnl: Double? = null,
    @SerialName("pnl_percent")
    val pnlPercent: Double? = null,
    val status: String = "OPEN", // "OPEN" or "CLOSED"
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("closed_at")
    val closedAt: String? = null
)

/**
 * Paper Trading holding (current position)
 */
@Serializable
data class PaperHolding(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    val symbol: String,
    val quantity: Double,
    @SerialName("avg_entry_price")
    val avgEntryPrice: Double,
    @SerialName("total_invested")
    val totalInvested: Double,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

/**
 * Paper holding enriched with live price data
 */
data class PaperHoldingWithPrice(
    val holding: PaperHolding,
    val currentPrice: Double,
    val currentValue: Double,
    val pnl: Double,
    val pnlPercent: Double
) {
    companion object {
        fun fromHolding(holding: PaperHolding, currentPrice: Double): PaperHoldingWithPrice {
            val currentValue = holding.quantity * currentPrice
            val pnl = currentValue - holding.totalInvested
            val pnlPercent = if (holding.totalInvested > 0) (pnl / holding.totalInvested) * 100 else 0.0
            return PaperHoldingWithPrice(
                holding = holding,
                currentPrice = currentPrice,
                currentValue = currentValue,
                pnl = pnl,
                pnlPercent = pnlPercent
            )
        }
    }
}
