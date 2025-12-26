package com.cointracker.pro.data.analysis

import com.cointracker.pro.data.binance.BinanceKline
import com.cointracker.pro.data.models.FearGreedIndex
import com.cointracker.pro.data.models.TechnicalIndicators
import com.cointracker.pro.data.models.TradingSignal
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Local Signal Generator - Echte technische Analyse
 *
 * Berechnet Trading-Signale basierend auf:
 * - RSI (20%)
 * - MACD (20%)
 * - EMA Trend (15%)
 * - Bollinger Bands (15%)
 * - Fear & Greed Index (20%)
 * - Volume Analysis (10%)
 */
class SignalGenerator {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    data class IndicatorAnalysis(
        val rsi: Double,
        val rsiSignal: String,
        val rsiScore: Double,

        val macdLine: Double,
        val macdSignal: Double,
        val macdHistogram: Double,
        val macdTrend: String,
        val macdScore: Double,

        val ema50: Double,
        val ema200: Double,
        val emaTrend: String,
        val emaScore: Double,

        val bbUpper: Double,
        val bbMiddle: Double,
        val bbLower: Double,
        val bbPosition: String,
        val bbScore: Double,

        val volumeRatio: Double,
        val volumeTrend: String,
        val volumeScore: Double,

        val atr: Double,
        val currentPrice: Double
    )

    /**
     * Generiert ein Trading-Signal für ein Symbol
     */
    fun generateSignal(
        symbol: String,
        klines: List<BinanceKline>,
        fearGreed: FearGreedIndex?
    ): TradingSignal {
        require(klines.size >= 200) { "Mindestens 200 Kerzen für akkurate Analyse benötigt" }

        val closes = klines.map { it.close }
        val highs = klines.map { it.high }
        val lows = klines.map { it.low }
        val volumes = klines.map { it.volume }
        val currentPrice = closes.last()

        // Berechne alle Indikatoren
        val analysis = analyzeIndicators(closes, highs, lows, volumes, currentPrice)

        // Fear & Greed Score
        val fgScore = calculateFearGreedScore(fearGreed)

        // Gewichteter Gesamtscore
        val weights = mapOf(
            "rsi" to 0.20,
            "macd" to 0.20,
            "ema" to 0.15,
            "bb" to 0.15,
            "fearGreed" to 0.20,
            "volume" to 0.10
        )

        val totalScore = (
            analysis.rsiScore * weights["rsi"]!! +
            analysis.macdScore * weights["macd"]!! +
            analysis.emaScore * weights["ema"]!! +
            analysis.bbScore * weights["bb"]!! +
            fgScore * weights["fearGreed"]!! +
            analysis.volumeScore * weights["volume"]!!
        ).toInt().coerceIn(0, 100)

        // Signal-Typ bestimmen
        val signalType = when {
            totalScore >= 75 -> "STRONG_BUY"
            totalScore >= 60 -> "BUY"
            totalScore <= 25 -> "STRONG_SELL"
            totalScore <= 40 -> "SELL"
            else -> "HOLD"
        }

        // Gründe generieren
        val reasons = generateReasons(analysis, fearGreed, fgScore)

        // Risk Level
        val riskLevel = calculateRiskLevel(analysis, totalScore)

        // Confidence basierend auf Indikator-Übereinstimmung
        val confidence = calculateConfidence(analysis, fgScore)

        // Entry, Stop-Loss, Take-Profit berechnen
        val (entryPrice, stopLoss, takeProfit) = calculateTradeLevels(
            currentPrice = currentPrice,
            atr = analysis.atr,
            signalType = signalType
        )

        // TechnicalIndicators für das Signal
        val indicators = TechnicalIndicators(
            symbol = symbol,
            timeframe = "1h",
            timestamp = dateFormat.format(Date()),
            rsi = analysis.rsi,
            rsiSignal = analysis.rsiSignal,
            macdLine = analysis.macdLine,
            macdSignal = analysis.macdSignal,
            macdHistogram = analysis.macdHistogram,
            macdTrend = analysis.macdTrend,
            bbUpper = analysis.bbUpper,
            bbMiddle = analysis.bbMiddle,
            bbLower = analysis.bbLower,
            bbPosition = analysis.bbPosition,
            ema50 = analysis.ema50,
            ema200 = analysis.ema200,
            emaTrend = analysis.emaTrend,
            volumeSma = volumes.takeLast(20).average(),
            volumeRatio = analysis.volumeRatio
        )

        return TradingSignal(
            symbol = symbol,
            timestamp = dateFormat.format(Date()),
            signal = signalType,
            signalScore = totalScore,
            confidence = confidence,
            reasons = reasons,
            riskLevel = riskLevel,
            suggestedAction = generateSuggestedAction(signalType, confidence),
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            indicators = indicators,
            fearGreed = fearGreed
        )
    }

