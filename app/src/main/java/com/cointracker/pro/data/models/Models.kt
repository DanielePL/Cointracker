package com.cointracker.pro.data.models

import com.google.gson.annotations.SerializedName

data class Ticker(
    val symbol: String,
    val price: Double,
    @SerializedName("change_24h") val change24h: Double,
    @SerializedName("change_percent_24h") val changePercent24h: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val timestamp: String
)

data class OHLCV(
    val timestamp: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class TechnicalIndicators(
    val symbol: String,
    val timeframe: String,
    val timestamp: String,
    val rsi: Double?,
    @SerializedName("rsi_signal") val rsiSignal: String?,
    @SerializedName("macd_line") val macdLine: Double?,
    @SerializedName("macd_signal") val macdSignal: Double?,
    @SerializedName("macd_histogram") val macdHistogram: Double?,
    @SerializedName("macd_trend") val macdTrend: String?,
    @SerializedName("bb_upper") val bbUpper: Double?,
    @SerializedName("bb_middle") val bbMiddle: Double?,
    @SerializedName("bb_lower") val bbLower: Double?,
    @SerializedName("bb_position") val bbPosition: String?,
    @SerializedName("ema_50") val ema50: Double?,
    @SerializedName("ema_200") val ema200: Double?,
    @SerializedName("ema_trend") val emaTrend: String?,
    @SerializedName("volume_sma") val volumeSma: Double?,
    @SerializedName("volume_ratio") val volumeRatio: Double?
)

data class FearGreedIndex(
    val value: Int,
    @SerializedName("value_classification") val classification: String,
    val timestamp: String,
    @SerializedName("time_until_update") val timeUntilUpdate: String?
)

data class TradingSignal(
    val symbol: String,
    val timestamp: String,
    val signal: String,
    @SerializedName("signal_score") val signalScore: Int,
    val confidence: Double,
    val reasons: List<String>,
    @SerializedName("risk_level") val riskLevel: String,
    @SerializedName("suggested_action") val suggestedAction: String,
    @SerializedName("entry_price") val entryPrice: Double?,
    @SerializedName("stop_loss") val stopLoss: Double?,
    @SerializedName("take_profit") val takeProfit: Double?,
    val indicators: TechnicalIndicators,
    @SerializedName("fear_greed") val fearGreed: FearGreedIndex?
)

data class PortfolioResponse(
    @SerializedName("total_btc") val totalBtc: Double,
    @SerializedName("total_usdt") val totalUsdt: Double,
    val balances: List<Balance>,
    val timestamp: String
)

data class Balance(
    val currency: String,
    val free: Double,
    val used: Double,
    val total: Double
)

data class HealthResponse(
    val status: String,
    @SerializedName("binance_connected") val binanceConnected: Boolean,
    val timestamp: String
)

// ==================== V2 API Models ====================

// Authentication
data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val email: String? = null
)

data class AuthToken(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_at") val expiresAt: String
)

data class User(
    val id: String,
    val username: String,
    val email: String?,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("created_at") val createdAt: String
)

// Aggregated Sentiment
data class AggregatedSentiment(
    val symbol: String,
    @SerializedName("overall_score") val overallScore: Double,
    @SerializedName("overall_label") val overallLabel: String,
    val confidence: Double,
    @SerializedName("bullish_factors") val bullishFactors: List<String>,
    @SerializedName("bearish_factors") val bearishFactors: List<String>,
    val sources: List<SentimentSource>,
    val timestamp: String
)

data class SentimentSource(
    val source: String,
    val value: Double,
    val confidence: Double,
    @SerializedName("raw_value") val rawValue: Any?
)

// On-Chain Data
data class OnChainMetrics(
    val symbol: String,
    @SerializedName("exchange_netflow") val exchangeNetflow: Double,
    @SerializedName("exchange_reserve") val exchangeReserve: Double,
    @SerializedName("exchange_reserve_change_24h") val exchangeReserveChange24h: Double,
    @SerializedName("whale_transactions_24h") val whaleTransactions24h: Int,
    @SerializedName("whale_volume_24h") val whaleVolume24h: Double,
    @SerializedName("whale_accumulation") val whaleAccumulation: Double,
    @SerializedName("active_addresses_24h") val activeAddresses24h: Int,
    val signal: String,
    val reasons: List<String>,
    val timestamp: String
)

