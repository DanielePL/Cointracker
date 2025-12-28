package com.cointracker.pro.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.cointracker.pro.MainActivity
import com.cointracker.pro.R

/**
 * Helper class for creating and showing notifications
 */
object NotificationHelper {

    // Channel IDs
    const val CHANNEL_TRADES = "trades"
    const val CHANNEL_SIGNALS = "signals"
    const val CHANNEL_ALERTS = "alerts"

    // Notification IDs
    private var notificationId = 1000

    /**
     * Create notification channels (required for Android 8+)
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // Trade Executed Channel
            val tradesChannel = NotificationChannel(
                CHANNEL_TRADES,
                "Trade Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when bot executes trades"
                enableVibration(true)
            }

            // Signal Alerts Channel
            val signalsChannel = NotificationChannel(
                CHANNEL_SIGNALS,
                "Signal Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Strong buy/sell signal notifications"
            }

            // Profit/Loss Alerts Channel
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Profit & Loss Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Take-profit and stop-loss alerts"
                enableVibration(true)
            }

            notificationManager.createNotificationChannels(
                listOf(tradesChannel, signalsChannel, alertsChannel)
            )
        }
    }

    /**
     * Show trade executed notification
     */
    fun showTradeNotification(
        context: Context,
        action: String,  // "BUY" or "SELL"
        coin: String,
        amount: Double,
        price: Double
    ) {
        val title = if (action == "BUY") "🟢 Bought $coin" else "🔴 Sold $coin"
        val message = "$${"%.2f".format(amount)} @ $${"%.2f".format(price)}"

        showNotification(context, CHANNEL_TRADES, title, message)
    }

    /**
     * Show take-profit or stop-loss notification
     */
    fun showProfitLossNotification(
        context: Context,
        coin: String,
        pnl: Double,
        pnlPercent: Double,
        reason: String  // "TAKE_PROFIT" or "STOP_LOSS"
    ) {
        val isProfit = pnl >= 0
        val emoji = if (isProfit) "💰" else "📉"
        val title = "$emoji $coin $reason"
        val sign = if (isProfit) "+" else ""
        val message = "$sign$${"%.2f".format(pnl)} ($sign${"%.1f".format(pnlPercent)}%)"

        showNotification(context, CHANNEL_ALERTS, title, message)
    }

    /**
     * Show strong signal notification
     */
    fun showSignalNotification(
        context: Context,
        coin: String,
        signal: String,  // "STRONG_BUY", "STRONG_SELL"
        score: Int
    ) {
        val emoji = if (signal.contains("BUY")) "🚀" else "⚠️"
        val title = "$emoji $signal: $coin"
        val message = "Score: $score/100"

        showNotification(context, CHANNEL_SIGNALS, title, message)
    }

    /**
     * Generic notification builder
     */
    private fun showNotification(
        context: Context,
        channelId: String,
        title: String,
        message: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent to open app when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notificationId++, notification)
    }
}
