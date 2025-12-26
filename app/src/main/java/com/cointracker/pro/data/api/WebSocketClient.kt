package com.cointracker.pro.data.api

import android.util.Log
import com.cointracker.pro.data.models.WebSocketMessage
import com.google.gson.Gson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for real-time price updates from the backend
 */
class CoinTrackerWebSocket(
    private val baseUrl: String = "ws://10.0.2.2:8000"
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val messageChannel = Channel<WebSocketMessage>(Channel.BUFFERED)
    private val subscribedSymbols = mutableSetOf<String>()

    val messages: Flow<WebSocketMessage> = messageChannel.receiveAsFlow()

    private var isConnected = false

    fun connect() {
        if (isConnected) return

        val request = Request.Builder()
            .url("$baseUrl/api/v2/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true

                // Re-subscribe to previously subscribed symbols
                subscribedSymbols.forEach { symbol ->
                    subscribeInternal(webSocket, symbol)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = gson.fromJson(text, WebSocketMessage::class.java)
                    messageChannel.trySend(message)

                    when (message.type) {
                        "ticker" -> {
                            Log.d(TAG, "Ticker: ${message.symbol} = ${message.price}")
                        }
                        "connected" -> {
                            Log.d(TAG, "Server: ${message.message}")
                        }
                        "subscribed" -> {
                            Log.d(TAG, "Subscribed to ${message.symbol}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                isConnected = false

                // Try to reconnect after a delay
                Thread.sleep(5000)
                connect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
            }
        })
    }

    fun subscribe(symbol: String) {
        subscribedSymbols.add(symbol)
        webSocket?.let { ws ->
            subscribeInternal(ws, symbol)
        }
    }

    private fun subscribeInternal(ws: WebSocket, symbol: String) {
        val message = """{"action": "subscribe", "symbol": "$symbol"}"""
        ws.send(message)
    }

    fun unsubscribe(symbol: String) {
        subscribedSymbols.remove(symbol)
        webSocket?.let { ws ->
            val message = """{"action": "unsubscribe", "symbol": "$symbol"}"""
            ws.send(message)
        }
    }

    fun ping() {
        webSocket?.let { ws ->
            ws.send("""{"action": "ping"}""")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        isConnected = false
    }

    fun isConnected(): Boolean = isConnected

    companion object {
        private const val TAG = "CoinTrackerWS"

        @Volatile
        private var INSTANCE: CoinTrackerWebSocket? = null

        fun getInstance(baseUrl: String = "ws://10.0.2.2:8000"): CoinTrackerWebSocket {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CoinTrackerWebSocket(baseUrl).also { INSTANCE = it }
            }
        }
    }
}
