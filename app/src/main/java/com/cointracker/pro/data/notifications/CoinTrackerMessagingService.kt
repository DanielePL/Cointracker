package com.cointracker.pro.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cointracker.pro.MainActivity
import com.cointracker.pro.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging Service for handling push notifications
 *
 * Notification types:
 * - trading_signal: ML trading signal alerts (BUY/SELL)
 * - whale_alert: Large transaction detected
 * - price_alert: Price threshold crossed
 * - sentiment_shift: Major sentiment change detected
 */
class CoinTrackerMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"

        // Notification Channels
        const val CHANNEL_TRADING_SIGNALS = "trading_signals"
        const val CHANNEL_WHALE_ALERTS = "whale_alerts"
        const val CHANNEL_PRICE_ALERTS = "price_alerts"
        const val CHANNEL_SENTIMENT = "sentiment_alerts"
        const val CHANNEL_GENERAL = "general"
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM Token refreshed: $token")
        // Send token to backend for push notification targeting
        sendTokenToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "Message received from: ${remoteMessage.from}")

        // Handle data payload
        remoteMessage.data.isNotEmpty().let {
            Log.d(TAG, "Message data: ${remoteMessage.data}")
            handleDataMessage(remoteMessage.data)
        }

        // Handle notification payload (when app is in foreground)
        remoteMessage.notification?.let {
            Log.d(TAG, "Notification: ${it.title} - ${it.body}")
            showNotification(
                title = it.title ?: "CoinTracker Pro",
                body = it.body ?: "",
                channelId = CHANNEL_GENERAL,
                data = remoteMessage.data
            )
        }
    }

    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"] ?: return
        val title = data["title"] ?: "CoinTracker Pro"
        val body = data["body"] ?: ""
        val symbol = data["symbol"]
        val score = data["score"]?.toIntOrNull()
        val direction = data["direction"]

        val channelId = when (type) {
            "trading_signal" -> CHANNEL_TRADING_SIGNALS
            "whale_alert" -> CHANNEL_WHALE_ALERTS
            "price_alert" -> CHANNEL_PRICE_ALERTS
            "sentiment_shift" -> CHANNEL_SENTIMENT
            else -> CHANNEL_GENERAL
        }

        // Build enhanced notification based on type
        val enhancedTitle = when (type) {
            "trading_signal" -> {
                val emoji = when (direction) {
                    "BUY", "STRONG_BUY" -> "📈"
                    "SELL", "STRONG_SELL" -> "📉"
                    else -> "📊"
                }
                "$emoji $title"
            }
            "whale_alert" -> "🐋 $title"
            "price_alert" -> "💰 $title"
            "sentiment_shift" -> "🎭 $title"
            else -> title
        }

        showNotification(
            title = enhancedTitle,
            body = body,
            channelId = channelId,
            data = data
        )
    }

    private fun showNotification(
        title: String,
        body: String,
        channelId: String,
        data: Map<String, String> = emptyMap()
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Pass data to activity
            data.forEach { (key, value) ->
                putExtra(key, value)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use app icon for now
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

        // Add color based on channel
        when (channelId) {
            CHANNEL_TRADING_SIGNALS -> {
                val color = if (data["direction"]?.contains("BUY") == true) {
                    0xFF00E676.toInt() // BullishGreen
                } else {
                    0xFFFF5252.toInt() // BearishRed
                }
                notificationBuilder.setColor(color)
            }
            CHANNEL_WHALE_ALERTS -> notificationBuilder.setColor(0xFF00D4FF.toInt()) // ElectricBlue
            CHANNEL_PRICE_ALERTS -> notificationBuilder.setColor(0xFFFF6B35.toInt()) // AccentOrange
            CHANNEL_SENTIMENT -> notificationBuilder.setColor(0xFFFFD740.toInt()) // NeutralYellow
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (required for Android O+)
        createNotificationChannel(notificationManager, channelId)

        notificationManager.notify(
            System.currentTimeMillis().toInt(),
            notificationBuilder.build()
        )
    }

    private fun createNotificationChannel(
        notificationManager: NotificationManager,
        channelId: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (name, description, importance) = when (channelId) {
                CHANNEL_TRADING_SIGNALS -> Triple(
                    "Trading Signals",
                    "ML-powered BUY/SELL signals with reasoning",
                    NotificationManager.IMPORTANCE_HIGH
                )
                CHANNEL_WHALE_ALERTS -> Triple(
                    "Whale Alerts",
                    "Large cryptocurrency transactions detected",
                    NotificationManager.IMPORTANCE_HIGH
                )
                CHANNEL_PRICE_ALERTS -> Triple(
                    "Price Alerts",
                    "Price threshold notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                CHANNEL_SENTIMENT -> Triple(
                    "Sentiment Shifts",
                    "Major market sentiment changes",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                else -> Triple(
                    "General",
                    "General notifications",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            }

            val channel = NotificationChannel(channelId, name, importance).apply {
                this.description = description
                enableVibration(true)
                if (channelId == CHANNEL_TRADING_SIGNALS || channelId == CHANNEL_WHALE_ALERTS) {
                    enableLights(true)
                }
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendTokenToServer(token: String) {
        // TODO: Send token to backend via API
        // This will be used to target push notifications to specific devices
        Log.d(TAG, "Token to send to server: $token")

        // Save token locally for now
        getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }
}

/**
 * Helper object to manage FCM tokens and subscriptions
 */
object NotificationManager {

    fun getStoredToken(context: Context): String? {
        return context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            .getString("fcm_token", null)
    }

    fun subscribeToSymbol(symbol: String) {
        // Subscribe to symbol-specific topics
        val topic = symbol.replace("/", "_").lowercase()
        com.google.firebase.messaging.FirebaseMessaging.getInstance()
            .subscribeToTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("FCM", "Subscribed to $topic")
                }
            }
    }

    fun unsubscribeFromSymbol(symbol: String) {
        val topic = symbol.replace("/", "_").lowercase()
        com.google.firebase.messaging.FirebaseMessaging.getInstance()
            .unsubscribeFromTopic(topic)
    }

    fun subscribeToWhaleAlerts() {
        com.google.firebase.messaging.FirebaseMessaging.getInstance()
            .subscribeToTopic("whale_alerts")
    }

    fun subscribeToTradingSignals() {
        com.google.firebase.messaging.FirebaseMessaging.getInstance()
            .subscribeToTopic("trading_signals")
    }
}
