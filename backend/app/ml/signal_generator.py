"""
CoinTracker Pro - ML Signal Generator
The brain of the trading bot. Generates signals with explanations.
"""
import numpy as np
from typing import List, Optional, Tuple
from datetime import datetime
from loguru import logger
from dataclasses import dataclass

from app.models.schemas import (
    TradingSignal, SignalDirection, SignalReason,
    HistoricalPattern, FeatureVector, TechnicalIndicators,
    FearGreedIndex
)
from app.services.exchange import exchange_service
from app.services.indicators import indicator_service
from app.services.fear_greed import fear_greed_service
from app.config import get_settings


@dataclass
class IndicatorSignal:
    """Individual indicator signal with explanation."""
    name: str
    value: float
    signal: int  # 1=bullish, -1=bearish, 0=neutral
    weight: float
    reason: str


class SignalGenerator:
    """
    Generates trading signals using a rule-based approach combined with
    indicator weighting. This is the foundation - ML models can be added
    on top for more sophisticated predictions.
    """

    def __init__(self):
        self.settings = get_settings()

        # Indicator weights (sum to ~1.0)
        self.weights = {
            'rsi': 0.15,
            'macd': 0.15,
            'bollinger': 0.10,
            'ema': 0.10,
            'volume': 0.10,
            'fear_greed': 0.20,  # Sentiment gets more weight
            'divergence': 0.10,
            'extreme_zones': 0.10,
        }

    async def generate_signal(self, symbol: str) -> TradingSignal:
        """
        Generate a trading signal for a symbol with full explanation.
        """
        try:
            # Get market data
            df = await exchange_service.get_ohlcv_dataframe(symbol, '1h', 250)
            indicators = indicator_service.calculate_all(df)
            fear_greed = await fear_greed_service.get_with_changes()
            ticker = await exchange_service.get_ticker(symbol)

            # Analyze each indicator
            signals = self._analyze_indicators(indicators, fear_greed)

            # Calculate weighted score
            score, direction, confidence = self._calculate_score(signals)

            # Generate explanations
            top_reasons = self._generate_reasons(signals)
            bullish_factors = [r for r in top_reasons if r.contribution > 0]
            bearish_factors = [r for r in top_reasons if r.contribution < 0]

            # Find similar historical patterns (simplified)
            similar_patterns = self._find_similar_patterns(indicators, fear_greed)

            # Calculate suggested levels
            current_price = ticker.price
            suggested_entry = current_price
            suggested_stop_loss = current_price * (1 - self.settings.default_stop_loss_pct / 100)
            suggested_take_profit = current_price * (1 + self.settings.default_take_profit_pct / 100)

            if direction == SignalDirection.SELL:
                suggested_stop_loss = current_price * (1 + self.settings.default_stop_loss_pct / 100)
                suggested_take_profit = current_price * (1 - self.settings.default_take_profit_pct / 100)

            return TradingSignal(
                symbol=symbol,
                score=score,
                direction=direction,
                confidence=confidence,
                top_reasons=top_reasons[:5],
                bullish_factors=bullish_factors[:3],
                bearish_factors=bearish_factors[:3],
                similar_patterns=similar_patterns[:3],
                current_price=current_price,
                suggested_entry=suggested_entry,
                suggested_stop_loss=suggested_stop_loss,
                suggested_take_profit=suggested_take_profit,
                timestamp=datetime.utcnow()
            )

        except Exception as e:
            logger.error(f"Signal generation failed for {symbol}: {e}")
            raise

    def _analyze_indicators(
        self,
        indicators: TechnicalIndicators,
        fear_greed: FearGreedIndex
    ) -> List[IndicatorSignal]:
        """Analyze all indicators and generate individual signals."""
        signals = []

        # === RSI Analysis ===
        rsi = indicators.rsi_14
        if rsi < 30:
            signals.append(IndicatorSignal(
                name="RSI",
                value=rsi,
                signal=1,
                weight=self.weights['rsi'] * 1.5,  # Stronger in extremes
                reason=f"RSI bei {rsi:.0f} (Oversold) - historisch guter Einstieg"
            ))
        elif rsi < 40:
            signals.append(IndicatorSignal(
                name="RSI",
                value=rsi,
                signal=1,
                weight=self.weights['rsi'] * 0.5,
                reason=f"RSI bei {rsi:.0f} - leicht überverkauft"
            ))
        elif rsi > 70:
            signals.append(IndicatorSignal(
                name="RSI",
                value=rsi,
                signal=-1,
                weight=self.weights['rsi'] * 1.5,
                reason=f"RSI bei {rsi:.0f} (Overbought) - Vorsicht vor Korrektur"
            ))
        elif rsi > 60:
            signals.append(IndicatorSignal(
                name="RSI",
                value=rsi,
                signal=-1,
                weight=self.weights['rsi'] * 0.5,
                reason=f"RSI bei {rsi:.0f} - leicht überkauft"
            ))
        else:
            signals.append(IndicatorSignal(
                name="RSI",
                value=rsi,
                signal=0,
                weight=0,
                reason=f"RSI bei {rsi:.0f} - neutral"
            ))

        # === MACD Analysis ===
        macd_hist = indicators.macd_histogram
        if indicators.macd_cross == 1:
            signals.append(IndicatorSignal(
                name="MACD",
                value=macd_hist,
                signal=1,
                weight=self.weights['macd'],
                reason="MACD bullish Crossover - Momentum dreht positiv"
            ))
        elif indicators.macd_cross == -1:
            signals.append(IndicatorSignal(
                name="MACD",
                value=macd_hist,
                signal=-1,
                weight=self.weights['macd'],
                reason="MACD bearish Crossover - Momentum dreht negativ"
            ))

        # === Bollinger Bands ===
        bb_pos = indicators.bb_position
        if bb_pos < 0.1:
            signals.append(IndicatorSignal(
                name="Bollinger",
                value=bb_pos,
                signal=1,
                weight=self.weights['bollinger'] * 1.5,
                reason="Preis am unteren Bollinger Band - potentieller Bounce"
            ))
        elif bb_pos > 0.9:
            signals.append(IndicatorSignal(
                name="Bollinger",
                value=bb_pos,
                signal=-1,
                weight=self.weights['bollinger'] * 1.5,
                reason="Preis am oberen Bollinger Band - potentieller Rücksetzer"
            ))
        elif indicators.bb_width < 0.03:
            signals.append(IndicatorSignal(
                name="Bollinger",
                value=indicators.bb_width,
                signal=0,
                weight=self.weights['bollinger'] * 0.5,
                reason="Bollinger Squeeze - große Bewegung steht bevor"
            ))

        # === EMA Alignment (Trend) ===
        if indicators.ema_alignment == 1:
            signals.append(IndicatorSignal(
                name="EMA",
                value=indicators.price_vs_ema50_pct,
                signal=1,
                weight=self.weights['ema'],
                reason="Golden Cross aktiv (EMA50 > EMA200) - Uptrend"
            ))
        else:
            signals.append(IndicatorSignal(
                name="EMA",
                value=indicators.price_vs_ema50_pct,
                signal=-1,
                weight=self.weights['ema'],
                reason="Death Cross aktiv (EMA50 < EMA200) - Downtrend"
            ))

        # === Volume Analysis ===
        if indicators.volume_ratio > 2.0:
            # High volume confirms moves
            signals.append(IndicatorSignal(
                name="Volume",
                value=indicators.volume_ratio,
                signal=0,  # Volume is confirming, not directional
                weight=self.weights['volume'],
                reason=f"Hohes Volumen ({indicators.volume_ratio:.1f}x Durchschnitt) - Bewegung bestätigt"
            ))

        # === Fear & Greed Index (THE KEY!) ===
        fg_value = fear_greed.value
        if fg_value <= 20:
            signals.append(IndicatorSignal(
                name="Fear&Greed",
                value=fg_value,
                signal=1,
                weight=self.weights['fear_greed'] * 2.0,  # Double weight for extremes!
                reason=f"Extreme Fear ({fg_value}) - 'Blood in the streets' - historisch bester Einstieg!"
            ))
        elif fg_value <= 35:
            signals.append(IndicatorSignal(
                name="Fear&Greed",
                value=fg_value,
                signal=1,
                weight=self.weights['fear_greed'],
                reason=f"Fear Index bei {fg_value} - Markt ist ängstlich"
            ))
        elif fg_value >= 80:
            signals.append(IndicatorSignal(
                name="Fear&Greed",
                value=fg_value,
                signal=-1,
                weight=self.weights['fear_greed'] * 2.0,
                reason=f"Extreme Greed ({fg_value}) - Markt überhitzt - Vorsicht!"
            ))
        elif fg_value >= 65:
            signals.append(IndicatorSignal(
                name="Fear&Greed",
                value=fg_value,
                signal=-1,
                weight=self.weights['fear_greed'],
                reason=f"Greed Index bei {fg_value} - Markt wird gierig"
            ))
        else:
            signals.append(IndicatorSignal(
                name="Fear&Greed",
                value=fg_value,
                signal=0,
                weight=0,
                reason=f"Fear & Greed neutral bei {fg_value}"
            ))

        # === RSI Divergence ===
        if indicators.rsi_divergence == 1:
            signals.append(IndicatorSignal(
                name="Divergence",
                value=1,
                signal=1,
                weight=self.weights['divergence'] * 1.5,
                reason="Bullish RSI Divergenz erkannt - Hidden Strength!"
            ))
        elif indicators.rsi_divergence == -1:
            signals.append(IndicatorSignal(
                name="Divergence",
                value=-1,
                signal=-1,
                weight=self.weights['divergence'] * 1.5,
                reason="Bearish RSI Divergenz erkannt - Hidden Weakness!"
            ))

        return signals

    def _calculate_score(
        self,
        signals: List[IndicatorSignal]
    ) -> Tuple[int, SignalDirection, float]:
        """
        Calculate overall score, direction, and confidence.

        Returns:
            score: 0-100 (50 = neutral, 0 = strong sell, 100 = strong buy)
            direction: BUY, SELL, or HOLD
            confidence: 0-1
        """
        if not signals:
            return 50, SignalDirection.HOLD, 0.0

        # Calculate weighted sum
        total_weight = sum(abs(s.weight) for s in signals if s.signal != 0)
        if total_weight == 0:
            return 50, SignalDirection.HOLD, 0.3

        weighted_sum = sum(s.signal * s.weight for s in signals)

        # Normalize to 0-100 scale
        # weighted_sum ranges from roughly -1 to 1
        score = int(50 + weighted_sum * 50)
        score = max(0, min(100, score))

        # Determine direction
        if score >= 65:
            direction = SignalDirection.BUY
        elif score <= 35:
            direction = SignalDirection.SELL
        else:
            direction = SignalDirection.HOLD

        # Calculate confidence based on agreement of signals
        bullish_signals = sum(1 for s in signals if s.signal > 0)
        bearish_signals = sum(1 for s in signals if s.signal < 0)
        total_signals = bullish_signals + bearish_signals

        if total_signals == 0:
            confidence = 0.3
        else:
            # Higher confidence when signals agree
            agreement = abs(bullish_signals - bearish_signals) / total_signals
            confidence = 0.5 + agreement * 0.5

        return score, direction, confidence

    def _generate_reasons(
        self,
        signals: List[IndicatorSignal]
    ) -> List[SignalReason]:
        """Convert indicator signals to human-readable reasons."""
        reasons = []

        for signal in signals:
            if signal.signal == 0 and signal.weight == 0:
                continue

            contribution = signal.signal * signal.weight * 100  # Scale to points

            reasons.append(SignalReason(
                factor=signal.name,
                value=signal.value,
                contribution=contribution,
                description=signal.reason
            ))

        # Sort by absolute contribution
        reasons.sort(key=lambda r: abs(r.contribution), reverse=True)
        return reasons

    def _find_similar_patterns(
        self,
        indicators: TechnicalIndicators,
        fear_greed: FearGreedIndex
    ) -> List[HistoricalPattern]:
        """
        Find similar historical patterns.
        This is a simplified version - a full implementation would use
        the pattern matching database.
        """
        patterns = []

        # Example patterns based on known market behavior
        if fear_greed.value < 25 and indicators.rsi_14 < 35:
            patterns.append(HistoricalPattern(
                date=datetime(2022, 6, 18),  # Example: June 2022 bottom
                similarity_score=0.85,
                pattern_description="Extreme Fear + Oversold RSI",
                outcome_1d=2.3,
                outcome_7d=12.1,
                outcome_30d=34.5
            ))

        if indicators.ema_alignment == 1 and indicators.bb_position < 0.2:
            patterns.append(HistoricalPattern(
                date=datetime(2023, 10, 15),  # Example
                similarity_score=0.78,
                pattern_description="Uptrend + Lower BB Touch",
                outcome_1d=1.8,
                outcome_7d=8.5,
                outcome_30d=22.0
            ))

        if fear_greed.value > 75 and indicators.rsi_14 > 70:
            patterns.append(HistoricalPattern(
                date=datetime(2021, 11, 10),  # Example: Nov 2021 top
                similarity_score=0.82,
                pattern_description="Extreme Greed + Overbought",
                outcome_1d=-3.2,
                outcome_7d=-15.8,
                outcome_30d=-28.4
            ))

        return patterns

    async def should_trade(self, signal: TradingSignal) -> bool:
        """
        Determine if a signal is strong enough to trade.
        """
        return (
            signal.score >= self.settings.signal_threshold or
            signal.score <= (100 - self.settings.signal_threshold)
        ) and signal.confidence >= self.settings.confidence_threshold


# Singleton instance
signal_generator = SignalGenerator()
