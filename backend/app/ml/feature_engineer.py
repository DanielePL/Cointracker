"""
Feature Engineering for ML Model
Transforms raw market data into ML-ready features
"""

import numpy as np
import pandas as pd
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from loguru import logger

from app.services.indicators import TechnicalAnalyzer


@dataclass
class FeatureVector:
    """
    Complete feature vector for ML model input
    Each feature is normalized to roughly -1 to 1 or 0 to 1 range
    """

    # === TECHNICAL FEATURES ===
    # Momentum
    rsi_14: float = 0.0                 # Normalized RSI (0-1)
    rsi_divergence: int = 0             # 1=bullish, -1=bearish, 0=none
    macd_histogram: float = 0.0         # Normalized MACD histogram
    macd_cross: int = 0                 # 1=bullish cross, -1=bearish, 0=none

    # Trend
    price_vs_ema50: float = 0.0         # % deviation from EMA50
    price_vs_ema200: float = 0.0        # % deviation from EMA200
    ema_alignment: int = 0              # 1=bullish (50>200), -1=bearish

    # Volatility
    bb_position: float = 0.5            # Position within Bollinger Bands (0-1)
    bb_width: float = 0.0               # Normalized band width
    atr_normalized: float = 0.0         # ATR as % of price

    # Volume
    volume_ratio: float = 1.0           # Current volume / 20-day avg
    volume_trend: float = 0.0           # 5-day volume slope
    obv_divergence: int = 0             # On-Balance Volume divergence

    # === SENTIMENT FEATURES ===
    fear_greed_index: float = 0.5       # Normalized 0-1
    fear_greed_change_24h: float = 0.0  # Daily change normalized
    fear_greed_extreme: int = 0         # 1=extreme fear, 2=extreme greed

    # News/Social sentiment
    news_sentiment: float = 0.0         # -1 to 1
    social_sentiment: float = 0.0       # -1 to 1
    sentiment_momentum: float = 0.0     # Change in sentiment

    # === MARKET CONTEXT ===
    btc_dominance: float = 0.5          # BTC market dominance (normalized)
    market_cap_rank: float = 0.0        # Normalized rank
    drawdown_from_ath: float = 0.0      # % below ATH
    days_since_ath: int = 0             # Days since ATH

    # === TIME FEATURES ===
    hour_sin: float = 0.0               # Cyclical hour encoding
    hour_cos: float = 0.0
    day_sin: float = 0.0                # Cyclical day encoding
    day_cos: float = 0.0
    is_weekend: int = 0                 # Binary weekend flag

    # Metadata (not used in model)
    symbol: str = ""
    timestamp: datetime = field(default_factory=datetime.utcnow)

    def to_array(self) -> np.ndarray:
        """Convert to numpy array for model input"""
        return np.array([
            self.rsi_14, self.rsi_divergence, self.macd_histogram, self.macd_cross,
            self.price_vs_ema50, self.price_vs_ema200, self.ema_alignment,
            self.bb_position, self.bb_width, self.atr_normalized,
            self.volume_ratio, self.volume_trend, self.obv_divergence,
            self.fear_greed_index, self.fear_greed_change_24h, self.fear_greed_extreme,
            self.news_sentiment, self.social_sentiment, self.sentiment_momentum,
            self.btc_dominance, self.market_cap_rank, self.drawdown_from_ath, self.days_since_ath,
            self.hour_sin, self.hour_cos, self.day_sin, self.day_cos, self.is_weekend
        ], dtype=np.float32)

    @staticmethod
    def feature_names() -> List[str]:
        """Get feature names for interpretability"""
        return [
            "rsi_14", "rsi_divergence", "macd_histogram", "macd_cross",
            "price_vs_ema50", "price_vs_ema200", "ema_alignment",
            "bb_position", "bb_width", "atr_normalized",
            "volume_ratio", "volume_trend", "obv_divergence",
            "fear_greed_index", "fear_greed_change_24h", "fear_greed_extreme",
            "news_sentiment", "social_sentiment", "sentiment_momentum",
            "btc_dominance", "market_cap_rank", "drawdown_from_ath", "days_since_ath",
            "hour_sin", "hour_cos", "day_sin", "day_cos", "is_weekend"
        ]