data class WhaleTransactionsResponse(
    val symbol: String,
    @SerializedName("min_value_usd") val minValueUsd: Int,
    val transactions: List<WhaleTransaction>
)

data class WhaleTransaction(
    @SerializedName("tx_hash") val txHash: String,
    val amount: Double,
    @SerializedName("amount_usd") val amountUsd: Double,
    @SerializedName("from_label") val fromLabel: String?,
    @SerializedName("to_label") val toLabel: String?,
    @SerializedName("is_exchange_inflow") val isExchangeInflow: Boolean,
    @SerializedName("is_exchange_outflow") val isExchangeOutflow: Boolean,
    val timestamp: String
)

// ML Signals V2
data class HybridSignal(
    val symbol: String,
    val signal: String,
    val score: Int,
    val confidence: Double,
    @SerializedName("direction_probs") val directionProbs: Map<String, Double>,
    val reasons: List<String>,
    @SerializedName("feature_importance") val featureImportance: Map<String, Double>,
    val timestamp: String
)

// Trading
data class TradingStatus(
    val enabled: Boolean,
    @SerializedName("testnet_only") val testnetOnly: Boolean,
    @SerializedName("active_positions") val activePositions: Int,
    @SerializedName("daily_trades") val dailyTrades: Int,
    @SerializedName("daily_pnl") val dailyPnl: Double,
    @SerializedName("last_trade_time") val lastTradeTime: String?
)

data class TradingConfig(
    @SerializedName("max_position_size_pct") val maxPositionSizePct: Double,
    @SerializedName("stop_loss_pct") val stopLossPct: Double,
    @SerializedName("take_profit_pct") val takeProfitPct: Double,
    @SerializedName("min_signal_score") val minSignalScore: Int,
    @SerializedName("trailing_stop_pct") val trailingStopPct: Double
)

// Backtesting
data class BacktestResult(
    val symbol: String,
    val timeframe: String,
    @SerializedName("period_days") val periodDays: Int,
    val performance: BacktestPerformance,
    @SerializedName("risk_metrics") val riskMetrics: RiskMetrics,
    @SerializedName("trade_stats") val tradeStats: TradeStats,
    @SerializedName("recent_trades") val recentTrades: List<BacktestTrade>
)

data class BacktestPerformance(
    @SerializedName("total_return_pct") val totalReturnPct: Double,
    @SerializedName("buy_hold_return_pct") val buyHoldReturnPct: Double,
    val alpha: Double,
    @SerializedName("annualized_return_pct") val annualizedReturnPct: Double
)

data class RiskMetrics(
    @SerializedName("sharpe_ratio") val sharpeRatio: Double,
    @SerializedName("sortino_ratio") val sortinoRatio: Double,
    @SerializedName("max_drawdown_pct") val maxDrawdownPct: Double,
    @SerializedName("volatility_pct") val volatilityPct: Double
)

data class TradeStats(
    @SerializedName("total_trades") val totalTrades: Int,
    @SerializedName("winning_trades") val winningTrades: Int,
    @SerializedName("losing_trades") val losingTrades: Int,
    @SerializedName("win_rate") val winRate: Double,
    @SerializedName("profit_factor") val profitFactor: Double,
    @SerializedName("avg_win_pct") val avgWinPct: Double,
    @SerializedName("avg_loss_pct") val avgLossPct: Double
)

data class BacktestTrade(
    @SerializedName("entry_time") val entryTime: String,
    @SerializedName("exit_time") val exitTime: String,
    val side: String,
    @SerializedName("entry_price") val entryPrice: Double,
    @SerializedName("exit_price") val exitPrice: Double,
    @SerializedName("pnl_pct") val pnlPct: Double,
    @SerializedName("exit_reason") val exitReason: String
)

// WebSocket Messages
data class WebSocketMessage(
    val type: String,
    val symbol: String? = null,
    val price: Double? = null,
    val change24h: Double? = null,
    val volume: Double? = null,
    val timestamp: String? = null,
    val message: String? = null
)
