"""
CoinTracker Pro - Fear & Greed Index Service
Fetches sentiment data from Alternative.me API
"""
import httpx
from datetime import datetime, timedelta
from typing import Optional, List
from loguru import logger
import asyncio

from app.config import get_settings
from app.models.schemas import FearGreedIndex, SentimentData


class FearGreedService:
    """
    Fetch Fear & Greed Index from Alternative.me.

    The Fear and Greed Index ranges from 0-100:
    - 0-24: Extreme Fear
    - 25-49: Fear
    - 50-54: Neutral
    - 55-74: Greed
    - 75-100: Extreme Greed
    """

    def __init__(self):
        self.settings = get_settings()
        self.api_url = self.settings.fear_greed_api_url
        self._cache: Optional[FearGreedIndex] = None
        self._cache_time: Optional[datetime] = None
        self._cache_duration = timedelta(minutes=15)  # Cache for 15 min

    async def get_current(self) -> FearGreedIndex:
        """Get current Fear & Greed Index value."""
        # Check cache
        if self._is_cache_valid():
            return self._cache

        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(
                    self.api_url,
                    params={"limit": 1, "format": "json"}
                )
                response.raise_for_status()
                data = response.json()

            if "data" not in data or not data["data"]:
                raise ValueError("Invalid API response")

            current = data["data"][0]

            result = FearGreedIndex(
                value=int(current["value"]),
                value_classification=current["value_classification"],
                timestamp=datetime.fromtimestamp(int(current["timestamp"]))
            )

            # Update cache
            self._cache = result
            self._cache_time = datetime.utcnow()

            logger.info(f"Fear & Greed Index: {result.value} ({result.value_classification})")
            return result

        except Exception as e:
            logger.error(f"Failed to fetch Fear & Greed Index: {e}")
            # Return cached value if available, otherwise default
            if self._cache:
                return self._cache
            return FearGreedIndex(
                value=50,
                value_classification="Neutral",
                timestamp=datetime.utcnow()
            )

    async def get_historical(self, days: int = 30) -> List[FearGreedIndex]:
        """Get historical Fear & Greed Index values."""
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(
                    self.api_url,
                    params={"limit": days, "format": "json"}
                )
                response.raise_for_status()
                data = response.json()

            if "data" not in data:
                return []

            return [
                FearGreedIndex(
                    value=int(item["value"]),
                    value_classification=item["value_classification"],
                    timestamp=datetime.fromtimestamp(int(item["timestamp"]))
                )
                for item in data["data"]
            ]

        except Exception as e:
            logger.error(f"Failed to fetch historical Fear & Greed: {e}")
            return []

    async def get_with_changes(self) -> FearGreedIndex:
        """Get current value with 24h and 7d changes."""
        historical = await self.get_historical(days=8)

        if not historical:
            return await self.get_current()

        current = historical[0]

        # Calculate changes
        change_24h = None
        change_7d = None

        if len(historical) >= 2:
            change_24h = current.value - historical[1].value

        if len(historical) >= 8:
            change_7d = current.value - historical[7].value

        return FearGreedIndex(
            value=current.value,
            value_classification=current.value_classification,
            timestamp=current.timestamp,
            change_24h=change_24h,
            change_7d=change_7d
        )

    def classify_value(self, value: int) -> str:
        """Classify a Fear & Greed value."""
        if value <= 24:
            return "Extreme Fear"
        elif value <= 49:
            return "Fear"
        elif value <= 54:
            return "Neutral"
        elif value <= 74:
            return "Greed"
        else:
            return "Extreme Greed"

    def is_extreme_fear(self, value: int) -> bool:
        """Check if value indicates extreme fear (potential buy signal)."""
        return value <= 20

    def is_extreme_greed(self, value: int) -> bool:
        """Check if value indicates extreme greed (potential sell signal)."""
        return value >= 80

    def _is_cache_valid(self) -> bool:
        """Check if cached value is still valid."""
        if self._cache is None or self._cache_time is None:
            return False
        return datetime.utcnow() - self._cache_time < self._cache_duration


class SentimentService:
    """
    Aggregated sentiment from multiple sources.
    Combines Fear & Greed with other sentiment APIs.
    """

    def __init__(self):
        self.fear_greed = FearGreedService()
        self.settings = get_settings()

    async def get_combined_sentiment(self) -> SentimentData:
        """Get combined sentiment from all sources."""
        fear_greed = await self.fear_greed.get_with_changes()

        # TODO: Add more sentiment sources
        # - LunarCrush for social sentiment
        # - CryptoCompare for news sentiment
        # - On-chain sentiment indicators

        return SentimentData(
            fear_greed=fear_greed,
            news_sentiment=None,  # To be implemented
            social_sentiment=None,  # To be implemented
            social_volume_spike=None  # To be implemented
        )

    async def get_sentiment_score(self) -> float:
        """
        Get normalized sentiment score from -1 to 1.

        -1 = Extreme Fear
         0 = Neutral
         1 = Extreme Greed
        """
        sentiment = await self.get_combined_sentiment()
        fg_value = sentiment.fear_greed.value

        # Normalize from 0-100 to -1 to 1
        normalized = (fg_value - 50) / 50

        return normalized


# Singleton instances
fear_greed_service = FearGreedService()
sentiment_service = SentimentService()
