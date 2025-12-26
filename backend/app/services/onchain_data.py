"""
On-Chain Data Provider
Tracks whale movements, exchange flows, and other blockchain metrics
"""

import asyncio
import httpx
from typing import Dict, List, Optional
from dataclasses import dataclass
from datetime import datetime, timedelta
from loguru import logger


@dataclass
class WhaleTransaction:
    """Large transaction detected on-chain"""
    tx_hash: str
    blockchain: str
    symbol: str
    amount: float
    amount_usd: float
    from_address: str
    to_address: str
    from_label: Optional[str]  # "Binance", "Unknown Wallet", etc.
    to_label: Optional[str]
    timestamp: datetime
    is_exchange_inflow: bool   # True if going TO exchange
    is_exchange_outflow: bool  # True if coming FROM exchange


@dataclass
class ExchangeFlow:
    """Net flow of assets to/from exchanges"""
    symbol: str
    exchange: str
    inflow_24h: float          # Amount flowing INTO exchange
    outflow_24h: float         # Amount flowing OUT OF exchange
    netflow_24h: float         # Positive = more inflow (bearish)
    inflow_change_pct: float   # Change vs previous 24h
    timestamp: datetime


@dataclass
class OnChainMetrics:
    """Aggregated on-chain metrics"""
    symbol: str

    # Exchange flows
    total_exchange_netflow: float    # Positive = bearish
    exchange_reserve: float          # Total on exchanges
    exchange_reserve_change_24h: float

    # Whale activity
    whale_transactions_24h: int
    whale_volume_24h: float
    whale_accumulation: float        # Net whale buying (negative = selling)

    # Network activity
    active_addresses_24h: int
    transaction_count_24h: int
    avg_transaction_value: float

    # Holder distribution
    addresses_holding_1plus: int
    addresses_holding_10plus: int
    addresses_holding_100plus: int
    addresses_holding_1000plus: int

    timestamp: datetime

    # Interpretation
    signal: str                      # "bullish", "bearish", "neutral"
    reasons: List[str]


class WhaleAlertProvider:
    """
    Whale Alert API for large transaction tracking
    Free tier: 10 requests/minute
    """

    API_URL = "https://api.whale-alert.io/v1"

    def __init__(self, api_key: Optional[str] = None):
        self.api_key = api_key

    async def get_recent_transactions(
        self,
        min_value_usd: int = 1_000_000,
        limit: int = 100
    ) -> List[WhaleTransaction]:
        """Get recent large transactions"""
        if not self.api_key:
            logger.debug("Whale Alert API key not configured")
            return self._get_mock_transactions()

        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(
                    f"{self.API_URL}/transactions",
                    params={
                        "api_key": self.api_key,
                        "min_value": min_value_usd,
                        "limit": limit,
                        "currency": "btc,eth"
                    },
                    timeout=10
                )
                data = response.json()

                transactions = []
                for tx in data.get("transactions", []):
                    transactions.append(self._parse_transaction(tx))

                return transactions

        except Exception as e:
            logger.error(f"Whale Alert API error: {e}")
            return self._get_mock_transactions()

    def _parse_transaction(self, tx: Dict) -> WhaleTransaction:
        """Parse Whale Alert transaction format"""
        from_owner = tx.get("from", {}).get("owner", "unknown")
        to_owner = tx.get("to", {}).get("owner", "unknown")

        is_inflow = to_owner.lower() in ["binance", "coinbase", "kraken", "huobi", "okex", "bitfinex"]
        is_outflow = from_owner.lower() in ["binance", "coinbase", "kraken", "huobi", "okex", "bitfinex"]

        return WhaleTransaction(
            tx_hash=tx.get("hash", ""),
            blockchain=tx.get("blockchain", ""),
            symbol=tx.get("symbol", "").upper(),
            amount=tx.get("amount", 0),
            amount_usd=tx.get("amount_usd", 0),
            from_address=tx.get("from", {}).get("address", ""),
            to_address=tx.get("to", {}).get("address", ""),
            from_label=from_owner,
            to_label=to_owner,
            timestamp=datetime.fromtimestamp(tx.get("timestamp", 0)),
            is_exchange_inflow=is_inflow,
            is_exchange_outflow=is_outflow
        )

    def _get_mock_transactions(self) -> List[WhaleTransaction]:
        """Return mock transactions for demo"""
        return [
            WhaleTransaction(
                tx_hash="mock_tx_1",
                blockchain="bitcoin",
                symbol="BTC",
                amount=500,
                amount_usd=50_000_000,
                from_address="bc1q...",
                to_address="bc1q...",
                from_label="Unknown Wallet",
                to_label="Coinbase",
                timestamp=datetime.utcnow() - timedelta(hours=2),
                is_exchange_inflow=True,
                is_exchange_outflow=False
            ),
            WhaleTransaction(
                tx_hash="mock_tx_2",
                blockchain="bitcoin",
                symbol="BTC",
                amount=1200,
                amount_usd=120_000_000,
                from_address="bc1q...",
                to_address="bc1q...",
                from_label="Binance",
                to_label="Unknown Wallet",
                timestamp=datetime.utcnow() - timedelta(hours=5),
                is_exchange_inflow=False,
                is_exchange_outflow=True
            ),
        ]


