"""
Sentiment Aggregator - Combines multiple sentiment sources
- Fear & Greed Index
- LunarCrush Social Sentiment
- CryptoCompare News Sentiment
- Reddit/Twitter mentions (via free APIs)
"""

import asyncio
import httpx
from typing import Dict, List, Optional
from dataclasses import dataclass
from datetime import datetime, timedelta
from loguru import logger
import statistics


@dataclass
class SentimentScore:
    source: str
    value: float  # -1 to 1 (bearish to bullish)
    confidence: float  # 0 to 1
    raw_value: any
    timestamp: datetime


@dataclass
class AggregatedSentiment:
    overall_score: float  # -1 to 1
    overall_label: str  # "Extreme Fear", "Fear", "Neutral", "Greed", "Extreme Greed"
    confidence: float
    sources: List[SentimentScore]
    bullish_factors: List[str]
    bearish_factors: List[str]
    timestamp: datetime


class FearGreedProvider:
    """Alternative.me Fear & Greed Index"""

    API_URL = "https://api.alternative.me/fng/"

    async def get_sentiment(self) -> Optional[SentimentScore]:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(f"{self.API_URL}?limit=1", timeout=10)
                data = response.json()

                if data.get("data"):
                    fg_data = data["data"][0]
                    value = int(fg_data["value"])

                    # Convert 0-100 to -1 to 1
                    normalized = (value - 50) / 50

                    return SentimentScore(
                        source="fear_greed_index",
                        value=normalized,
                        confidence=0.9,  # High confidence - widely used indicator
                        raw_value={"value": value, "label": fg_data["value_classification"]},
                        timestamp=datetime.utcnow()
                    )
        except Exception as e:
            logger.error(f"Fear & Greed API error: {e}")
        return None


class CoinGeckoSentimentProvider:
    """CoinGecko trending and sentiment data (free)"""

    API_URL = "https://api.coingecko.com/api/v3"

    async def get_sentiment(self, coin_id: str = "bitcoin") -> Optional[SentimentScore]:
        try:
            async with httpx.AsyncClient() as client:
                # Get coin data with community data
                response = await client.get(
                    f"{self.API_URL}/coins/{coin_id}",
                    params={
                        "localization": "false",
                        "tickers": "false",
                        "market_data": "true",
                        "community_data": "true",
                        "developer_data": "false"
                    },
                    timeout=10
                )
                data = response.json()

                # Calculate sentiment from various metrics
                sentiment_votes_up = data.get("sentiment_votes_up_percentage", 50)

                # Normalize to -1 to 1
                normalized = (sentiment_votes_up - 50) / 50

                return SentimentScore(
                    source="coingecko_community",
                    value=normalized,
                    confidence=0.6,
                    raw_value={
                        "votes_up_pct": sentiment_votes_up,
                        "community_score": data.get("community_score", 0)
                    },
                    timestamp=datetime.utcnow()
                )
        except Exception as e:
            logger.error(f"CoinGecko API error: {e}")
        return None


class CryptoCompareNewsProvider:
    """CryptoCompare News API for news sentiment"""

    API_URL = "https://min-api.cryptocompare.com/data/v2/news/"

    # Simple keyword-based sentiment (can be enhanced with NLP)
    BULLISH_KEYWORDS = [
        "surge", "rally", "bullish", "breakout", "soar", "jump", "gain",
        "adoption", "partnership", "upgrade", "launch", "approval", "etf",
        "institutional", "buy", "accumulate", "moon", "ath", "high"
    ]

    BEARISH_KEYWORDS = [
        "crash", "dump", "bearish", "plunge", "drop", "fall", "decline",
        "hack", "scam", "fraud", "ban", "regulation", "sec", "lawsuit",
        "sell", "fear", "panic", "liquidation", "low"
    ]

    async def get_sentiment(self, categories: str = "BTC") -> Optional[SentimentScore]:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(
                    self.API_URL,
                    params={"categories": categories, "lang": "EN"},
                    timeout=10
                )
                data = response.json()

                if data.get("Data"):
                    news_items = data["Data"][:20]  # Last 20 news items

                    bullish_count = 0
                    bearish_count = 0
                    total_analyzed = 0

                    for item in news_items:
                        title = item.get("title", "").lower()
                        body = item.get("body", "").lower()
                        text = title + " " + body

                        # Count keyword matches
                        bullish_matches = sum(1 for kw in self.BULLISH_KEYWORDS if kw in text)
                        bearish_matches = sum(1 for kw in self.BEARISH_KEYWORDS if kw in text)

                        if bullish_matches > bearish_matches:
                            bullish_count += 1
                        elif bearish_matches > bullish_matches:
                            bearish_count += 1

                        total_analyzed += 1

                    if total_analyzed > 0:
                        # Calculate sentiment score
                        net_sentiment = (bullish_count - bearish_count) / total_analyzed

                        return SentimentScore(
                            source="cryptocompare_news",
                            value=net_sentiment,
                            confidence=0.5,  # Lower confidence for simple keyword analysis
                            raw_value={
                                "bullish_news": bullish_count,
                                "bearish_news": bearish_count,
                                "neutral_news": total_analyzed - bullish_count - bearish_count,
                                "total_analyzed": total_analyzed
                            },
                            timestamp=datetime.utcnow()
                        )
        except Exception as e:
            logger.error(f"CryptoCompare News API error: {e}")
        return None


