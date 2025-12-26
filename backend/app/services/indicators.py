"""
CoinTracker Pro - Technical Indicators Service
Uses 'ta' library for technical analysis (pure Python, cross-platform)
"""
import pandas as pd
import numpy as np
from typing import Optional, Tuple
from loguru import logger

# Technical Analysis library
import ta
from ta.momentum import RSIIndicator, StochRSIIndicator
from ta.trend import MACD, EMAIndicator, SMAIndicator
from ta.volatility import BollingerBands, AverageTrueRange
from ta.volume import OnBalanceVolumeIndicator, VolumeWeightedAveragePrice

from app.models.schemas import TechnicalIndicators


class IndicatorService:
    """Calculate technical indicators from OHLCV data."""

    def calculate_all(self, df: pd.DataFrame) -> TechnicalIndicators:
        """
        Calculate all technical indicators from OHLCV DataFrame.

        Args:
            df: DataFrame with columns: open, high, low, close, volume

        Returns:
            TechnicalIndicators object with all calculated values
        """
        if len(df) < 200:
            logger.warning(f"Limited data ({len(df)} candles). Some indicators may be inaccurate.")

        close = df['close']
        high = df['high']
        low = df['low']
        volume = df['volume']
        current_price = close.iloc[-1]

        # === Momentum ===
        rsi = self._calculate_rsi(close)
        rsi_divergence = self._detect_rsi_divergence(close, rsi)
        macd_line, macd_signal, macd_histogram = self._calculate_macd(close)
        macd_cross = self._detect_macd_cross(macd_line, macd_signal)

        # === Trend ===
        ema_50 = self._calculate_ema(close, 50)
        ema_200 = self._calculate_ema(close, 200)
        price_vs_ema50 = ((current_price - ema_50) / ema_50) * 100
        price_vs_ema200 = ((current_price - ema_200) / ema_200) * 100
        ema_alignment = 1 if ema_50 > ema_200 else -1

        # === Volatility ===
        bb_upper, bb_middle, bb_lower = self._calculate_bollinger(close)
        bb_position = self._calculate_bb_position(current_price, bb_upper, bb_lower)
        bb_width = (bb_upper - bb_lower) / bb_middle
        atr = self._calculate_atr(high, low, close)

        # === Volume ===
        volume_ratio = self._calculate_volume_ratio(volume)
        volume_trend = self._calculate_volume_trend(volume)
        obv = self._calculate_obv(close, volume)

        return TechnicalIndicators(
            # Momentum
            rsi_14=rsi,
            rsi_divergence=rsi_divergence,
            macd_line=macd_line,
            macd_signal=macd_signal,
            macd_histogram=macd_histogram,
            macd_cross=macd_cross,

            # Trend
            ema_50=ema_50,
            ema_200=ema_200,
            price_vs_ema50_pct=price_vs_ema50,
            price_vs_ema200_pct=price_vs_ema200,
            ema_alignment=ema_alignment,

            # Volatility
            bb_upper=bb_upper,
            bb_middle=bb_middle,
            bb_lower=bb_lower,
            bb_position=bb_position,
            bb_width=bb_width,
            atr_14=atr,

            # Volume
            volume_ratio=volume_ratio,
            volume_trend=volume_trend,
            obv=obv
        )

    # === RSI ===

    def _calculate_rsi(self, close: pd.Series, period: int = 14) -> float:
        """Calculate RSI (Relative Strength Index)."""
        rsi_indicator = RSIIndicator(close, window=period)
        rsi_series = rsi_indicator.rsi()
        return float(rsi_series.iloc[-1]) if not rsi_series.empty else 50.0

    def _detect_rsi_divergence(
        self,
        close: pd.Series,
        current_rsi: float,
        lookback: int = 14
    ) -> int:
        """
        Detect RSI divergence.

        Returns:
            1 = bullish divergence (price lower, RSI higher)
            -1 = bearish divergence (price higher, RSI lower)
            0 = no divergence
        """
        if len(close) < lookback * 2:
            return 0

        rsi_indicator = RSIIndicator(close, window=14)
        rsi_series = rsi_indicator.rsi()

        # Get values from lookback period
        price_now = close.iloc[-1]
        price_then = close.iloc[-lookback]
        rsi_now = rsi_series.iloc[-1]
        rsi_then = rsi_series.iloc[-lookback]

        # Bullish divergence: price makes lower low, RSI makes higher low
        if price_now < price_then and rsi_now > rsi_then:
            return 1

        # Bearish divergence: price makes higher high, RSI makes lower high
        if price_now > price_then and rsi_now < rsi_then:
            return -1

        return 0

    # === MACD ===

    def _calculate_macd(
        self,
        close: pd.Series,
        fast: int = 12,
        slow: int = 26,
        signal: int = 9
    ) -> Tuple[float, float, float]:
        """Calculate MACD line, signal line, and histogram."""
        macd_indicator = MACD(close, window_fast=fast, window_slow=slow, window_sign=signal)

        macd_line = macd_indicator.macd().iloc[-1]
        macd_signal = macd_indicator.macd_signal().iloc[-1]
        macd_histogram = macd_indicator.macd_diff().iloc[-1]

        return float(macd_line), float(macd_signal), float(macd_histogram)

    def _detect_macd_cross(
        self,
        macd_line: float,
        macd_signal: float,
        prev_macd_line: Optional[float] = None,
        prev_signal: Optional[float] = None
    ) -> int:
        """
        Detect MACD crossover.

        Returns:
            1 = bullish cross (MACD crosses above signal)
            -1 = bearish cross (MACD crosses below signal)
            0 = no cross
        """
        # Simple version: just check current position
        if macd_line > macd_signal:
            return 1
        elif macd_line < macd_signal:
            return -1
        return 0

    # === EMA ===

    def _calculate_ema(self, close: pd.Series, period: int) -> float:
        """Calculate Exponential Moving Average."""
        ema = EMAIndicator(close, window=period)
        return float(ema.ema_indicator().iloc[-1])

    # === Bollinger Bands ===

    def _calculate_bollinger(
        self,
        close: pd.Series,
        period: int = 20,
        std_dev: float = 2.0
    ) -> Tuple[float, float, float]:
        """Calculate Bollinger Bands."""
        bb = BollingerBands(close, window=period, window_dev=std_dev)

        upper = bb.bollinger_hband().iloc[-1]
        middle = bb.bollinger_mavg().iloc[-1]
        lower = bb.bollinger_lband().iloc[-1]

        return float(upper), float(middle), float(lower)

    def _calculate_bb_position(
        self,
        current_price: float,
        bb_upper: float,
        bb_lower: float
    ) -> float:
        """
        Calculate position within Bollinger Bands.

        Returns:
            0 = at lower band
            0.5 = at middle
            1 = at upper band
        """
        band_range = bb_upper - bb_lower
        if band_range == 0:
            return 0.5

        position = (current_price - bb_lower) / band_range
        return max(0, min(1, position))  # Clamp to [0, 1]

    # === ATR ===

    def _calculate_atr(
        self,
        high: pd.Series,
        low: pd.Series,
        close: pd.Series,
        period: int = 14
    ) -> float:
        """Calculate Average True Range."""
        atr = AverageTrueRange(high, low, close, window=period)
        return float(atr.average_true_range().iloc[-1])

    # === Volume ===

    def _calculate_volume_ratio(
        self,
        volume: pd.Series,
        period: int = 20
    ) -> float:
        """Calculate volume ratio vs average."""
        avg_volume = volume.rolling(window=period).mean().iloc[-1]
        current_volume = volume.iloc[-1]

        if avg_volume == 0:
            return 1.0

        return float(current_volume / avg_volume)

    def _calculate_volume_trend(
        self,
        volume: pd.Series,
        period: int = 5
    ) -> float:
        """Calculate volume trend (slope) over period."""
        if len(volume) < period:
            return 0.0

        recent_volumes = volume.iloc[-period:].values
        x = np.arange(period)

        # Linear regression slope
        slope = np.polyfit(x, recent_volumes, 1)[0]

        # Normalize by average volume
        avg = np.mean(recent_volumes)
        if avg == 0:
            return 0.0

        return float(slope / avg)

    def _calculate_obv(self, close: pd.Series, volume: pd.Series) -> float:
        """Calculate On-Balance Volume."""
        obv = OnBalanceVolumeIndicator(close, volume)
        return float(obv.on_balance_volume().iloc[-1])

    # === Additional Indicators ===

    def calculate_golden_cross(self, close: pd.Series) -> bool:
        """Check for Golden Cross (50 EMA > 200 EMA)."""
        ema_50 = self._calculate_ema(close, 50)
        ema_200 = self._calculate_ema(close, 200)
        return ema_50 > ema_200

    def calculate_death_cross(self, close: pd.Series) -> bool:
        """Check for Death Cross (50 EMA < 200 EMA)."""
        return not self.calculate_golden_cross(close)

    def is_oversold(self, close: pd.Series, threshold: float = 30) -> bool:
        """Check if RSI indicates oversold condition."""
        rsi = self._calculate_rsi(close)
        return rsi < threshold

    def is_overbought(self, close: pd.Series, threshold: float = 70) -> bool:
        """Check if RSI indicates overbought condition."""
        rsi = self._calculate_rsi(close)
        return rsi > threshold

    def is_bollinger_squeeze(
        self,
        close: pd.Series,
        threshold: float = 0.05
    ) -> bool:
        """Check if Bollinger Bands are squeezing (low volatility)."""
        upper, middle, lower = self._calculate_bollinger(close)
        width = (upper - lower) / middle
        return width < threshold


# Singleton instance
indicator_service = IndicatorService()
