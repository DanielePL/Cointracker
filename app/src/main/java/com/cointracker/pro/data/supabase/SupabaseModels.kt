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

// ==================== AUTONOMOUS BOT MODELS ====================

/**
 * Bot Balance - Current state of the trading bot's virtual balance
 */
@Serializable
data class BotBalance(
    val id: Int? = null,
    @SerialName("balance_usdt")
    val balanceUsdt: Double = 10000.0,
    @SerialName("initial_balance")
    val initialBalance: Double = 10000.0,
    @SerialName("total_pnl")
    val totalPnl: Double = 0.0,
    @SerialName("total_pnl_percent")
    val totalPnlPercent: Double = 0.0,
    @SerialName("total_trades")
    val totalTrades: Int = 0,
    @SerialName("winning_trades")
    val winningTrades: Int = 0,
    @SerialName("losing_trades")
    val losingTrades: Int = 0,
    @SerialName("largest_win")
    val largestWin: Double = 0.0,
    @SerialName("largest_loss")
    val largestLoss: Double = 0.0,
    @SerialName("max_drawdown")
    val maxDrawdown: Double = 0.0,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
) {
    val winRate: Double
        get() = if (totalTrades > 0) (winningTrades.toDouble() / totalTrades) * 100 else 0.0
}

/**
 * Bot Position - Current open position with trailing stop support
 */
@Serializable
data class BotPosition(
    val id: Int? = null,
    val coin: String,
    val side: String = "LONG",
    val quantity: Double,
    @SerialName("entry_price")
    val entryPrice: Double,
    @SerialName("current_price")
    val currentPrice: Double? = null,
    @SerialName("total_invested")
    val totalInvested: Double,
    @SerialName("unrealized_pnl")
    val unrealizedPnl: Double = 0.0,
    @SerialName("unrealized_pnl_percent")
    val unrealizedPnlPercent: Double = 0.0,
    @SerialName("stop_loss")
    val stopLoss: Double? = null,
    @SerialName("take_profit")
    val takeProfit: Double? = null,
    @SerialName("highest_price")
    val highestPrice: Double? = null,  // Highest price since entry (for trailing stop)
    @SerialName("trailing_stop")
    val trailingStop: Double? = null,  // Current trailing stop price
    @SerialName("signal_score")
    val signalScore: Int? = null,
    @SerialName("entry_signal")
    val entrySignal: String? = null,
    @SerialName("opened_at")
    val openedAt: String? = null
) {
    /**
     * Get unrealized PnL - prefer API value, fall back to calculated
     */
    val calculatedUnrealizedPnl: Double
        get() {
            // If we have API-provided value, use it
            if (unrealizedPnl != 0.0 || currentPrice == null) {
                return unrealizedPnl
            }
            // Otherwise calculate from prices
            val current = currentPrice ?: entryPrice
            return if (side == "SHORT") {
                (entryPrice - current) * quantity
            } else {
                (current - entryPrice) * quantity
            }
        }

    val calculatedUnrealizedPnlPercent: Double
        get() {
            // If we have API-provided value, use it
            if (unrealizedPnlPercent != 0.0 || currentPrice == null) {
                return unrealizedPnlPercent
            }
            // Otherwise calculate from prices
            val current = currentPrice ?: entryPrice
            return if (entryPrice > 0) {
                if (side == "SHORT") {
                    ((entryPrice - current) / entryPrice) * 100
                } else {
                    ((current - entryPrice) / entryPrice) * 100
                }
            } else 0.0
        }

    /**
     * Calculate profit locked by trailing stop
     * Returns the % profit that would be realized if trailing stop triggers
     */
    val trailingStopProfit: Double?
        get() {
            if (trailingStop == null || entryPrice <= 0) return null
            return ((trailingStop - entryPrice) / entryPrice) * 100
        }
}

/**
 * Bot Trade - Executed trade record
 */
@Serializable
data class BotTrade(
    val id: Int? = null,
    val coin: String,
    val side: String, // "BUY" or "SELL"
    val quantity: Double,
    @SerialName("entry_price")
    val entryPrice: Double,
    @SerialName("exit_price")
    val exitPrice: Double? = null,
    @SerialName("total_value")
    val totalValue: Double,
    val pnl: Double? = null,
    @SerialName("pnl_percent")
    val pnlPercent: Double? = null,
    val fee: Double = 0.0,
    @SerialName("signal_type")
    val signalType: String? = null,
    @SerialName("signal_score")
    val signalScore: Int? = null,
    @SerialName("signal_reasons")
    val signalReasons: List<String>? = null,
    val rsi: Double? = null,
    val macd: Double? = null,
    val status: String = "OPEN", // "OPEN", "CLOSED", "STOPPED_OUT", "TAKE_PROFIT"
    @SerialName("close_reason")
    val closeReason: String? = null,
    @SerialName("opened_at")
    val openedAt: String? = null,
    @SerialName("closed_at")
    val closedAt: String? = null,
    @SerialName("balance_before")
    val balanceBefore: Double? = null,
    @SerialName("balance_after")
    val balanceAfter: Double? = null
) {
    val isProfitable: Boolean
        get() = (pnl ?: 0.0) > 0

    val isClosed: Boolean
        get() = status != "OPEN"
}

