package com.cointracker.pro.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.cointracker.pro.data.supabase.SupabaseModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging Service
 * Handles incoming push notifications and token refresh
 */
class CoinTrackerMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
    }

    /**
     * Called when FCM token is generated or refreshed
     * Store the token in Supabase for the backend to use
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")

        // Store token in Supabase
        CoroutineScope(Dispatchers.IO).launch {
            try {
                storeFcmToken(token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to store FCM token", e)
            }
        }
    }

    /**
     * Called when a message is received
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Message received from: ${message.from}")

        // Handle data payload
        val data = message.data
        if (data.isNotEmpty()) {
            handleDataMessage(data)
        }

        // Handle notification payload (when app is in foreground)
        message.notification?.let { notification ->
            Log.d(TAG, "Notification: ${notification.title} - ${notification.body}")
        }
    }

    /**
     * Handle custom data messages from backend
     */
    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"] ?: return

        when (type) {
            "TRADE_EXECUTED" -> {
                val action = data["action"] ?: "BUY"
                val coin = data["coin"] ?: "Unknown"
                val amount = data["amount"]?.toDoubleOrNull() ?: 0.0
                val price = data["price"]?.toDoubleOrNull() ?: 0.0

                NotificationHelper.showTradeNotification(
                    context = applicationContext,
                    action = action,
                    coin = coin,
                    amount = amount,
                    price = price
                )
            }

            "PROFIT_LOSS" -> {
                val coin = data["coin"] ?: "Unknown"
                val pnl = data["pnl"]?.toDoubleOrNull() ?: 0.0
                val pnlPercent = data["pnl_percent"]?.toDoubleOrNull() ?: 0.0
                val reason = data["reason"] ?: "CLOSED"

                NotificationHelper.showProfitLossNotification(
                    context = applicationContext,
                    coin = coin,
                    pnl = pnl,
                    pnlPercent = pnlPercent,
                    reason = reason
                )
            }

            "STRONG_SIGNAL" -> {
                val coin = data["coin"] ?: "Unknown"
                val signal = data["signal"] ?: "STRONG_BUY"
                val score = data["score"]?.toIntOrNull() ?: 0

                NotificationHelper.showSignalNotification(
                    context = applicationContext,
                    coin = coin,
                    signal = signal,
                    score = score
                )
            }
        }
    }

    /**
     * Store FCM token in Supabase for backend to use
     */
    private suspend fun storeFcmToken(token: String) {
        try {
            val database = SupabaseModule.database

            // Upsert token (insert or update)
            database.from("fcm_tokens").upsert(
                mapOf(
                    "token" to token,
                    "platform" to "android",
                    "updated_at" to java.time.Instant.now().toString()
                )
            )

            Log.d(TAG, "FCM token stored successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store FCM token in Supabase", e)
        }
    }
}
