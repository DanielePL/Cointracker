package com.cointracker.pro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cointracker.pro.data.models.OHLCV
import com.cointracker.pro.ui.theme.*
import kotlin.math.abs

/**
 * Custom Candlestick Chart Component
 * Built with Canvas for better compatibility
 */
@Composable
fun CandlestickChart(
    ohlcvData: List<OHLCV>,
    symbol: String,
    selectedTimeframe: String = "1h",
    onTimeframeSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.04f)
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
                Text(
                    text = symbol,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                if (ohlcvData.isNotEmpty()) {
                    val lastCandle = ohlcvData.last()
                    val priceChange = lastCandle.close - lastCandle.open
                    val changePercent = (priceChange / lastCandle.open) * 100

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$${String.format("%,.2f", lastCandle.close)}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${if (changePercent >= 0) "+" else ""}${String.format("%.2f", changePercent)}%",
                            color = if (changePercent >= 0) BullishGreen else BearishRed,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chart
            if (ohlcvData.isNotEmpty()) {
                CandlestickCanvas(
                    candles = ohlcvData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Loading chart data...",
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Timeframe selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val timeframes = listOf("1h" to "1H", "4h" to "4H", "1d" to "1D", "1w" to "1W")
                timeframes.forEach { (apiTf, displayTf) ->
                    TimeframeButton(
                        text = displayTf,
                        selected = selectedTimeframe == apiTf,
                        onClick = { onTimeframeSelected(apiTf) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CandlestickCanvas(
    candles: List<OHLCV>,
    modifier: Modifier = Modifier
) {
    val bullishColor = BullishGreen
    val bearishColor = BearishRed
    val gridColor = Color.White.copy(alpha = 0.08f)
    val volumeAlpha = 0.3f

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var chartWidth by remember { mutableFloatStateOf(0f) }

    // Custom gesture handling that only consumes horizontal gestures
    val gestureModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            var pastTouchSlop = false
            val touchSlop = viewConfiguration.touchSlop
            var lockedToPan = false
            var totalPanX = 0f
            var totalPanY = 0f

            do {
                val event = awaitPointerEvent()
                val canceled = event.changes.any { it.isConsumed }
                if (!canceled) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()

                    if (!pastTouchSlop) {
                        zoom *= zoomChange
                        totalPanX += panChange.x
                        totalPanY += panChange.y

                        val totalPan = Offset(totalPanX, totalPanY)
                        val centroidSize = event.calculateCentroidSize(useCurrent = false)

                        if (centroidSize > touchSlop || totalPan.getDistance() > touchSlop) {
                            pastTouchSlop = true
                            // Only lock to pan if gesture is predominantly horizontal
                            // or if it's a pinch gesture (2+ fingers)
                            lockedToPan = event.changes.size >= 2 ||
                                    abs(totalPanX) > abs(totalPanY) * 1.5f
                        }
                    }

                    if (pastTouchSlop && lockedToPan) {
                        // Process zoom
                        if (zoomChange != 1f) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            val totalContentWidth = chartWidth * newScale
                            val maxOffset = (totalContentWidth - chartWidth).coerceAtLeast(0f)
                            val scaleDiff = newScale / scale
                            offsetX = (offsetX * scaleDiff).coerceIn(-maxOffset, 0f)
                            scale = newScale
                        }

                        // Process horizontal pan only
                        if (panChange.x != 0f) {
                            val totalContentWidth = chartWidth * scale
                            val maxOffset = (totalContentWidth - chartWidth).coerceAtLeast(0f)
                            offsetX = (offsetX + panChange.x).coerceIn(-maxOffset, 0f)
                        }

                        // Consume only if we're handling horizontal pan or zoom
                        event.changes.forEach {
                            if (it.positionChanged()) {
                                it.consume()
                            }
                        }
                    }
                    // If not lockedToPan, don't consume - let parent handle vertical scroll
                }
            } while (event.changes.any { it.pressed })
        }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .then(gestureModifier)
    ) {
        // Update chartWidth for pan calculations
        chartWidth = size.width
        val chartHeight = size.height
        val candleCount = candles.size

        // Reserve bottom 20% for volume
        val priceChartHeight = chartHeight * 0.75f
        val volumeChartHeight = chartHeight * 0.20f
        val volumeChartTop = priceChartHeight + (chartHeight * 0.05f)

        // Apply scale to candle dimensions
        val baseCandleWidth = chartWidth / candleCount
        val scaledCandleWidth = (baseCandleWidth * 0.75f) * scale
        val scaledSpacing = (baseCandleWidth * 0.25f) * scale
        val totalCandleSpace = scaledCandleWidth + scaledSpacing

        // Find min/max prices for scaling
        val allPrices = candles.flatMap { listOf(it.high, it.low) }
        val minPrice = allPrices.minOrNull() ?: 0.0
        val maxPrice = allPrices.maxOrNull() ?: 1.0
        val priceRange = (maxPrice - minPrice).coerceAtLeast(0.01)

        // Find max volume for scaling
        val maxVolume = candles.maxOfOrNull { it.volume } ?: 1.0

        // Calculate visible range for performance
        val visibleStartIndex = ((-offsetX) / totalCandleSpace).toInt().coerceAtLeast(0)
        val visibleEndIndex = ((chartWidth - offsetX) / totalCandleSpace).toInt().coerceAtMost(candleCount - 1)

        // Draw horizontal grid lines
        for (i in 0..4) {
            val y = priceChartHeight * (i / 4f)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(chartWidth, y),
                strokeWidth = 1f
            )
        }

        // Draw candles and volume (only visible ones for performance)
        for (index in visibleStartIndex..visibleEndIndex) {
            val candle = candles[index]
            // Apply scale and offset to x position
            val x = (index * totalCandleSpace) + (scaledSpacing / 2) + offsetX

            // Skip if outside visible area
            if (x + scaledCandleWidth < 0 || x > chartWidth) continue

            val isBullish = candle.close >= candle.open
            val color = if (isBullish) bullishColor else bearishColor

            // Calculate Y positions for price (inverted because canvas Y increases downward)
            val highY = ((maxPrice - candle.high) / priceRange * priceChartHeight).toFloat()
            val lowY = ((maxPrice - candle.low) / priceRange * priceChartHeight).toFloat()
            val openY = ((maxPrice - candle.open) / priceRange * priceChartHeight).toFloat()
            val closeY = ((maxPrice - candle.close) / priceRange * priceChartHeight).toFloat()

            // Draw wick (high-low line) - scale stroke width slightly
            val wickX = x + scaledCandleWidth / 2
            drawLine(
                color = color,
                start = Offset(wickX, highY),
                end = Offset(wickX, lowY),
                strokeWidth = (1.5f * scale).coerceIn(1f, 3f)
            )

            // Draw body (open-close rectangle)
            val bodyTop = minOf(openY, closeY)
            val bodyHeight = kotlin.math.abs(closeY - openY).coerceAtLeast(2f)

            drawRect(
                color = color,
                topLeft = Offset(x, bodyTop),
                size = Size(scaledCandleWidth, bodyHeight)
            )

            // Draw volume bar
            val volumeHeight = ((candle.volume / maxVolume) * volumeChartHeight).toFloat()
            drawRect(
                color = color.copy(alpha = volumeAlpha),
                topLeft = Offset(x, volumeChartTop + volumeChartHeight - volumeHeight),
                size = Size(scaledCandleWidth, volumeHeight)
            )
        }

        // Draw current price line (last close)
        candles.lastOrNull()?.let { lastCandle ->
            val lastCloseY = ((maxPrice - lastCandle.close) / priceRange * priceChartHeight).toFloat()
            drawLine(
                color = ElectricBlue.copy(alpha = 0.5f),
                start = Offset(0f, lastCloseY),
                end = Offset(chartWidth, lastCloseY),
                strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 5f))
            )
        }
    }
}

@Composable
private fun TimeframeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) ElectricBlue.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) ElectricBlue else Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * Mini sparkline chart for price overview
 */
@Composable
fun MiniPriceChart(
    prices: List<Double>,
    isPositive: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isPositive) BullishGreen else BearishRed

    Canvas(modifier = modifier.height(40.dp)) {
        if (prices.size < 2) return@Canvas

        val chartWidth = size.width
        val chartHeight = size.height
        val minPrice = prices.minOrNull() ?: 0.0
        val maxPrice = prices.maxOrNull() ?: 1.0
        val priceRange = (maxPrice - minPrice).coerceAtLeast(0.01)

        val stepX = chartWidth / (prices.size - 1)

        for (i in 0 until prices.size - 1) {
            val x1 = i * stepX
            val x2 = (i + 1) * stepX
            val y1 = ((maxPrice - prices[i]) / priceRange * chartHeight).toFloat()
            val y2 = ((maxPrice - prices[i + 1]) / priceRange * chartHeight).toFloat()

            drawLine(
                color = color,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 2f
            )
        }
    }
}