/**
 * Bot Settings - Configuration for the trading bot
 */
@Serializable
data class BotSettings(
    val id: Int? = null,
    @SerialName("min_signal_score")
    val minSignalScore: Int = 65,
    @SerialName("max_position_size_percent")
    val maxPositionSizePercent: Double = 20.0,
    @SerialName("max_positions")
    val maxPositions: Int = 5,
    @SerialName("stop_loss_percent")
    val stopLossPercent: Double = -5.0,
    @SerialName("take_profit_percent")
    val takeProfitPercent: Double = 15.0,
    @SerialName("required_confidence")
    val requiredConfidence: Double = 0.6,
    @SerialName("min_volume_24h")
    val minVolume24h: Double = 1000000.0,
    @SerialName("enabled_coins")
    val enabledCoins: List<String> = listOf("BTC", "ETH", "SOL", "XRP", "ADA"),
    @SerialName("is_active")
    val isActive: Boolean = true,
    @SerialName("last_run_at")
    val lastRunAt: String? = null
)

/**
 * Bot Performance Daily - Daily trading statistics
 */
@Serializable
data class BotPerformanceDaily(
    val id: Int? = null,
    val date: String,
    @SerialName("starting_balance")
    val startingBalance: Double,
    @SerialName("ending_balance")
    val endingBalance: Double,
    @SerialName("daily_pnl")
    val dailyPnl: Double = 0.0,
    @SerialName("daily_pnl_percent")
    val dailyPnlPercent: Double = 0.0,
    @SerialName("trades_count")
    val tradesCount: Int = 0,
    @SerialName("winning_trades")
    val winningTrades: Int = 0,
    @SerialName("losing_trades")
    val losingTrades: Int = 0,
    @SerialName("best_trade_pnl")
    val bestTradePnl: Double? = null,
    @SerialName("worst_trade_pnl")
    val worstTradePnl: Double? = null
)

// ==================== ML ANALYSIS MODELS ====================

/**
 * ML Analysis Log from backend analysis_logs table
 * Contains the results of automated ML analysis runs
 */
@Serializable
data class MLAnalysisLog(
    val id: String? = null,  // Can be UUID or numeric ID
    val coin: String,
    val timestamp: String? = null,
    val price: Double = 0.0,
    @SerialName("volume_24h")
    val volume24h: Double? = null,
    @SerialName("price_change_24h")
    val priceChange24h: Double? = null,

    // Technical indicators
    val rsi: Double? = null,
    val macd: Double? = null,
    @SerialName("macd_signal")
    val macdSignal: Double? = null,
    @SerialName("ema_12")
    val ema12: Double? = null,
    @SerialName("ema_26")
    val ema26: Double? = null,
    @SerialName("bb_upper")
    val bbUpper: Double? = null,
    @SerialName("bb_lower")
    val bbLower: Double? = null,
    val atr: Double? = null,

    // ML signal outputs
    @SerialName("ml_signal")
    val mlSignal: String = "HOLD",
    @SerialName("ml_score")
    val mlScore: Double = 50.0,  // Changed to Double for flexibility
    @SerialName("ml_confidence")
    val mlConfidence: Double? = null,
    @SerialName("top_reasons")
    val topReasons: List<String> = emptyList(),  // JSON array directly

    // Technical signal for comparison
    @SerialName("tech_signal")
    val techSignal: String? = null,
    @SerialName("tech_score")
    val techScore: Double? = null
) {
    // Helper to get mlScore as Int
    val mlScoreInt: Int get() = mlScore.toInt()
}

/**
 * Signal filter options for UI
 */
enum class SignalFilter(val displayName: String, val value: String?) {
    ALL("All", null),
    STRONG_BUY("Strong Buy", "STRONG_BUY"),
    BUY("Buy", "BUY"),
    HOLD("Hold", "HOLD"),
    SELL("Sell", "SELL"),
    STRONG_SELL("Strong Sell", "STRONG_SELL")
}

/**
 * Signal color type for UI
 */
enum class SignalColorType { BULLISH, BEARISH, NEUTRAL }

/**
 * ML Signal for UI display with formatting helpers
 */
