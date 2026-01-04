"""
CoinTracker Pro - Exchange Service (Binance via CCXT)
Supports: Spot, Margin, and Futures trading
"""
import ccxt
import asyncio
from typing import Optional, List, Dict, Any, Literal
from datetime import datetime, timedelta
from loguru import logger
import pandas as pd

from app.config import get_settings
from app.models.schemas import (
    OHLCV, Ticker, OrderRequest, OrderResponse, Position,
    PortfolioSummary, OrderSide, OrderType
)


# Trading type options
TradingType = Literal["spot", "margin", "future"]


class ExchangeService:
    """
    Binance exchange integration via CCXT.

    Supports multiple trading types:
    - spot: Regular spot trading (buy/sell)
    - margin: Margin trading with leverage (cross margin)
    - future: Futures/perpetual contracts
    """

    def __init__(self):
        self.settings = get_settings()
        self._exchange: Optional[ccxt.binance] = None
        self._initialized = False
        self._trading_type: TradingType = "spot"
        self._leverage: int = 1

    async def initialize(self, trading_type: TradingType = None) -> None:
        """
        Initialize exchange connection.

        Args:
            trading_type: 'spot', 'margin', or 'future'
        """
        # If trading type changed, reinitialize
        if trading_type and trading_type != self._trading_type:
            self._initialized = False
            self._trading_type = trading_type

        if self._initialized:
            return

        config = {
            'apiKey': self.settings.binance_api_key,
            'secret': self.settings.binance_api_secret,
            'enableRateLimit': True,
            'options': {
                'defaultType': self._trading_type,
                'adjustForTimeDifference': True,
            }
        }

        # Margin-specific options
        if self._trading_type == 'margin':
            config['options']['defaultMarginMode'] = 'cross'  # or 'isolated'
            logger.info("Using Margin Trading (Cross Margin)")

        # Futures-specific options
        if self._trading_type == 'future':
            config['options']['defaultType'] = 'future'
            logger.info("Using Futures Trading")

        # Use testnet if configured
        if self.settings.binance_testnet:
            config['sandbox'] = True
            logger.info("Using Binance Testnet")

        self._exchange = ccxt.binance(config)

        # Load markets
        await asyncio.to_thread(self._exchange.load_markets)
        self._initialized = True
        logger.info(f"Exchange initialized ({self._trading_type}). Markets loaded: {len(self._exchange.markets)}")

    def set_trading_type(self, trading_type: TradingType) -> None:
        """
        Set trading type for next initialization.
        Call initialize() after to apply changes.
        """
        if trading_type not in ["spot", "margin", "future"]:
            raise ValueError(f"Invalid trading type: {trading_type}. Use 'spot', 'margin', or 'future'")
        self._trading_type = trading_type
        self._initialized = False  # Force re-initialization
        logger.info(f"Trading type set to: {trading_type}")

    def set_leverage(self, leverage: int) -> None:
        """Set leverage for margin/futures trading (1-125x)."""
        if leverage < 1 or leverage > 125:
            raise ValueError("Leverage must be between 1 and 125")
        self._leverage = leverage
        logger.info(f"Leverage set to: {leverage}x")

    @property
    def trading_type(self) -> TradingType:
        """Get current trading type."""
        return self._trading_type

    @property
    def leverage(self) -> int:
        """Get current leverage setting."""
        return self._leverage

    def get_trading_info(self) -> Dict[str, Any]:
        """Get current trading configuration."""
        return {
            "trading_type": self._trading_type,
            "leverage": self._leverage,
            "initialized": self._initialized,
            "testnet": self.settings.binance_testnet,
            "has_api_key": bool(self.settings.binance_api_key)
        }

    @property
    def exchange(self) -> ccxt.binance:
        """Get exchange instance."""
        if not self._initialized:
            raise RuntimeError("Exchange not initialized. Call initialize() first.")
        return self._exchange

    # === Market Data ===

    async def get_ticker(self, symbol: str) -> Ticker:
        """Get current ticker for a symbol."""
        await self.initialize()

        ticker = await asyncio.to_thread(self.exchange.fetch_ticker, symbol)

        return Ticker(
            symbol=symbol,
            price=ticker['last'],
            change_24h=ticker['change'] or 0,
            change_24h_pct=ticker['percentage'] or 0,
            volume_24h=ticker['quoteVolume'] or 0,
            high_24h=ticker['high'] or ticker['last'],
            low_24h=ticker['low'] or ticker['last'],
            timestamp=datetime.utcnow()
        )

    async def get_ohlcv(
        self,
        symbol: str,
        timeframe: str = '1h',
        limit: int = 100,
        since: Optional[datetime] = None
    ) -> List[OHLCV]:
        """Fetch OHLCV candlestick data."""
        await self.initialize()

        since_ts = int(since.timestamp() * 1000) if since else None

        ohlcv = await asyncio.to_thread(
            self.exchange.fetch_ohlcv,
            symbol, timeframe, since_ts, limit
        )

        return [
            OHLCV(
                timestamp=datetime.fromtimestamp(candle[0] / 1000),
                open=candle[1],
                high=candle[2],
                low=candle[3],
                close=candle[4],
                volume=candle[5]
            )
            for candle in ohlcv
        ]

    async def get_ohlcv_dataframe(
        self,
        symbol: str,
        timeframe: str = '1h',
        limit: int = 100
    ) -> pd.DataFrame:
        """Fetch OHLCV as pandas DataFrame."""
        ohlcv_list = await self.get_ohlcv(symbol, timeframe, limit)

        df = pd.DataFrame([
            {
                'timestamp': o.timestamp,
                'open': o.open,
                'high': o.high,
                'low': o.low,
                'close': o.close,
                'volume': o.volume
            }
            for o in ohlcv_list
        ])

        df.set_index('timestamp', inplace=True)
        return df

    async def get_multiple_tickers(self, symbols: List[str]) -> Dict[str, Ticker]:
        """Get tickers for multiple symbols."""
        await self.initialize()

        tickers = await asyncio.to_thread(self.exchange.fetch_tickers, symbols)

        result = {}
        for symbol, ticker in tickers.items():
            if symbol in symbols:
                result[symbol] = Ticker(
                    symbol=symbol,
                    price=ticker['last'],
                    change_24h=ticker['change'] or 0,
                    change_24h_pct=ticker['percentage'] or 0,
                    volume_24h=ticker['quoteVolume'] or 0,
                    high_24h=ticker['high'] or ticker['last'],
                    low_24h=ticker['low'] or ticker['last'],
                    timestamp=datetime.utcnow()
                )
        return result

    # === Account & Portfolio ===

    async def get_balance(self) -> Dict[str, float]:
        """Get account balances."""
        await self.initialize()

        if not self.settings.binance_api_key:
            logger.warning("No API key configured - returning mock balance")
            return {"USDT": 10000.0, "BTC": 0.0, "ETH": 0.0}

        balance = await asyncio.to_thread(self.exchange.fetch_balance)

        # Return only non-zero balances
        return {
            currency: data['total']
            for currency, data in balance['total'].items()
            if data and data > 0
        }

    async def get_portfolio_summary(self) -> PortfolioSummary:
        """Get complete portfolio overview."""
        await self.initialize()

        balances = await self.get_balance()
        positions = []
        total_value = 0.0

        for currency, amount in balances.items():
            if currency == 'USDT':
                total_value += amount
                continue

            if amount > 0:
                try:
                    symbol = f"{currency}/USDT"
                    ticker = await self.get_ticker(symbol)

                    position_value = amount * ticker.price
                    total_value += position_value

                    positions.append(Position(
                        symbol=symbol,
                        side=OrderSide.BUY,
                        amount=amount,
                        entry_price=ticker.price,  # Simplified - would need trade history
                        current_price=ticker.price,
                        unrealized_pnl=0,  # Would need entry price
                        unrealized_pnl_pct=0
                    ))
                except Exception as e:
                    logger.warning(f"Could not get price for {currency}: {e}")

        return PortfolioSummary(
            total_value_usdt=total_value,
            available_usdt=balances.get('USDT', 0),
            positions=positions,
            total_pnl=0,  # Would need trade history
            total_pnl_pct=0
        )

    # === Trading ===

    async def place_order(self, order: OrderRequest) -> OrderResponse:
        """Place a trading order."""
        await self.initialize()

        if not self.settings.binance_api_key:
            logger.warning("No API key - simulating order")
            ticker = await self.get_ticker(order.symbol)
            return OrderResponse(
                order_id="SIMULATED_" + str(int(datetime.utcnow().timestamp())),
                symbol=order.symbol,
                side=order.side,
                order_type=order.order_type,
                amount=order.amount,
                price=ticker.price,
                status="SIMULATED",
                timestamp=datetime.utcnow()
            )

        try:
            # Prepare order parameters
            order_params = {}

            if order.order_type == OrderType.MARKET:
                result = await asyncio.to_thread(
                    self.exchange.create_market_order if order.side == OrderSide.BUY
                    else self.exchange.create_market_sell_order,
                    order.symbol,
                    order.amount
                )
            elif order.order_type == OrderType.LIMIT:
                result = await asyncio.to_thread(
                    self.exchange.create_limit_order,
                    order.symbol,
                    order.side.value.lower(),
                    order.amount,
                    order.price
                )
            else:
                raise ValueError(f"Unsupported order type: {order.order_type}")

            return OrderResponse(
                order_id=result['id'],
                symbol=order.symbol,
                side=order.side,
                order_type=order.order_type,
                amount=result['amount'],
                price=result['price'] or result['average'] or 0,
                status=result['status'],
                timestamp=datetime.utcnow()
            )

        except Exception as e:
            logger.error(f"Order failed: {e}")
            raise

    async def cancel_order(self, order_id: str, symbol: str) -> bool:
        """Cancel an open order."""
        await self.initialize()

        try:
            await asyncio.to_thread(self.exchange.cancel_order, order_id, symbol)
            return True
        except Exception as e:
            logger.error(f"Cancel order failed: {e}")
            return False

    async def get_open_orders(self, symbol: Optional[str] = None) -> List[Dict]:
        """Get all open orders."""
        await self.initialize()

        orders = await asyncio.to_thread(self.exchange.fetch_open_orders, symbol)
        return orders

    # === Historical Data ===

    async def fetch_historical_data(
        self,
        symbol: str,
        timeframe: str = '1h',
        days: int = 30
    ) -> pd.DataFrame:
        """Fetch historical OHLCV data for a period."""
        await self.initialize()

        all_candles = []
        since = datetime.utcnow() - timedelta(days=days)

        while since < datetime.utcnow():
            candles = await self.get_ohlcv(
                symbol, timeframe, limit=1000,
                since=since
            )

            if not candles:
                break

            all_candles.extend(candles)
            since = candles[-1].timestamp + timedelta(hours=1)

            # Rate limiting
            await asyncio.sleep(0.1)

        df = pd.DataFrame([
            {
                'timestamp': c.timestamp,
                'open': c.open,
                'high': c.high,
                'low': c.low,
                'close': c.close,
                'volume': c.volume
            }
            for c in all_candles
        ])

        if not df.empty:
            df.set_index('timestamp', inplace=True)
            df = df[~df.index.duplicated(keep='first')]

        return df


# Singleton instance
exchange_service = ExchangeService()