class CryptoQuantProvider:
    """
    CryptoQuant-style on-chain metrics
    Using free alternatives and estimations
    """

    COINGLASS_URL = "https://open-api.coinglass.com/public/v2"

    async def get_exchange_flows(self, symbol: str = "BTC") -> Optional[ExchangeFlow]:
        """Get exchange flow data"""
        # This would require paid API access
        # Using mock data for demonstration
        return ExchangeFlow(
            symbol=symbol,
            exchange="all",
            inflow_24h=15000,
            outflow_24h=18000,
            netflow_24h=-3000,  # Negative = bullish (more outflow)
            inflow_change_pct=-5.2,
            timestamp=datetime.utcnow()
        )

    async def get_metrics(self, symbol: str = "BTC") -> OnChainMetrics:
        """Get aggregated on-chain metrics"""
        # In production, this would aggregate data from multiple sources
        # Using mock/estimated data for demonstration

        # Simulate some on-chain data
        netflow = -2500  # Negative = bullish
        whale_accumulation = 1500  # Positive = whales buying

        reasons = []
        signal = "neutral"

        # Analyze netflow
        if netflow < -1000:
            reasons.append(f"Exchange outflow of {abs(netflow)} {symbol} (bullish)")
            signal = "bullish"
        elif netflow > 1000:
            reasons.append(f"Exchange inflow of {netflow} {symbol} (bearish)")
            signal = "bearish"

        # Analyze whale activity
        if whale_accumulation > 500:
            reasons.append(f"Whales accumulated {whale_accumulation} {symbol}")
            if signal != "bearish":
                signal = "bullish"
        elif whale_accumulation < -500:
            reasons.append(f"Whales sold {abs(whale_accumulation)} {symbol}")
            if signal != "bullish":
                signal = "bearish"

        return OnChainMetrics(
            symbol=symbol,
            total_exchange_netflow=netflow,
            exchange_reserve=2_100_000,
            exchange_reserve_change_24h=-0.3,
            whale_transactions_24h=45,
            whale_volume_24h=125_000_000,
            whale_accumulation=whale_accumulation,
            active_addresses_24h=950_000,
            transaction_count_24h=350_000,
            avg_transaction_value=25_000,
            addresses_holding_1plus=1_000_000,
            addresses_holding_10plus=150_000,
            addresses_holding_100plus=15_000,
            addresses_holding_1000plus=2_000,
            timestamp=datetime.utcnow(),
            signal=signal,
            reasons=reasons
        )


class BlockchainComProvider:
    """
    Blockchain.com API for basic Bitcoin on-chain data
    Free, no API key required
    """

    API_URL = "https://api.blockchain.info"

    async def get_stats(self) -> Dict:
        """Get Bitcoin network statistics"""
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(
                    f"{self.API_URL}/stats",
                    timeout=10
                )
                return response.json()
        except Exception as e:
            logger.error(f"Blockchain.com API error: {e}")
            return {}

    async def get_mempool_info(self) -> Dict:
        """Get mempool information"""
        try:
            async with httpx.AsyncClient() as client:
                response = await client.get(
                    f"{self.API_URL}/mempool",
                    timeout=10
                )
                return response.json()
        except Exception as e:
            logger.error(f"Mempool API error: {e}")
            return {}


class OnChainDataAggregator:
    """
    Aggregates on-chain data from multiple sources
    """

    def __init__(self, whale_alert_key: Optional[str] = None):
        self.whale_alert = WhaleAlertProvider(whale_alert_key)
        self.crypto_quant = CryptoQuantProvider()
        self.blockchain_com = BlockchainComProvider()

        self._cache: Dict[str, OnChainMetrics] = {}
        self._cache_time: Dict[str, datetime] = {}
        self._cache_ttl = timedelta(minutes=15)

    async def get_onchain_analysis(self, symbol: str = "BTC") -> OnChainMetrics:
        """
        Get comprehensive on-chain analysis for a symbol
        """
        # Check cache
        if symbol in self._cache and symbol in self._cache_time:
            if datetime.utcnow() - self._cache_time[symbol] < self._cache_ttl:
                return self._cache[symbol]

        # Fetch data from all sources
        results = await asyncio.gather(
            self.whale_alert.get_recent_transactions(),
            self.crypto_quant.get_metrics(symbol),
            self.blockchain_com.get_stats() if symbol == "BTC" else asyncio.sleep(0),
            return_exceptions=True
        )

        whale_txs = results[0] if isinstance(results[0], list) else []
        metrics = results[1] if isinstance(results[1], OnChainMetrics) else await self.crypto_quant.get_metrics(symbol)

        # Enhance metrics with whale transaction analysis
        if whale_txs:
            inflow_volume = sum(tx.amount_usd for tx in whale_txs if tx.is_exchange_inflow)
            outflow_volume = sum(tx.amount_usd for tx in whale_txs if tx.is_exchange_outflow)

            if outflow_volume > inflow_volume * 1.5:
                metrics.reasons.append(f"Large whale outflows detected (${outflow_volume/1e6:.1f}M)")
            elif inflow_volume > outflow_volume * 1.5:
                metrics.reasons.append(f"Large whale inflows to exchanges (${inflow_volume/1e6:.1f}M)")

        # Cache result
        self._cache[symbol] = metrics
        self._cache_time[symbol] = datetime.utcnow()

        return metrics

    async def get_whale_alerts(
        self,
        symbol: str = "BTC",
        min_value_usd: int = 10_000_000
    ) -> List[WhaleTransaction]:
        """Get recent whale transactions for a symbol"""
        all_txs = await self.whale_alert.get_recent_transactions(min_value_usd)
        return [tx for tx in all_txs if tx.symbol == symbol]


# Global instance
onchain_data = OnChainDataAggregator()