    private fun analyzeIndicators(
        closes: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        volumes: List<Double>,
        currentPrice: Double
    ): IndicatorAnalysis {
        // RSI (14 Perioden)
        val rsi = calculateRSI(closes, 14)
        val rsiSignal = when {
            rsi < 30 -> "oversold"
            rsi > 70 -> "overbought"
            rsi < 40 -> "approaching_oversold"
            rsi > 60 -> "approaching_overbought"
            else -> "neutral"
        }
        val rsiScore = when {
            rsi < 20 -> 90.0  // Stark überverkauft = starkes Kaufsignal
            rsi < 30 -> 75.0
            rsi < 40 -> 60.0
            rsi > 80 -> 10.0  // Stark überkauft = starkes Verkaufssignal
            rsi > 70 -> 25.0
            rsi > 60 -> 40.0
            else -> 50.0
        }

        // MACD (12, 26, 9)
        val ema12 = calculateEMA(closes, 12)
        val ema26 = calculateEMA(closes, 26)
        val macdLine = ema12 - ema26

        // MACD Signal Line (9-Perioden EMA der MACD-Linie)
        val macdHistory = mutableListOf<Double>()
        for (i in 26 until closes.size) {
            val shortEma = calculateEMA(closes.take(i + 1), 12)
            val longEma = calculateEMA(closes.take(i + 1), 26)
            macdHistory.add(shortEma - longEma)
        }
        val macdSignalLine = if (macdHistory.size >= 9) calculateEMA(macdHistory, 9) else 0.0
        val macdHistogram = macdLine - macdSignalLine

        val macdTrend = when {
            macdLine > macdSignalLine && macdHistogram > 0 -> "bullish"
            macdLine < macdSignalLine && macdHistogram < 0 -> "bearish"
            macdLine > macdSignalLine -> "bullish_crossing"
            else -> "bearish_crossing"
        }

        val macdScore = when {
            macdHistogram > 0 && macdLine > 0 -> 70.0 + (macdHistogram / abs(macdLine) * 20).coerceAtMost(20.0)
            macdHistogram > 0 -> 60.0
            macdHistogram < 0 && macdLine < 0 -> 30.0 - (abs(macdHistogram) / abs(macdLine) * 20).coerceAtMost(20.0)
            macdHistogram < 0 -> 40.0
            else -> 50.0
        }

        // EMA 50/200 (Golden Cross / Death Cross)
        val ema50 = calculateEMA(closes, 50)
        val ema200 = calculateEMA(closes, 200)
        val emaTrend = when {
            ema50 > ema200 && currentPrice > ema50 -> "strong_bullish"
            ema50 > ema200 -> "bullish"
            ema50 < ema200 && currentPrice < ema50 -> "strong_bearish"
            ema50 < ema200 -> "bearish"
            else -> "neutral"
        }

        val emaScore = when (emaTrend) {
            "strong_bullish" -> 85.0
            "bullish" -> 65.0
            "strong_bearish" -> 15.0
            "bearish" -> 35.0
            else -> 50.0
        }

        // Bollinger Bands (20, 2)
        val bbPeriod = 20
        val bbStdDev = 2.0
        val sma20 = closes.takeLast(bbPeriod).average()
        val stdDev = calculateStdDev(closes.takeLast(bbPeriod))
        val bbUpper = sma20 + (bbStdDev * stdDev)
        val bbLower = sma20 - (bbStdDev * stdDev)
        val bbMiddle = sma20

        val bbPosition = when {
            currentPrice >= bbUpper -> "above_upper"
            currentPrice <= bbLower -> "below_lower"
            currentPrice > bbMiddle -> "upper_half"
            else -> "lower_half"
        }

        val bbPercentB = (currentPrice - bbLower) / (bbUpper - bbLower)
        val bbScore = when {
            bbPercentB < 0 -> 85.0  // Unter dem unteren Band = Kaufsignal
            bbPercentB < 0.2 -> 70.0
            bbPercentB > 1 -> 15.0  // Über dem oberen Band = Verkaufssignal
            bbPercentB > 0.8 -> 30.0
            else -> 50.0
        }

        // Volume Analysis
        val volumeSma = volumes.takeLast(20).average()
        val currentVolume = volumes.last()
        val volumeRatio = currentVolume / volumeSma

        val volumeTrend = when {
            volumeRatio > 1.5 -> "high_volume"
            volumeRatio > 1.0 -> "above_average"
            volumeRatio < 0.5 -> "low_volume"
            else -> "below_average"
        }

        // Volume bestätigt den Trend - kein direktes Buy/Sell Signal
        val volumeScore = 50.0 // Neutral, wird mit anderen kombiniert

        // ATR für Stop-Loss/Take-Profit
        val atr = calculateATR(highs, lows, closes, 14)

        return IndicatorAnalysis(
            rsi = rsi,
            rsiSignal = rsiSignal,
            rsiScore = rsiScore,
            macdLine = macdLine,
            macdSignal = macdSignalLine,
            macdHistogram = macdHistogram,
            macdTrend = macdTrend,
            macdScore = macdScore,
            ema50 = ema50,
            ema200 = ema200,
            emaTrend = emaTrend,
            emaScore = emaScore,
            bbUpper = bbUpper,
            bbMiddle = bbMiddle,
            bbLower = bbLower,
            bbPosition = bbPosition,
            bbScore = bbScore,
            volumeRatio = volumeRatio,
            volumeTrend = volumeTrend,
            volumeScore = volumeScore,
            atr = atr,
            currentPrice = currentPrice
        )
    }

