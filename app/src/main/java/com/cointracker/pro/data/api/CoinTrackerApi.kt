package com.cointracker.pro.data.api

import com.cointracker.pro.data.models.*
import retrofit2.http.*

interface CoinTrackerApi {

    // ==================== V1 API ====================

    @GET("api/v1/health")
    suspend fun getHealth(): HealthResponse

    @GET("api/v1/ticker/{symbol}")
    suspend fun getTicker(
        @Path("symbol") symbol: String
    ): Ticker

    @GET("api/v1/ohlcv/{symbol}")
    suspend fun getOHLCV(
        @Path("symbol") symbol: String,
        @Query("timeframe") timeframe: String = "1h",
        @Query("limit") limit: Int = 100
    ): List<OHLCV>

    @GET("api/v1/indicators/{symbol}")
    suspend fun getIndicators(
        @Path("symbol") symbol: String,
        @Query("timeframe") timeframe: String = "1h"
    ): TechnicalIndicators

    @GET("api/v1/fear-greed")
    suspend fun getFearGreed(): FearGreedIndex

    @GET("api/v1/signals/{symbol}")
    suspend fun getSignal(
        @Path("symbol") symbol: String
    ): TradingSignal

    @GET("api/v1/portfolio")
    suspend fun getPortfolio(): PortfolioResponse

    // ==================== V2 API ====================

    // Authentication
    @POST("api/v2/auth/register")
    suspend fun register(@Body request: RegisterRequest): User

    @POST("api/v2/auth/login")
    suspend fun login(@Body request: LoginRequest): AuthToken

    @GET("api/v2/auth/me")
    suspend fun getCurrentUser(@Header("Authorization") token: String): User

    // Aggregated Sentiment
    @GET("api/v2/sentiment/aggregated/{symbol}")
    suspend fun getAggregatedSentiment(
        @Path("symbol") symbol: String
    ): AggregatedSentiment

    // On-Chain Data
    @GET("api/v2/onchain/{symbol}")
    suspend fun getOnChainMetrics(
        @Path("symbol") symbol: String
    ): OnChainMetrics

    @GET("api/v2/whales/{symbol}")
    suspend fun getWhaleTransactions(
        @Path("symbol") symbol: String,
        @Query("min_value_usd") minValueUsd: Int = 10_000_000
    ): WhaleTransactionsResponse

    // ML Signals V2
    @GET("api/v2/signals-v2/{symbol}")
    suspend fun getHybridSignal(
        @Path("symbol") symbol: String
    ): HybridSignal

    // Trading (requires auth)
    @GET("api/v2/trading/status")
    suspend fun getTradingStatus(
        @Header("Authorization") token: String
    ): TradingStatus

    @POST("api/v2/trading/enable")
    suspend fun enableTrading(
        @Header("Authorization") token: String,
        @Query("enabled") enabled: Boolean = true,
        @Query("testnet_only") testnetOnly: Boolean = true
    ): Map<String, Any>

    @POST("api/v2/trading/config")
    suspend fun updateTradingConfig(
        @Header("Authorization") token: String,
        @Query("max_position_size_pct") maxPositionSizePct: Double? = null,
        @Query("stop_loss_pct") stopLossPct: Double? = null,
        @Query("take_profit_pct") takeProfitPct: Double? = null,
        @Query("min_signal_score") minSignalScore: Int? = null
    ): Map<String, Any>

    // Backtesting (requires auth)
    @POST("api/v2/backtest")
    suspend fun runBacktest(
        @Header("Authorization") token: String,
        @Query("symbol") symbol: String = "BTC/USDT",
        @Query("timeframe") timeframe: String = "1h",
        @Query("days") days: Int = 90,
        @Query("position_size_pct") positionSizePct: Double = 10.0,
        @Query("stop_loss_pct") stopLossPct: Double = 3.0,
        @Query("take_profit_pct") takeProfitPct: Double = 6.0
    ): BacktestResult
}
