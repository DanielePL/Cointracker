package com.cointracker.pro.data.binance

import com.google.gson.annotations.SerializedName

/**
 * Binance API Response Models
 */

// Account Information
data class BinanceAccountInfo(
    @SerializedName("makerCommission") val makerCommission: Int,
    @SerializedName("takerCommission") val takerCommission: Int,
    @SerializedName("canTrade") val canTrade: Boolean,
    @SerializedName("canWithdraw") val canWithdraw: Boolean,
    @SerializedName("canDeposit") val canDeposit: Boolean,
    @SerializedName("updateTime") val updateTime: Long,
    @SerializedName("accountType") val accountType: String,
    @SerializedName("balances") val balances: List<BinanceBalance>,
    @SerializedName("permissions") val permissions: List<String>
)

data class BinanceBalance(
    @SerializedName("asset") val asset: String,
    @SerializedName("free") val free: String,
    @SerializedName("locked") val locked: String
) {
    val total: Double get() = (free.toDoubleOrNull() ?: 0.0) + (locked.toDoubleOrNull() ?: 0.0)
    val freeAmount: Double get() = free.toDoubleOrNull() ?: 0.0
    val lockedAmount: Double get() = locked.toDoubleOrNull() ?: 0.0
}

// Price Ticker
data class BinanceTicker(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("price") val price: String
) {
    val priceDouble: Double get() = price.toDoubleOrNull() ?: 0.0
}

// 24hr Ticker Statistics
data class Binance24hrTicker(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("priceChange") val priceChange: String,
    @SerializedName("priceChangePercent") val priceChangePercent: String,
    @SerializedName("weightedAvgPrice") val weightedAvgPrice: String,
    @SerializedName("lastPrice") val lastPrice: String,
    @SerializedName("lastQty") val lastQty: String,
    @SerializedName("openPrice") val openPrice: String,
    @SerializedName("highPrice") val highPrice: String,
    @SerializedName("lowPrice") val lowPrice: String,
    @SerializedName("volume") val volume: String,
    @SerializedName("quoteVolume") val quoteVolume: String,
    @SerializedName("openTime") val openTime: Long,
    @SerializedName("closeTime") val closeTime: Long,
    @SerializedName("count") val count: Int
) {
    val priceChangePercentDouble: Double get() = priceChangePercent.toDoubleOrNull() ?: 0.0
    val lastPriceDouble: Double get() = lastPrice.toDoubleOrNull() ?: 0.0
    val highPriceDouble: Double get() = highPrice.toDoubleOrNull() ?: 0.0
    val lowPriceDouble: Double get() = lowPrice.toDoubleOrNull() ?: 0.0
    val volumeDouble: Double get() = volume.toDoubleOrNull() ?: 0.0
}

// Kline/Candlestick data
data class BinanceKline(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val quoteVolume: Double,
    val trades: Int,
    val takerBuyBaseVolume: Double,
    val takerBuyQuoteVolume: Double
)

// Exchange Info
data class BinanceExchangeInfo(
    @SerializedName("timezone") val timezone: String,
    @SerializedName("serverTime") val serverTime: Long,
    @SerializedName("symbols") val symbols: List<BinanceSymbol>
)

data class BinanceSymbol(
    @SerializedName("symbol") val symbol: String,
    @SerializedName("status") val status: String,
    @SerializedName("baseAsset") val baseAsset: String,
    @SerializedName("quoteAsset") val quoteAsset: String,
    @SerializedName("isSpotTradingAllowed") val isSpotTradingAllowed: Boolean
)

// Server Time
data class BinanceServerTime(
    @SerializedName("serverTime") val serverTime: Long
)

// ============================================
// App-specific models (processed data)
// ============================================

data class PortfolioAsset(
    val symbol: String,
    val name: String,
    val amount: Double,
    val lockedAmount: Double,
    val currentPrice: Double,
    val priceChange24h: Double,
    val valueUsd: Double,
    val percentOfPortfolio: Double
)

data class PortfolioSummary(
    val totalValueUsd: Double,
    val totalChange24h: Double,
    val totalChangePercent24h: Double,
    val assets: List<PortfolioAsset>,
    val lastUpdated: Long
)

// Coin name mapping
object CoinNames {
    private val names = mapOf(
        "BTC" to "Bitcoin",
        "ETH" to "Ethereum",
        "BNB" to "Binance Coin",
        "USDT" to "Tether",
        "USDC" to "USD Coin",
        "XRP" to "Ripple",
        "ADA" to "Cardano",
        "DOGE" to "Dogecoin",
        "SOL" to "Solana",
        "DOT" to "Polkadot",
        "MATIC" to "Polygon",
        "LTC" to "Litecoin",
        "SHIB" to "Shiba Inu",
        "TRX" to "TRON",
        "AVAX" to "Avalanche",
        "LINK" to "Chainlink",
        "ATOM" to "Cosmos",
        "UNI" to "Uniswap",
        "XMR" to "Monero",
        "ETC" to "Ethereum Classic",
        "XLM" to "Stellar",
        "BCH" to "Bitcoin Cash",
        "APT" to "Aptos",
        "FIL" to "Filecoin",
        "NEAR" to "NEAR Protocol",
        "ARB" to "Arbitrum",
        "OP" to "Optimism",
        "PEPE" to "Pepe",
        "INJ" to "Injective",
        "SUI" to "Sui",
        "IMX" to "Immutable X",
        "RNDR" to "Render",
        "FET" to "Fetch.ai",
        "SAND" to "The Sandbox",
        "MANA" to "Decentraland",
        "AAVE" to "Aave",
        "GRT" to "The Graph",
        "ALGO" to "Algorand",
        "FTM" to "Fantom",
        "THETA" to "Theta Network",
        "AXS" to "Axie Infinity",
        "VET" to "VeChain",
        "EGLD" to "MultiversX",
        "HBAR" to "Hedera",
        "ICP" to "Internet Computer",
        "FDUSD" to "First Digital USD",
        "EUR" to "Euro",
        "USD" to "US Dollar",
        "BUSD" to "Binance USD"
    )

    fun getName(symbol: String): String = names[symbol] ?: symbol
}
