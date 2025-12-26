package com.cointracker.pro.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cointracker.pro.data.models.WhaleTransaction
import com.cointracker.pro.ui.theme.*

/**
 * Whale Alert Card - Shows large transactions
 */
@Composable
fun WhaleAlertCard(
    transactions: List<WhaleTransaction>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1a237e).copy(alpha = 0.6f),
                        Color(0xFF0d47a1).copy(alpha = 0.4f)
                    )
                )
            )
            .padding(16.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🐋",
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Whale Alerts",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "${transactions.size} txns",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (transactions.isEmpty()) {
                Text(
                    text = "No whale movements detected",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            } else {
                // Show latest transactions
                transactions.take(3).forEach { tx ->
                    WhaleTransactionItem(tx)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun WhaleTransactionItem(tx: WhaleTransaction) {
    val isInflow = tx.isExchangeInflow
    val isOutflow = tx.isExchangeOutflow

    val (icon, iconColor, label) = when {
        isInflow -> Triple(Icons.Default.ArrowDownward, BearishRed, "TO Exchange")
        isOutflow -> Triple(Icons.Default.ArrowUpward, BullishGreen, "FROM Exchange")
        else -> Triple(Icons.Default.SwapHoriz, ElectricBlue, "Transfer")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatAmount(tx.amountUsd),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Row {
                Text(
                    text = tx.fromLabel ?: "Unknown",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                Text(
                    text = " → ",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
                Text(
                    text = tx.toLabel ?: "Unknown",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }

        // Signal indicator
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(iconColor.copy(alpha = 0.2f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isInflow) "BEARISH" else if (isOutflow) "BULLISH" else "NEUTRAL",
                color = iconColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatAmount(usd: Double): String {
    return when {
        usd >= 1_000_000_000 -> "$${String.format("%.1f", usd / 1_000_000_000)}B"
        usd >= 1_000_000 -> "$${String.format("%.1f", usd / 1_000_000)}M"
        usd >= 1_000 -> "$${String.format("%.0f", usd / 1_000)}K"
        else -> "$${String.format("%.0f", usd)}"
    }
}