class FeatureEngineer:
    """
    Transforms raw data into ML features
    """

    def __init__(self):
        self.analyzer = TechnicalAnalyzer()
        self._ath_cache: Dict[str, Tuple[float, datetime]] = {}

    async def create_features(
        self,
        ohlcv_data: pd.DataFrame,
        fear_greed: Optional[Dict] = None,
        sentiment: Optional[Dict] = None,
        market_data: Optional[Dict] = None,
        symbol: str = "BTC/USDT"
    ) -> FeatureVector:
        """
        Create complete feature vector from raw data

        Args:
            ohlcv_data: DataFrame with columns [timestamp, open, high, low, close, volume]
            fear_greed: Fear & Greed index data
            sentiment: Aggregated sentiment data
            market_data: Market context data (dominance, etc.)
        """
        features = FeatureVector(symbol=symbol, timestamp=datetime.utcnow())

        if ohlcv_data.empty:
            return features

        # Ensure we have enough data
        if len(ohlcv_data) < 200:
            logger.warning(f"Insufficient data for feature engineering: {len(ohlcv_data)} rows")

        # Calculate technical indicators
        await self._add_technical_features(features, ohlcv_data)

        # Add sentiment features
        self._add_sentiment_features(features, fear_greed, sentiment)

        # Add market context
        self._add_market_context(features, ohlcv_data, market_data, symbol)

        # Add time features
        self._add_time_features(features)

        return features

    async def _add_technical_features(
        self,
        features: FeatureVector,
        df: pd.DataFrame
    ):
        """Calculate and add technical indicator features"""

        close = df['close'].values
        high = df['high'].values
        low = df['low'].values
        volume = df['volume'].values

        # RSI
        rsi = self.analyzer.calculate_rsi(close)
        if rsi is not None:
            features.rsi_14 = rsi / 100.0  # Normalize to 0-1

            # Check for RSI divergence (simplified)
            if len(close) >= 14:
                price_trend = close[-1] - close[-14]
                rsi_current = rsi
                rsi_prev = self.analyzer.calculate_rsi(close[:-7])
                if rsi_prev is not None:
                    rsi_trend = rsi_current - rsi_prev

                    # Bullish divergence: price down, RSI up
                    if price_trend < 0 and rsi_trend > 5:
                        features.rsi_divergence = 1
                    # Bearish divergence: price up, RSI down
                    elif price_trend > 0 and rsi_trend < -5:
                        features.rsi_divergence = -1

        # MACD
        macd_result = self.analyzer.calculate_macd(close)
        if macd_result:
            macd_line, signal_line, histogram = macd_result

            # Normalize histogram by price
            features.macd_histogram = histogram / close[-1] * 100 if close[-1] > 0 else 0

            # Detect crossovers
            if len(close) >= 2:
                prev_hist = self.analyzer.calculate_macd(close[:-1])
                if prev_hist:
                    _, _, prev_histogram = prev_hist
                    if prev_histogram < 0 and histogram > 0:
                        features.macd_cross = 1  # Bullish cross
                    elif prev_histogram > 0 and histogram < 0:
                        features.macd_cross = -1  # Bearish cross

        # EMAs
        ema_result = self.analyzer.calculate_ema(close)
        if ema_result:
            ema50, ema200 = ema_result
            current_price = close[-1]

            features.price_vs_ema50 = (current_price - ema50) / ema50 if ema50 > 0 else 0
            features.price_vs_ema200 = (current_price - ema200) / ema200 if ema200 > 0 else 0
            features.ema_alignment = 1 if ema50 > ema200 else -1

        # Bollinger Bands
        bb_result = self.analyzer.calculate_bollinger(close)
        if bb_result:
            upper, middle, lower = bb_result
            current_price = close[-1]

            # Position within bands (0 = at lower, 1 = at upper)
            band_range = upper - lower
            if band_range > 0:
                features.bb_position = (current_price - lower) / band_range
                features.bb_width = band_range / middle if middle > 0 else 0

        # ATR (Average True Range)
        atr = self._calculate_atr(high, low, close)
        if atr is not None and close[-1] > 0:
            features.atr_normalized = atr / close[-1]

        # Volume features
        volume_result = self.analyzer.calculate_volume_analysis(close, volume)
        if volume_result:
            volume_sma, volume_ratio = volume_result
            features.volume_ratio = volume_ratio

            # Volume trend (slope of last 5 periods)
            if len(volume) >= 5:
                recent_volumes = volume[-5:]
                x = np.arange(5)
                slope = np.polyfit(x, recent_volumes, 1)[0]
                features.volume_trend = slope / np.mean(recent_volumes) if np.mean(recent_volumes) > 0 else 0

    def _add_sentiment_features(
        self,
        features: FeatureVector,
        fear_greed: Optional[Dict],
        sentiment: Optional[Dict]
    ):
        """Add sentiment-based features"""

        if fear_greed:
            value = fear_greed.get("value", 50)
            features.fear_greed_index = value / 100.0

            # Detect extremes
            if value <= 20:
                features.fear_greed_extreme = 1  # Extreme fear
            elif value >= 80:
                features.fear_greed_extreme = 2  # Extreme greed

            # Change tracking would need historical data
            features.fear_greed_change_24h = 0.0

        if sentiment:
            features.news_sentiment = sentiment.get("news_sentiment", 0.0)
            features.social_sentiment = sentiment.get("social_sentiment", 0.0)
            features.sentiment_momentum = sentiment.get("momentum", 0.0)

    def _add_market_context(
        self,
        features: FeatureVector,
        df: pd.DataFrame,
        market_data: Optional[Dict],
        symbol: str
    ):
        """Add market context features"""

        if market_data:
            features.btc_dominance = market_data.get("btc_dominance", 50) / 100.0

            rank = market_data.get("market_cap_rank", 1)
            # Normalize rank (1 = 1.0, 100 = 0.01)
            features.market_cap_rank = 1.0 / rank if rank > 0 else 0

        # Calculate drawdown from ATH
        close = df['close'].values
        current_price = close[-1]
        ath = np.max(close)

        # Update ATH cache
        if symbol not in self._ath_cache or ath > self._ath_cache[symbol][0]:
            self._ath_cache[symbol] = (ath, datetime.utcnow())

        cached_ath, ath_date = self._ath_cache[symbol]
        features.drawdown_from_ath = (cached_ath - current_price) / cached_ath if cached_ath > 0 else 0
        features.days_since_ath = (datetime.utcnow() - ath_date).days

    def _add_time_features(self, features: FeatureVector):
        """Add cyclical time features"""
        now = datetime.utcnow()

        # Hour of day (cyclical encoding)
        hour = now.hour
        features.hour_sin = np.sin(2 * np.pi * hour / 24)
        features.hour_cos = np.cos(2 * np.pi * hour / 24)

        # Day of week (cyclical encoding)
        day = now.weekday()
        features.day_sin = np.sin(2 * np.pi * day / 7)
        features.day_cos = np.cos(2 * np.pi * day / 7)

        # Weekend flag
        features.is_weekend = 1 if day >= 5 else 0

    def _calculate_atr(
        self,
        high: np.ndarray,
        low: np.ndarray,
        close: np.ndarray,
        period: int = 14
    ) -> Optional[float]:
        """Calculate Average True Range"""
        if len(close) < period + 1:
            return None

        tr_list = []
        for i in range(1, len(close)):
            tr = max(
                high[i] - low[i],
                abs(high[i] - close[i-1]),
                abs(low[i] - close[i-1])
            )
            tr_list.append(tr)

        if len(tr_list) < period:
            return None

        return np.mean(tr_list[-period:])

    def create_sequence(
        self,
        feature_history: List[FeatureVector],
        sequence_length: int = 24
    ) -> np.ndarray:
        """
        Create sequence of features for LSTM input

        Args:
            feature_history: List of historical feature vectors
            sequence_length: Number of timesteps for LSTM

        Returns:
            numpy array of shape (sequence_length, num_features)
        """
        if len(feature_history) < sequence_length:
            # Pad with zeros if not enough history
            padding_needed = sequence_length - len(feature_history)
            padded = [FeatureVector().to_array() for _ in range(padding_needed)]
            padded.extend([f.to_array() for f in feature_history])
            return np.array(padded, dtype=np.float32)

        # Take last sequence_length features
        return np.array(
            [f.to_array() for f in feature_history[-sequence_length:]],
            dtype=np.float32
        )


# Global instance
feature_engineer = FeatureEngineer()
