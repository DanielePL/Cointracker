"""
CoinTracker Pro - Data Collector Service
Continuously collects and stores market data for ML training.
"""
import asyncio
from datetime import datetime, timedelta
from typing import List, Optional
from loguru import logger
import pandas as pd

from app.services.exchange import exchange_service
from app.services.indicators import indicator_service
from app.services.fear_greed import fear_greed_service
from app.models.schemas import FeatureVector
from app.config import get_settings


class DataCollectorService:
    """
    Collects and processes market data for ML training and signal generation.
    """

    def __init__(self):
        self.settings = get_settings()
        self._running = False
        self._collection_interval = 3600  # 1 hour in seconds

    async def collect_features(self, symbol: str) -> Optional[FeatureVector]:
        """
        Collect all features for a symbol at current time.
        This creates a complete feature vector for ML prediction.
        """
        try:
            # Get OHLCV data
            df = await exchange_service.get_ohlcv_dataframe(
                symbol, timeframe='1h', limit=250
            )

            if df.empty:
                logger.warning(f"No OHLCV data for {symbol}")
                return None

            # Calculate technical indicators
            indicators = indicator_service.calculate_all(df)

            # Get Fear & Greed Index
            fear_greed = await fear_greed_service.get_with_changes()

            now = datetime.utcnow()

            # Create feature vector
            feature = FeatureVector(
                timestamp=now,
                symbol=symbol,

                # Technical indicators
                rsi_14=indicators.rsi_14,
                rsi_divergence=indicators.rsi_divergence,
                macd_histogram=indicators.macd_histogram,
                macd_cross=indicators.macd_cross,
                price_vs_ema50=indicators.price_vs_ema50_pct,
                price_vs_ema200=indicators.price_vs_ema200_pct,
                ema_alignment=indicators.ema_alignment,
                bb_position=indicators.bb_position,
                bb_width=indicators.bb_width,
                atr_normalized=indicators.atr_14 / df['close'].iloc[-1],  # Normalize by price
                volume_ratio=indicators.volume_ratio,
                volume_trend=indicators.volume_trend,
                obv_divergence=0,  # Would need more logic

                # Sentiment
                fear_greed_index=fear_greed.value,
                fear_greed_change_24h=fear_greed.change_24h or 0,
                fear_greed_change_7d=fear_greed.change_7d or 0,
                fear_greed_extreme=1 if fear_greed.value < 20 else (2 if fear_greed.value > 80 else 0),

                # Context
                hour_of_day=now.hour,
                day_of_week=now.weekday(),
                is_weekend=now.weekday() >= 5
            )

            return feature

        except Exception as e:
            logger.error(f"Failed to collect features for {symbol}: {e}")
            return None

    async def collect_all_symbols(self) -> List[FeatureVector]:
        """Collect features for all configured symbols."""
        features = []

        for symbol in self.settings.supported_pairs:
            feature = await self.collect_features(symbol)
            if feature:
                features.append(feature)
            await asyncio.sleep(0.5)  # Rate limiting

        return features

    async def start_continuous_collection(self):
        """
        Start continuous data collection in background.
        Collects data every hour for ML training.
        """
        if self._running:
            logger.warning("Data collector already running")
            return

        self._running = True
        logger.info("Starting continuous data collection...")

        while self._running:
            try:
                logger.info("Collecting feature snapshots...")
                features = await self.collect_all_symbols()
                logger.info(f"Collected {len(features)} feature snapshots")

                # TODO: Save to database for training
                # await self._save_features(features)

            except Exception as e:
                logger.error(f"Collection error: {e}")

            # Wait for next collection
            await asyncio.sleep(self._collection_interval)

    def stop_collection(self):
        """Stop continuous collection."""
        self._running = False
        logger.info("Data collection stopped")

    async def get_training_data(
        self,
        symbol: str,
        days: int = 30
    ) -> pd.DataFrame:
        """
        Get training data for ML model.
        Returns DataFrame with features and labels (future price changes).
        """
        # Fetch historical OHLCV
        df = await exchange_service.fetch_historical_data(
            symbol, timeframe='1h', days=days
        )

        if df.empty:
            return pd.DataFrame()

        # Calculate indicators for each row
        training_data = []

        for i in range(200, len(df)):  # Need 200 for EMA200
            window_df = df.iloc[:i+1]

            try:
                indicators = indicator_service.calculate_all(window_df)

                # Calculate future price changes (labels)
                current_price = df['close'].iloc[i]

                price_1h = df['close'].iloc[i+1] if i+1 < len(df) else None
                price_24h = df['close'].iloc[i+24] if i+24 < len(df) else None
                price_7d = df['close'].iloc[i+168] if i+168 < len(df) else None

                change_1h = ((price_1h - current_price) / current_price * 100) if price_1h else None
                change_24h = ((price_24h - current_price) / current_price * 100) if price_24h else None
                change_7d = ((price_7d - current_price) / current_price * 100) if price_7d else None

                training_data.append({
                    'timestamp': df.index[i],
                    'price': current_price,

                    # Features
                    'rsi_14': indicators.rsi_14,
                    'macd_histogram': indicators.macd_histogram,
                    'bb_position': indicators.bb_position,
                    'bb_width': indicators.bb_width,
                    'ema_alignment': indicators.ema_alignment,
                    'volume_ratio': indicators.volume_ratio,
                    'atr_normalized': indicators.atr_14 / current_price,

                    # Labels
                    'change_1h': change_1h,
                    'change_24h': change_24h,
                    'change_7d': change_7d,

                    # Classification labels
                    'direction_1h': 1 if change_1h and change_1h > 0.5 else (-1 if change_1h and change_1h < -0.5 else 0) if change_1h else None,
                    'direction_24h': 1 if change_24h and change_24h > 2 else (-1 if change_24h and change_24h < -2 else 0) if change_24h else None,
                })

            except Exception as e:
                logger.debug(f"Skipping row {i}: {e}")
                continue

        return pd.DataFrame(training_data)


# Singleton instance
data_collector = DataCollectorService()