data class MLSignalDisplay(
    val analysisLog: MLAnalysisLog,
    val formattedPrice: String,
    val formattedChange: String,
    val signalColor: SignalColorType
) {
    companion object {
        fun from(log: MLAnalysisLog): MLSignalDisplay {
            val colorType = when (log.mlSignal) {
                "STRONG_BUY", "BUY" -> SignalColorType.BULLISH
                "STRONG_SELL", "SELL" -> SignalColorType.BEARISH
                else -> SignalColorType.NEUTRAL
            }
            return MLSignalDisplay(
                analysisLog = log,
                formattedPrice = formatPrice(log.price),
                formattedChange = formatChange(log.priceChange24h),
                signalColor = colorType
            )
        }

        private fun formatPrice(price: Double): String = when {
            price >= 1000 -> "$${"%,.0f".format(price)}"
            price >= 1 -> "$${"%,.2f".format(price)}"
            else -> "$${"%,.6f".format(price)}"
        }

        private fun formatChange(change: Double?): String {
            if (change == null) return "N/A"
            return "${if (change >= 0) "+" else ""}${"%.2f".format(change)}%"
        }
    }
}

// ==================== BULLRUN SCANNER MODELS ====================

/**
 * Coin with bullrun indicators from backend scanner
 */
@Serializable
data class BullrunCoin(
    val symbol: String,
    val price: Double,
    @SerialName("price_change_24h")
    val priceChange24h: Double,
    @SerialName("volume_change")
    val volumeChange: Double,
    @SerialName("bullrun_score")
    val bullrunScore: Int,
    val signals: List<String>,
    val rsi: Double? = null,
    @SerialName("above_ema50")
    val aboveEma50: Boolean = false,
    @SerialName("above_ema200")
    val aboveEma200: Boolean = false,
    @SerialName("macd_bullish")
    val macdBullish: Boolean = false
)

/**
 * Market summary from bullrun scanner
 */
@Serializable
data class BullrunMarketSummary(
    @SerialName("coins_scanned")
    val coinsScanned: Int,
    @SerialName("strong_bullrun")
    val strongBullrun: Int,
    @SerialName("moderate_bullish")
    val moderateBullish: Int,
    @SerialName("market_sentiment")
    val marketSentiment: String
)

/**
 * Full response from bullrun scanner endpoint
 */
@Serializable
data class BullrunScannerResponse(
    val timestamp: String,
    @SerialName("market_summary")
    val marketSummary: BullrunMarketSummary,
    @SerialName("top_bullrun_coins")
    val topBullrunCoins: List<BullrunCoin>
)

// ============================================
// Bot Status API Response Models
// ============================================

/**
 * Position with live prices from API
 */
@Serializable
data class BotPositionLive(
    val coin: String,
    val side: String = "LONG",
    val quantity: Double,
    @SerialName("entry_price")
    val entryPrice: Double,
    @SerialName("current_price")
    val currentPrice: Double,
    @SerialName("current_value")
    val currentValue: Double,
    @SerialName("unrealized_pnl")
    val unrealizedPnl: Double,
    @SerialName("unrealized_pnl_percent")
    val unrealizedPnlPercent: Double,
    @SerialName("stop_loss")
    val stopLoss: Double? = null,
    @SerialName("take_profit")
    val takeProfit: Double? = null
) {
    /**
     * Convert to BotPosition for UI compatibility
     */
    fun toBotPosition(): BotPosition = BotPosition(
        coin = coin,
        side = side,
        quantity = quantity,
        entryPrice = entryPrice,
        currentPrice = currentPrice,
        totalInvested = entryPrice * quantity,
        unrealizedPnl = unrealizedPnl,
        unrealizedPnlPercent = unrealizedPnlPercent,
        stopLoss = stopLoss,
        takeProfit = takeProfit
    )
}

/**
 * Balance info from API
 */
@Serializable
data class BotBalanceInfo(
    val current: Double,
    val initial: Double,
    @SerialName("total_pnl")
    val totalPnl: Double,
    @SerialName("total_pnl_percent")
    val totalPnlPercent: Double,
    @SerialName("total_trades")
    val totalTrades: Int,
    @SerialName("win_rate")
    val winRate: Double
)

/**
 * Positions summary from API
 */
@Serializable
data class BotPositionsSummary(
    val count: Int,
    @SerialName("total_value")
    val totalValue: Double,
    @SerialName("total_unrealized_pnl")
    val totalUnrealizedPnl: Double
)

/**
 * Settings summary from API
 */
@Serializable
data class BotSettingsSummary(
    @SerialName("min_signal_score")
    val minSignalScore: Int,
    @SerialName("max_positions")
    val maxPositions: Int,
    @SerialName("enabled_coins")
    val enabledCoins: List<String>? = null
)

/**
 * Full bot status response from /api/v3/analysis/bot/status
 */
@Serializable
data class BotStatusResponse(
    @SerialName("is_active")
    val isActive: Boolean,
    val balance: BotBalanceInfo,
    val positions: List<BotPositionLive>,
    @SerialName("positions_summary")
    val positionsSummary: BotPositionsSummary,
    val settings: BotSettingsSummary
)