class RedditSentimentProvider:
    """Reddit sentiment via Pushshift/public APIs"""

    # Using a simple approach - can be enhanced with actual Reddit API
    SUBREDDITS = ["cryptocurrency", "bitcoin", "ethtrader"]

    async def get_sentiment(self) -> Optional[SentimentScore]:
        # Placeholder - would need Reddit API credentials for real implementation
        # Returns neutral for now
        return SentimentScore(
            source="reddit",
            value=0.0,
            confidence=0.3,
            raw_value={"status": "api_key_required"},
            timestamp=datetime.utcnow()
        )


class SentimentAggregator:
    """
    Aggregates sentiment from multiple sources and provides
    weighted overall sentiment score
    """

    # Source weights based on reliability and relevance
    SOURCE_WEIGHTS = {
        "fear_greed_index": 0.35,      # Most reliable, widely used
        "coingecko_community": 0.20,   # Community votes
        "cryptocompare_news": 0.25,    # News sentiment
        "reddit": 0.10,                # Social media
        "twitter": 0.10,               # Social media
    }

    def __init__(self):
        self.fear_greed = FearGreedProvider()
        self.coingecko = CoinGeckoSentimentProvider()
        self.news = CryptoCompareNewsProvider()
        self.reddit = RedditSentimentProvider()

        self._cache: Optional[AggregatedSentiment] = None
        self._cache_time: Optional[datetime] = None
        self._cache_ttl = timedelta(minutes=5)

    async def get_aggregated_sentiment(self, symbol: str = "BTC") -> AggregatedSentiment:
        """
        Get aggregated sentiment from all sources
        """
        # Check cache
        if self._cache and self._cache_time:
            if datetime.utcnow() - self._cache_time < self._cache_ttl:
                return self._cache

        # Fetch from all sources in parallel
        results = await asyncio.gather(
            self.fear_greed.get_sentiment(),
            self.coingecko.get_sentiment(self._symbol_to_coingecko(symbol)),
            self.news.get_sentiment(symbol),
            self.reddit.get_sentiment(),
            return_exceptions=True
        )

        # Filter successful results
        scores: List[SentimentScore] = []
        for result in results:
            if isinstance(result, SentimentScore):
                scores.append(result)

        # Calculate weighted average
        if not scores:
            return AggregatedSentiment(
                overall_score=0.0,
                overall_label="Neutral",
                confidence=0.0,
                sources=[],
                bullish_factors=[],
                bearish_factors=[],
                timestamp=datetime.utcnow()
            )

        weighted_sum = 0.0
        weight_total = 0.0

        for score in scores:
            weight = self.SOURCE_WEIGHTS.get(score.source, 0.1)
            weighted_sum += score.value * weight * score.confidence
            weight_total += weight * score.confidence

        overall_score = weighted_sum / weight_total if weight_total > 0 else 0.0

        # Generate factors
        bullish_factors = []
        bearish_factors = []

        for score in scores:
            if score.value > 0.2:
                bullish_factors.append(f"{score.source}: +{score.value:.2f}")
            elif score.value < -0.2:
                bearish_factors.append(f"{score.source}: {score.value:.2f}")

        # Determine label
        label = self._score_to_label(overall_score)

        aggregated = AggregatedSentiment(
            overall_score=overall_score,
            overall_label=label,
            confidence=weight_total / len(scores) if scores else 0.0,
            sources=scores,
            bullish_factors=bullish_factors,
            bearish_factors=bearish_factors,
            timestamp=datetime.utcnow()
        )

        # Cache result
        self._cache = aggregated
        self._cache_time = datetime.utcnow()

        return aggregated

    def _symbol_to_coingecko(self, symbol: str) -> str:
        """Convert trading symbol to CoinGecko ID"""
        mapping = {
            "BTC": "bitcoin",
            "ETH": "ethereum",
            "SOL": "solana",
            "XRP": "ripple",
            "ADA": "cardano",
            "DOGE": "dogecoin",
            "DOT": "polkadot",
            "MATIC": "matic-network",
            "LINK": "chainlink",
            "AVAX": "avalanche-2"
        }
        base = symbol.split("/")[0] if "/" in symbol else symbol
        return mapping.get(base, "bitcoin")

    def _score_to_label(self, score: float) -> str:
        """Convert -1 to 1 score to human label"""
        if score <= -0.6:
            return "Extreme Fear"
        elif score <= -0.2:
            return "Fear"
        elif score <= 0.2:
            return "Neutral"
        elif score <= 0.6:
            return "Greed"
        else:
            return "Extreme Greed"


# Global instance
sentiment_aggregator = SentimentAggregator()
