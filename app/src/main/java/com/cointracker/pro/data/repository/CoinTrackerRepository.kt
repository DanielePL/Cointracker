package com.cointracker.pro.data.repository

import com.cointracker.pro.data.api.ApiClient
import com.cointracker.pro.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CoinTrackerRepository {

    private val api = ApiClient.api

    suspend fun getHealth(): Result<HealthResponse> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getHealth())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTicker(symbol: String): Result<Ticker> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getTicker(symbol))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOHLCV(
        symbol: String,
        timeframe: String = "1h",
        limit: Int = 100
    ): Result<List<OHLCV>> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getOHLCV(symbol, timeframe, limit))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getIndicators(
        symbol: String,
        timeframe: String = "1h"
    ): Result<TechnicalIndicators> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getIndicators(symbol, timeframe))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getFearGreed(): Result<FearGreedIndex> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getFearGreed())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSignal(symbol: String): Result<TradingSignal> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getSignal(symbol))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPortfolio(): Result<PortfolioResponse> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getPortfolio())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================== V2 API ====================

    suspend fun getAggregatedSentiment(symbol: String): Result<AggregatedSentiment> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getAggregatedSentiment(symbol))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOnChainMetrics(symbol: String): Result<OnChainMetrics> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getOnChainMetrics(symbol))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getWhaleTransactions(
        symbol: String,
        minValueUsd: Int = 10_000_000
    ): Result<WhaleTransactionsResponse> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getWhaleTransactions(symbol, minValueUsd))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getHybridSignal(symbol: String): Result<HybridSignal> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getHybridSignal(symbol))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTradingStatus(token: String): Result<TradingStatus> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.getTradingStatus("Bearer $token"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun runBacktest(
        token: String,
        symbol: String = "BTC/USDT",
        timeframe: String = "1h",
        days: Int = 90,
        positionSizePct: Double = 10.0,
        stopLossPct: Double = 3.0,
        takeProfitPct: Double = 6.0
    ): Result<BacktestResult> = withContext(Dispatchers.IO) {
        try {
            Result.success(api.runBacktest(
                token = "Bearer $token",
                symbol = symbol,
                timeframe = timeframe,
                days = days,
                positionSizePct = positionSizePct,
                stopLossPct = stopLossPct,
                takeProfitPct = takeProfitPct
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
