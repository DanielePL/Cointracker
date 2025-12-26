package com.cointracker.pro.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cointracker.pro.data.models.AggregatedSentiment
import com.cointracker.pro.data.models.OnChainMetrics
import com.cointracker.pro.ui.theme.*

/**
 * Aggregated Sentiment Card with gauge visualization
 */
@Composable
fun SentimentCard(
    sentiment: AggregatedSentiment?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF4a148c).copy(alpha = 0.5f),
                        Color(0xFF7b1fa2).copy(alpha = 0.3f)
                    )
                )
            )
            .padding(16.dp)
    ) {
        if (sentiment == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(150.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ElectricBlue)
            }
        } else {
            Column {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Market Sentiment",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(getSentimentColor(sentiment.overallScore).copy(alpha = 0.2f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = sentiment.overallLabel.uppercase(),
                            color = getSentimentColor(sentiment.overallScore),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Gauge and Score
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sentiment Gauge
                    SentimentGauge(
                        score = sentiment.overallScore,
                        modifier = Modifier.size(100.dp)
                    )

                    // Factors
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                    ) {
                        Text(
                            text = "Bullish Factors",
                            color = BullishGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        sentiment.bullishFactors.take(2).forEach { factor ->
                            Text(
                                text = "• $factor",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Bearish Factors",
                            color = BearishRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        sentiment.bearishFactors.take(2).forEach { factor ->
                            Text(
                                text = "• $factor",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Source indicators
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sentiment.sources.forEach { source ->
                        SourceChip(
                            name = source.source,
                            value = source.value,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SentimentGauge(
    score: Double,
    modifier: Modifier = Modifier
) {
    val animatedScore by animateFloatAsState(
        targetValue = score.toFloat(),
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "score"
    )

    val color = getSentimentColor(score)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12f
            val radius = (size.minDimension - strokeWidth) / 2
            val center = Offset(size.width / 2, size.height / 2)

            // Background arc
            drawArc(
                color = Color.White.copy(alpha = 0.1f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Score arc
            val sweepAngle = (animatedScore / 100f) * 270f
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${score.toInt()}",
                color = color,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "/ 100",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun SourceChip(
    name: String,
    value: Double,
    modifier: Modifier = Modifier
) {
    val color = getSentimentColor(value * 100)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = name.take(10),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 9.sp
            )
            Text(
                text = "${(value * 100).toInt()}",
                color = color,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * On-Chain Metrics Card
 */
@Composable
fun OnChainMetricsCard(
    metrics: OnChainMetrics?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF004d40).copy(alpha = 0.5f),
                        Color(0xFF00695c).copy(alpha = 0.3f)
                    )
                )
            )
            .padding(16.dp)
    ) {
        if (metrics == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ElectricBlue)
            }
        } else {
            Column {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⛓️", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "On-Chain Data",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val signalColor = when (metrics.signal) {
                        "bullish" -> BullishGreen
                        "bearish" -> BearishRed
                        else -> NeutralYellow
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(signalColor.copy(alpha = 0.2f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = metrics.signal.uppercase(),
                            color = signalColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Metrics Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricItem(
                        label = "Exchange Flow",
                        value = formatFlow(metrics.exchangeNetflow),
                        isPositive = metrics.exchangeNetflow < 0 // Outflow is bullish
                    )
                    MetricItem(
                        label = "Whale Txns",
                        value = "${metrics.whaleTransactions24h}",
                        isPositive = null
                    )
                    MetricItem(
                        label = "Active Addr",
                        value = formatNumber(metrics.activeAddresses24h),
                        isPositive = null
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Reasons
                metrics.reasons.take(2).forEach { reason ->
                    Text(
                        text = "• $reason",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    isPositive: Boolean?
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = when (isPositive) {
                true -> BullishGreen
                false -> BearishRed
                null -> Color.White
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp
        )
    }
}

private fun getSentimentColor(score: Double): Color {
    return when {
        score <= 25 -> BearishRed
        score <= 45 -> Color(0xFFFF9800)
        score <= 55 -> NeutralYellow
        score <= 75 -> Color(0xFF8BC34A)
        else -> BullishGreen
    }
}

private fun formatFlow(flow: Double): String {
    val prefix = if (flow >= 0) "+" else ""
    return when {
        kotlin.math.abs(flow) >= 1000 -> "${prefix}${String.format("%.1f", flow / 1000)}K"
        else -> "${prefix}${flow.toInt()}"
    }
}

private fun formatNumber(num: Int): String {
    return when {
        num >= 1_000_000 -> "${String.format("%.1f", num / 1_000_000.0)}M"
        num >= 1_000 -> "${String.format("%.0f", num / 1_000.0)}K"
        else -> "$num"
    }
}