    private fun calculateRSI(closes: List<Double>, period: Int): Double {
        if (closes.size < period + 1) return 50.0

        var avgGain = 0.0
        var avgLoss = 0.0

        // Initial average
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) avgGain += change
            else avgLoss += abs(change)
        }
        avgGain /= period
        avgLoss /= period

        // Smoothed RSI
        for (i in (period + 1) until closes.size) {
            val change = closes[i] - closes[i - 1]
            if (change > 0) {
                avgGain = (avgGain * (period - 1) + change) / period
                avgLoss = (avgLoss * (period - 1)) / period
            } else {
                avgGain = (avgGain * (period - 1)) / period
                avgLoss = (avgLoss * (period - 1) + abs(change)) / period
            }
        }

        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    private fun calculateEMA(values: List<Double>, period: Int): Double {
        if (values.size < period) return values.average()

        val multiplier = 2.0 / (period + 1)
        var ema = values.take(period).average()

        for (i in period until values.size) {
            ema = (values[i] - ema) * multiplier + ema
        }
        return ema
    }

    private fun calculateStdDev(values: List<Double>): Double {
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }

    private fun calculateATR(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
        period: Int
    ): Double {
        if (highs.size < period + 1) return 0.0

        val trueRanges = mutableListOf<Double>()
        for (i in 1 until highs.size) {
            val highLow = highs[i] - lows[i]
            val highClose = abs(highs[i] - closes[i - 1])
            val lowClose = abs(lows[i] - closes[i - 1])
            trueRanges.add(maxOf(highLow, highClose, lowClose))
        }

        // Smoothed ATR
        var atr = trueRanges.take(period).average()
        for (i in period until trueRanges.size) {
            atr = (atr * (period - 1) + trueRanges[i]) / period
        }
        return atr
    }

    private fun calculateFearGreedScore(fearGreed: FearGreedIndex?): Double {
        if (fearGreed == null) return 50.0

        // Fear & Greed ist konträr: Extreme Fear = Kaufsignal, Extreme Greed = Verkaufssignal
        return when {
            fearGreed.value <= 20 -> 85.0  // Extreme Fear = starkes Kaufsignal
            fearGreed.value <= 35 -> 70.0  // Fear
            fearGreed.value >= 80 -> 15.0  // Extreme Greed = starkes Verkaufssignal
            fearGreed.value >= 65 -> 30.0  // Greed
            else -> 50.0  // Neutral
        }
    }

    private fun generateReasons(
        analysis: IndicatorAnalysis,
        fearGreed: FearGreedIndex?,
        fgScore: Double
    ): List<String> {
        val reasons = mutableListOf<String>()

        // RSI
        when {
            analysis.rsi < 30 -> reasons.add("RSI bei ${String.format("%.1f", analysis.rsi)} - überverkauft, Kaufgelegenheit")
            analysis.rsi < 40 -> reasons.add("RSI bei ${String.format("%.1f", analysis.rsi)} - nähert sich überverkaufter Zone")
            analysis.rsi > 70 -> reasons.add("RSI bei ${String.format("%.1f", analysis.rsi)} - überkauft, Vorsicht geboten")
            analysis.rsi > 60 -> reasons.add("RSI bei ${String.format("%.1f", analysis.rsi)} - nähert sich überkaufter Zone")
            else -> reasons.add("RSI bei ${String.format("%.1f", analysis.rsi)} - neutral")
        }

        // MACD
        when (analysis.macdTrend) {
            "bullish" -> reasons.add("MACD bullish: Linie über Signal, positives Histogram")
            "bullish_crossing" -> reasons.add("MACD Kaufsignal: Linie kreuzt Signal nach oben")
            "bearish" -> reasons.add("MACD bearish: Linie unter Signal, negatives Histogram")
            "bearish_crossing" -> reasons.add("MACD Verkaufssignal: Linie kreuzt Signal nach unten")
        }

        // EMA Trend
        when (analysis.emaTrend) {
            "strong_bullish" -> reasons.add("Starker Aufwärtstrend: Preis über EMA50, Golden Cross aktiv")
            "bullish" -> reasons.add("Aufwärtstrend: EMA50 über EMA200 (Golden Cross)")
            "strong_bearish" -> reasons.add("Starker Abwärtstrend: Preis unter EMA50, Death Cross aktiv")
            "bearish" -> reasons.add("Abwärtstrend: EMA50 unter EMA200 (Death Cross)")
            else -> reasons.add("EMA-Trend neutral, kein klarer Trend")
        }

        // Bollinger Bands
        when (analysis.bbPosition) {
            "below_lower" -> reasons.add("Preis unter unterem Bollinger Band - stark überverkauft")
            "lower_half" -> reasons.add("Preis in unterer Hälfte der Bollinger Bänder")
            "above_upper" -> reasons.add("Preis über oberem Bollinger Band - stark überkauft")
            "upper_half" -> reasons.add("Preis in oberer Hälfte der Bollinger Bänder")
        }

        // Fear & Greed
        fearGreed?.let {
            when {
                it.value <= 25 -> reasons.add("Fear & Greed: ${it.value} (${it.classification}) - Extreme Angst = Kaufgelegenheit")
                it.value <= 40 -> reasons.add("Fear & Greed: ${it.value} (${it.classification}) - Markt ängstlich")
                it.value >= 75 -> reasons.add("Fear & Greed: ${it.value} (${it.classification}) - Extreme Gier = Vorsicht")
                it.value >= 60 -> reasons.add("Fear & Greed: ${it.value} (${it.classification}) - Markt gierig")
                else -> reasons.add("Fear & Greed: ${it.value} (${it.classification}) - neutral")
            }
        }

        // Volume
        when (analysis.volumeTrend) {
            "high_volume" -> reasons.add("Volumen ${String.format("%.1f", analysis.volumeRatio)}x über Durchschnitt - starke Bewegung")
            "above_average" -> reasons.add("Volumen leicht über Durchschnitt - Trend wird bestätigt")
            "low_volume" -> reasons.add("Niedriges Volumen - schwache Überzeugung im Markt")
            else -> reasons.add("Volumen unter Durchschnitt")
        }

        return reasons.take(6) // Max 6 Gründe
    }

    private fun calculateRiskLevel(analysis: IndicatorAnalysis, totalScore: Int): String {
        val volatility = analysis.atr / analysis.currentPrice * 100 // ATR als Prozent

        return when {
            volatility > 5 -> "VERY_HIGH"
            volatility > 3 -> "HIGH"
            volatility > 1.5 -> "MEDIUM"
            else -> "LOW"
        }
    }

    private fun calculateConfidence(analysis: IndicatorAnalysis, fgScore: Double): Double {
        // Confidence basiert auf Übereinstimmung der Indikatoren
        val scores = listOf(
            analysis.rsiScore,
            analysis.macdScore,
            analysis.emaScore,
            analysis.bbScore,
            fgScore
        )

        val avgScore = scores.average()
        val bullishCount = scores.count { it > 55 }
        val bearishCount = scores.count { it < 45 }

        // Hohe Confidence wenn viele Indikatoren übereinstimmen
        val agreement = maxOf(bullishCount, bearishCount).toDouble() / scores.size

        return (agreement * 0.6 + (1 - calculateStdDev(scores) / 50) * 0.4).coerceIn(0.3, 0.95)
    }

    private fun calculateTradeLevels(
        currentPrice: Double,
        atr: Double,
        signalType: String
    ): Triple<Double, Double, Double> {
        return when (signalType) {
            "STRONG_BUY", "BUY" -> Triple(
                currentPrice,                           // Entry at current price
                currentPrice - (atr * 1.5),            // Stop Loss: 1.5x ATR unter Entry
                currentPrice + (atr * 3.0)             // Take Profit: 3x ATR über Entry (2:1 R/R)
            )
            "STRONG_SELL", "SELL" -> Triple(
                currentPrice,                           // Entry at current price (für Short)
                currentPrice + (atr * 1.5),            // Stop Loss: 1.5x ATR über Entry
                currentPrice - (atr * 3.0)             // Take Profit: 3x ATR unter Entry
            )
            else -> Triple(currentPrice, currentPrice - atr, currentPrice + atr)
        }
    }

    private fun generateSuggestedAction(signalType: String, confidence: Double): String {
        val confText = when {
            confidence > 0.75 -> "hoher"
            confidence > 0.5 -> "mittlerer"
            else -> "niedriger"
        }

        return when (signalType) {
            "STRONG_BUY" -> "Starkes Kaufsignal mit $confText Konfidenz. Position aufbauen erwägen."
            "BUY" -> "Kaufsignal mit $confText Konfidenz. Auf Bestätigung warten."
            "STRONG_SELL" -> "Starkes Verkaufssignal mit $confText Konfidenz. Position reduzieren erwägen."
            "SELL" -> "Verkaufssignal mit $confText Konfidenz. Stop-Loss setzen."
            else -> "Neutral - Seitenlinie halten, auf klares Signal warten."
        }
    }
}
