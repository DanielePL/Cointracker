package com.cointracker.pro.data.binance

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Binance WebSocket for live price updates
 * Uses the public streams (no auth required)
 */
class BinanceWebSocket {

    companion object {
        private const val TAG = "BinanceWebSocket"
        private const val WS_BASE_URL = "wss://stream.binance.com:9443/ws"
        private const val TESTNET_WS_URL = "wss://testnet.binance.vision/ws"

        @Volatile
        private var instance: BinanceWebSocket? = null

        fun getInstance(): BinanceWebSocket {
            return instance ?: synchronized(this) {
                instance ?: BinanceWebSocket().also { instance = it }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var currentSymbols = mutableSetOf<String>()

    // Flow for ticker updates
    private val _tickerUpdates = MutableSharedFlow<TickerUpdate>(
        replay = 1,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val tickerUpdates: SharedFlow<TickerUpdate> = _tickerUpdates.asSharedFlow()

    // Flow for connection state
    private val _connectionState = MutableSharedFlow<Boolean>(replay = 1)
    val connectionState: SharedFlow<Boolean> = _connectionState.asSharedFlow()

    /**
     * Connect to multiple symbol ticker streams
     */
    fun connect(symbols: List<String>) {
        if (isConnected && currentSymbols == symbols.toSet()) {
            Log.d(TAG, "Already connected to same symbols")
            return
        }

        disconnect()
        currentSymbols = symbols.toMutableSet()

        // Build combined stream URL
        // Format: wss://stream.binance.com:9443/stream?streams=btcusdt@ticker/ethusdt@ticker
        val streams = symbols.joinToString("/") { symbol ->
            "${symbol.lowercase()}@ticker"
        }
        val url = "wss://stream.binance.com:9443/stream?streams=$streams"

        Log.d(TAG, "Connecting to: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                _connectionState.tryEmit(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    // Combined stream format: {"stream":"btcusdt@ticker","data":{...}}
                    val streamMessage = gson.fromJson(text, StreamMessage::class.java)
                    streamMessage.data?.let { ticker ->
                        val update = TickerUpdate(
                            symbol = ticker.symbol,
                            price = ticker.lastPrice.toDoubleOrNull() ?: 0.0,
                            priceChange = ticker.priceChange.toDoubleOrNull() ?: 0.0,
                            priceChangePercent = ticker.priceChangePercent.toDoubleOrNull() ?: 0.0,
                            high = ticker.highPrice.toDoubleOrNull() ?: 0.0,
                            low = ticker.lowPrice.toDoubleOrNull() ?: 0.0,
                            volume = ticker.volume.toDoubleOrNull() ?: 0.0,
                            quoteVolume = ticker.quoteVolume.toDoubleOrNull() ?: 0.0
                        )
                        _tickerUpdates.tryEmit(update)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                _connectionState.tryEmit(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                isConnected = false
                _connectionState.tryEmit(false)

                // Auto-reconnect after 5 seconds
                Thread.sleep(5000)
                if (currentSymbols.isNotEmpty()) {
                    connect(currentSymbols.toList())
                }
            }
        })
    }

    /**
     * Connect to a single symbol
     */
    fun connectToSymbol(symbol: String) {
        connect(listOf(symbol))
    }

    /**
     * Add a symbol to the current connection
     */
    fun addSymbol(symbol: String) {
        val newSymbols = currentSymbols.toMutableSet()
        newSymbols.add(symbol)
        connect(newSymbols.toList())
    }

    /**
     * Disconnect from WebSocket
     */
    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
        _connectionState.tryEmit(false)
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected
}

// WebSocket message models
data class StreamMessage(
    val stream: String?,
    val data: WsTicker?
)

data class WsTicker(
    @SerializedName("e") val eventType: String,      // "24hrTicker"
    @SerializedName("E") val eventTime: Long,        // Event time
    @SerializedName("s") val symbol: String,         // Symbol
    @SerializedName("p") val priceChange: String,    // Price change
    @SerializedName("P") val priceChangePercent: String, // Price change percent
    @SerializedName("w") val weightedAvgPrice: String,
    @SerializedName("c") val lastPrice: String,      // Last price
    @SerializedName("Q") val lastQty: String,        // Last quantity
    @SerializedName("o") val openPrice: String,      // Open price
    @SerializedName("h") val highPrice: String,      // High price
    @SerializedName("l") val lowPrice: String,       // Low price
    @SerializedName("v") val volume: String,         // Base volume
    @SerializedName("q") val quoteVolume: String,    // Quote volume
    @SerializedName("O") val openTime: Long,
    @SerializedName("C") val closeTime: Long,
    @SerializedName("n") val trades: Int             // Number of trades
)

data class TickerUpdate(
    val symbol: String,
    val price: Double,
    val priceChange: Double,
    val priceChangePercent: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val quoteVolume: Double
)
