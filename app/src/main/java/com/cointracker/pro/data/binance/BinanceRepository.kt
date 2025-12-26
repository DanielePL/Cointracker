package com.cointracker.pro.data.binance

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Repository for Binance API operations
 * Handles both public and authenticated endpoints
 */
class BinanceRepository(context: Context) {

    private val config = BinanceConfig.getInstance(context)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val api: BinanceApiService by lazy {
        Retrofit.Builder()
            .baseUrl(config.getBaseUrl() + "/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceApiService::class.java)
    }

    // Price cache for quick lookups
    private var priceCache: Map<String, Double> = emptyMap()
    private var tickerCache: Map<String, Binance24hrTicker> = emptyMap()

    /**
     * Test connection to Binance
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            api.ping()
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get all current prices
     */
    suspend fun getAllPrices(): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        try {
            val prices = api.getAllPrices()
            val priceMap = prices.associate { it.symbol to it.priceDouble }
            priceCache = priceMap
            Result.success(priceMap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get 24hr ticker data for all symbols
     */
    suspend fun getAll24hrTickers(): Result<List<Binance24hrTicker>> = withContext(Dispatchers.IO) {
        try {
            val tickers = api.getAll24hrTickers()
            tickerCache = tickers.associateBy { it.symbol }
            Result.success(tickers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get account info with balances (requires API keys)
     */
    suspend fun getAccountInfo(): Result<BinanceAccountInfo> = withContext(Dispatchers.IO) {
        try {
            val apiKey = config.getApiKey()
                ?: return@withContext Result.failure(Exception("API Key not configured"))
            val secretKey = config.getSecretKey()
                ?: return@withContext Result.failure(Exception("Secret Key not configured"))

            val timestamp = System.currentTimeMillis()
            val signature = BinanceSignature.signAccountRequest(timestamp, secretKey)

            val accountInfo = api.getAccountInfo(
                apiKey = apiKey,
                timestamp = timestamp,
                signature = signature
            )
            Result.success(accountInfo)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get full portfolio summary with USD values
     */
    suspend fun getPortfolioSummary(): Result<PortfolioSummary> = withContext(Dispatchers.IO) {
        try {
            // Fetch account info and prices in parallel
            coroutineScope {
                val accountDeferred = async { getAccountInfo() }
                val tickersDeferred = async { getAll24hrTickers() }

                val accountResult = accountDeferred.await()
                val tickersResult = tickersDeferred.await()

                if (accountResult.isFailure) {
                    return@coroutineScope Result.failure<PortfolioSummary>(
                        accountResult.exceptionOrNull() ?: Exception("Failed to get account")
                    )
                }

                if (tickersResult.isFailure) {
                    return@coroutineScope Result.failure<PortfolioSummary>(
                        tickersResult.exceptionOrNull() ?: Exception("Failed to get prices")
                    )
                }

                val account = accountResult.getOrThrow()
                val tickers = tickersResult.getOrThrow()
                val tickerMap = tickers.associateBy { it.symbol }

                // Filter balances with non-zero amounts
                val nonZeroBalances = account.balances.filter { it.total > 0.0001 }

                // Calculate portfolio assets
                val assets = mutableListOf<PortfolioAsset>()
                var totalValueUsd = 0.0

                for (balance in nonZeroBalances) {
                    val symbol = balance.asset
                    val amount = balance.total

                    // Get USD value
                    val (price, change24h) = when {
                        symbol == "USDT" || symbol == "USDC" || symbol == "BUSD" || symbol == "FDUSD" -> {
                            1.0 to 0.0
                        }
                        symbol == "USD" -> {
                            1.0 to 0.0
                        }
                        else -> {
                            // Try SYMBOL+USDT pair first
                            val usdtTicker = tickerMap["${symbol}USDT"]
                            if (usdtTicker != null) {
                                usdtTicker.lastPriceDouble to usdtTicker.priceChangePercentDouble
                            } else {
                                // Try SYMBOL+BUSD or other quote currencies
                                val busdTicker = tickerMap["${symbol}BUSD"]
                                if (busdTicker != null) {
                                    busdTicker.lastPriceDouble to busdTicker.priceChangePercentDouble
                                } else {
                                    // Try via BTC conversion
                                    val btcTicker = tickerMap["${symbol}BTC"]
                                    val btcUsdt = tickerMap["BTCUSDT"]
                                    if (btcTicker != null && btcUsdt != null) {
                                        val priceInBtc = btcTicker.lastPriceDouble
                                        val btcPrice = btcUsdt.lastPriceDouble
                                        (priceInBtc * btcPrice) to btcTicker.priceChangePercentDouble
                                    } else {
                                        0.0 to 0.0
                                    }
                                }
                            }
                        }
                    }

                    val valueUsd = amount * price
                    totalValueUsd += valueUsd

                    assets.add(
                        PortfolioAsset(
                            symbol = symbol,
                            name = CoinNames.getName(symbol),
                            amount = amount,
                            lockedAmount = balance.lockedAmount,
                            currentPrice = price,
                            priceChange24h = change24h,
                            valueUsd = valueUsd,
                            percentOfPortfolio = 0.0 // Will be calculated after
                        )
                    )
                }

                // Calculate percentages and sort by value
                val assetsWithPercent = assets.map { asset ->
                    asset.copy(
                        percentOfPortfolio = if (totalValueUsd > 0) {
                            (asset.valueUsd / totalValueUsd) * 100
                        } else 0.0
                    )
                }.sortedByDescending { it.valueUsd }

                // Calculate 24h change (weighted average)
                val totalChange24h = assetsWithPercent.sumOf { asset ->
                    (asset.priceChange24h / 100) * asset.valueUsd
                }
                val totalChangePercent = if (totalValueUsd > 0) {
                    (totalChange24h / (totalValueUsd - totalChange24h)) * 100
                } else 0.0

                Result.success(
                    PortfolioSummary(
                        totalValueUsd = totalValueUsd,
                        totalChange24h = totalChange24h,
                        totalChangePercent24h = totalChangePercent,
                        assets = assetsWithPercent,
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get kline/candlestick data
     */
    suspend fun getKlines(
        symbol: String,
        interval: String = "1h",
        limit: Int = 100
    ): Result<List<BinanceKline>> = withContext(Dispatchers.IO) {
        try {
            val rawKlines = api.getKlines(symbol, interval, limit)
            val klines = rawKlines.map { kline ->
                BinanceKline(
                    openTime = (kline[0] as Double).toLong(),
                    open = (kline[1] as String).toDouble(),
                    high = (kline[2] as String).toDouble(),
                    low = (kline[3] as String).toDouble(),
                    close = (kline[4] as String).toDouble(),
                    volume = (kline[5] as String).toDouble(),
                    closeTime = (kline[6] as Double).toLong(),
                    quoteVolume = (kline[7] as String).toDouble(),
                    trades = (kline[8] as Double).toInt(),
                    takerBuyBaseVolume = (kline[9] as String).toDouble(),
                    takerBuyQuoteVolume = (kline[10] as String).toDouble()
                )
            }
            Result.success(klines)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get top gainers from 24hr tickers
     */
    suspend fun getTopGainers(limit: Int = 10): Result<List<Binance24hrTicker>> = withContext(Dispatchers.IO) {
        try {
            val tickers = if (tickerCache.isNotEmpty()) {
                tickerCache.values.toList()
            } else {
                getAll24hrTickers().getOrThrow()
            }

            // Filter only USDT pairs and sort by 24h change
            val topGainers = tickers
                .filter { it.symbol.endsWith("USDT") && it.volumeDouble > 1_000_000 }
                .sortedByDescending { it.priceChangePercentDouble }
                .take(limit)

            Result.success(topGainers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get top losers from 24hr tickers
     */
    suspend fun getTopLosers(limit: Int = 10): Result<List<Binance24hrTicker>> = withContext(Dispatchers.IO) {
        try {
            val tickers = if (tickerCache.isNotEmpty()) {
                tickerCache.values.toList()
            } else {
                getAll24hrTickers().getOrThrow()
            }

            // Filter only USDT pairs and sort by 24h change (ascending = losers first)
            val topLosers = tickers
                .filter { it.symbol.endsWith("USDT") && it.volumeDouble > 1_000_000 }
                .sortedBy { it.priceChangePercentDouble }
                .take(limit)

            Result.success(topLosers)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
