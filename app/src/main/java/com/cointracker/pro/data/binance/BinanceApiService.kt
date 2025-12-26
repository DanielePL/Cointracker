package com.cointracker.pro.data.binance

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Binance REST API Interface
 * Docs: https://binance-docs.github.io/apidocs/spot/en/
 */
interface BinanceApiService {

    // ============================================
    // Public Endpoints (no auth required)
    // ============================================

    /**
     * Test connectivity
     */
    @GET("api/v3/ping")
    suspend fun ping()

    /**
     * Get server time
     */
    @GET("api/v3/time")
    suspend fun getServerTime(): BinanceServerTime

    /**
     * Get exchange info (trading pairs, etc.)
     */
    @GET("api/v3/exchangeInfo")
    suspend fun getExchangeInfo(): BinanceExchangeInfo

    /**
     * Get current price for a symbol
     */
    @GET("api/v3/ticker/price")
    suspend fun getPrice(
        @Query("symbol") symbol: String
    ): BinanceTicker

    /**
     * Get current prices for all symbols
     */
    @GET("api/v3/ticker/price")
    suspend fun getAllPrices(): List<BinanceTicker>

    /**
     * Get 24hr ticker for a symbol
     */
    @GET("api/v3/ticker/24hr")
    suspend fun get24hrTicker(
        @Query("symbol") symbol: String
    ): Binance24hrTicker

    /**
     * Get 24hr tickers for all symbols
     */
    @GET("api/v3/ticker/24hr")
    suspend fun getAll24hrTickers(): List<Binance24hrTicker>

    /**
     * Get klines/candlestick data
     */
    @GET("api/v3/klines")
    suspend fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,  // 1m, 5m, 15m, 1h, 4h, 1d, etc.
        @Query("limit") limit: Int = 500
    ): List<List<Any>>

    // ============================================
    // Private Endpoints (auth required)
    // ============================================

    /**
     * Get account information including balances
     * Requires API key + signature
     */
    @GET("api/v3/account")
    suspend fun getAccountInfo(
        @Header("X-MBX-APIKEY") apiKey: String,
        @Query("timestamp") timestamp: Long,
        @Query("signature") signature: String
    ): BinanceAccountInfo
}
