"""
Auto-Trading Engine
Executes trades based on ML signals with risk management
"""

import asyncio
from typing import Dict, List, Optional
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from loguru import logger
import uuid

from app.services.exchange import exchange_service
from app.ml.hybrid_model import ModelPrediction
from app.services.notification_service import notification_service


class OrderSide(str, Enum):
    BUY = "buy"
    SELL = "sell"


class OrderType(str, Enum):
    MARKET = "market"
    LIMIT = "limit"
    STOP_LOSS = "stop_loss"
    TAKE_PROFIT = "take_profit"


class OrderStatus(str, Enum):
    PENDING = "pending"
    OPEN = "open"
    FILLED = "filled"
    CANCELLED = "cancelled"
    FAILED = "failed"


@dataclass
class TradeOrder:
    id: str
    symbol: str
    side: OrderSide
    order_type: OrderType
    amount: float
    price: Optional[float] = None
    stop_loss: Optional[float] = None
    take_profit: Optional[float] = None
    status: OrderStatus = OrderStatus.PENDING
    filled_price: Optional[float] = None
    filled_amount: Optional[float] = None
    exchange_order_id: Optional[str] = None
    signal_score: int = 0
    signal_reasons: List[str] = field(default_factory=list)
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)
    error: Optional[str] = None


@dataclass
class Position:
    symbol: str
    side: OrderSide
    entry_price: float
    amount: float
    current_price: float
    unrealized_pnl: float
    unrealized_pnl_pct: float
    stop_loss: Optional[float] = None
    take_profit: Optional[float] = None
    opened_at: datetime = field(default_factory=datetime.utcnow)


@dataclass
class TradingConfig:
    """Trading strategy configuration"""
    # Risk Management
    max_position_size_pct: float = 5.0      # Max % of portfolio per trade
    max_open_positions: int = 5              # Max concurrent positions
    stop_loss_pct: float = 3.0               # Default stop loss %
    take_profit_pct: float = 6.0             # Default take profit %
    trailing_stop_pct: Optional[float] = 2.0 # Trailing stop %

    # Signal Thresholds
    min_signal_score: int = 65               # Minimum score to trade
    min_confidence: float = 0.6              # Minimum confidence

    # Execution
    use_limit_orders: bool = False           # Use limit vs market orders
    limit_order_offset_pct: float = 0.1      # Offset from current price for limits

    # Safety
    enabled: bool = False                    # Master switch
    testnet_only: bool = True                # Only trade on testnet
    daily_loss_limit_pct: float = 5.0        # Max daily loss %
    cooldown_after_loss: int = 300           # Seconds to wait after loss


class TradingEngine:
    """
    Automated trading engine with risk management

    Features:
    - Position sizing based on risk
    - Stop-loss and take-profit orders
    - Trailing stops
    - Daily loss limits
    - Trade journaling
    """

    def __init__(self, config: Optional[TradingConfig] = None):
        self.config = config or TradingConfig()
        self.orders: Dict[str, TradeOrder] = {}
        self.positions: Dict[str, Position] = {}
        self.daily_pnl: float = 0.0
        self.daily_pnl_reset: datetime = datetime.utcnow()
        self.last_loss_time: Optional[datetime] = None
        self.trade_history: List[TradeOrder] = []

    async def process_signal(
        self,
        symbol: str,
        prediction: ModelPrediction,
        current_price: float
    ) -> Optional[TradeOrder]:
        """
        Process ML signal and potentially execute trade

        Args:
            symbol: Trading pair (e.g., "BTC/USDT")
            prediction: ML model prediction
            current_price: Current market price

        Returns:
            TradeOrder if trade was executed, None otherwise
        """
        # Safety checks
        if not self.config.enabled:
            logger.debug("Trading engine disabled")
            return None

        if not self._check_safety_conditions():
            return None

        # Check signal quality
        if not self._should_trade(prediction):
            logger.debug(f"Signal not strong enough: score={prediction.score}, confidence={prediction.confidence}")
            return None

        # Check if we already have a position
        if symbol in self.positions:
            return await self._manage_existing_position(symbol, prediction, current_price)

        # Open new position
        return await self._open_position(symbol, prediction, current_price)

    def _check_safety_conditions(self) -> bool:
        """Check all safety conditions before trading"""
        # Reset daily PnL if new day
        now = datetime.utcnow()
        if now.date() > self.daily_pnl_reset.date():
            self.daily_pnl = 0.0
            self.daily_pnl_reset = now

        # Check daily loss limit
        if self.daily_pnl < -self.config.daily_loss_limit_pct:
            logger.warning(f"Daily loss limit reached: {self.daily_pnl:.2f}%")
            return False

        # Check cooldown after loss
        if self.last_loss_time:
            cooldown_end = self.last_loss_time + timedelta(seconds=self.config.cooldown_after_loss)
            if now < cooldown_end:
                logger.debug(f"In cooldown period until {cooldown_end}")
                return False

        # Check max positions
        if len(self.positions) >= self.config.max_open_positions:
            logger.debug(f"Max positions reached: {len(self.positions)}")
            return False

        return True

    def _should_trade(self, prediction: ModelPrediction) -> bool:
        """Determine if signal is strong enough to trade"""
        # Must meet minimum score
        if prediction.score < self.config.min_signal_score and prediction.score > (100 - self.config.min_signal_score):
            return False

        # Must meet minimum confidence
        if prediction.confidence < self.config.min_confidence:
            return False

        # Must have clear direction (not HOLD)
        if prediction.signal == "HOLD":
            return False

        return True

    async def _open_position(
        self,
        symbol: str,
        prediction: ModelPrediction,
        current_price: float
    ) -> Optional[TradeOrder]:
        """Open a new position based on signal"""
        # Determine side
        side = OrderSide.BUY if "BUY" in prediction.signal else OrderSide.SELL

        # Calculate position size
        amount = await self._calculate_position_size(symbol, current_price)
        if amount <= 0:
            logger.warning("Calculated position size is 0")
            return None

        # Calculate stop loss and take profit
        if side == OrderSide.BUY:
            stop_loss = current_price * (1 - self.config.stop_loss_pct / 100)
            take_profit = current_price * (1 + self.config.take_profit_pct / 100)
        else:
            stop_loss = current_price * (1 + self.config.stop_loss_pct / 100)
            take_profit = current_price * (1 - self.config.take_profit_pct / 100)

        # Create order
        order = TradeOrder(
            id=str(uuid.uuid4()),
            symbol=symbol,
            side=side,
            order_type=OrderType.MARKET,
            amount=amount,
            price=current_price,
            stop_loss=stop_loss,
            take_profit=take_profit,
            signal_score=prediction.score,
            signal_reasons=prediction.top_reasons
        )

        # Execute order
        success = await self._execute_order(order)

        if success:
            # Track position
            self.positions[symbol] = Position(
                symbol=symbol,
                side=side,
                entry_price=order.filled_price or current_price,
                amount=order.filled_amount or amount,
                current_price=current_price,
                unrealized_pnl=0.0,
                unrealized_pnl_pct=0.0,
                stop_loss=stop_loss,
                take_profit=take_profit
            )
            logger.info(f"Opened {side.value} position for {symbol} at {current_price}")

        return order if success else None

    async def _manage_existing_position(
        self,
        symbol: str,
        prediction: ModelPrediction,
        current_price: float
    ) -> Optional[TradeOrder]:
        """Manage existing position - potentially close or adjust"""
        position = self.positions[symbol]

        # Update current price and PnL
        position.current_price = current_price
        if position.side == OrderSide.BUY:
            position.unrealized_pnl = (current_price - position.entry_price) * position.amount
            position.unrealized_pnl_pct = ((current_price / position.entry_price) - 1) * 100
        else:
            position.unrealized_pnl = (position.entry_price - current_price) * position.amount
            position.unrealized_pnl_pct = ((position.entry_price / current_price) - 1) * 100

        # Check stop loss
        if position.stop_loss:
            if position.side == OrderSide.BUY and current_price <= position.stop_loss:
                logger.warning(f"Stop loss triggered for {symbol}")
                return await self._close_position(symbol, "stop_loss")
            elif position.side == OrderSide.SELL and current_price >= position.stop_loss:
                logger.warning(f"Stop loss triggered for {symbol}")
                return await self._close_position(symbol, "stop_loss")

        # Check take profit
        if position.take_profit:
            if position.side == OrderSide.BUY and current_price >= position.take_profit:
                logger.info(f"Take profit triggered for {symbol}")
                return await self._close_position(symbol, "take_profit")
            elif position.side == OrderSide.SELL and current_price <= position.take_profit:
                logger.info(f"Take profit triggered for {symbol}")
                return await self._close_position(symbol, "take_profit")

        # Check for signal reversal
        current_is_buy = position.side == OrderSide.BUY
        signal_is_buy = "BUY" in prediction.signal

        if current_is_buy != signal_is_buy and prediction.score >= self.config.min_signal_score:
            logger.info(f"Signal reversal detected for {symbol}")
            return await self._close_position(symbol, "signal_reversal")

        # Update trailing stop if applicable
        if self.config.trailing_stop_pct:
            self._update_trailing_stop(position, current_price)

        return None

    def _update_trailing_stop(self, position: Position, current_price: float):
        """Update trailing stop based on price movement"""
        if position.side == OrderSide.BUY:
            new_stop = current_price * (1 - self.config.trailing_stop_pct / 100)
            if position.stop_loss and new_stop > position.stop_loss:
                position.stop_loss = new_stop
                logger.debug(f"Trailing stop updated to {new_stop} for {position.symbol}")
        else:
            new_stop = current_price * (1 + self.config.trailing_stop_pct / 100)
            if position.stop_loss and new_stop < position.stop_loss:
                position.stop_loss = new_stop

    async def _close_position(self, symbol: str, reason: str) -> Optional[TradeOrder]:
        """Close an existing position"""
        if symbol not in self.positions:
            return None

        position = self.positions[symbol]

        # Create closing order (opposite side)
        close_side = OrderSide.SELL if position.side == OrderSide.BUY else OrderSide.BUY

        order = TradeOrder(
            id=str(uuid.uuid4()),
            symbol=symbol,
            side=close_side,
            order_type=OrderType.MARKET,
            amount=position.amount,
            signal_reasons=[f"Closing position: {reason}"]
        )

        success = await self._execute_order(order)

        if success:
            # Update daily PnL
            self.daily_pnl += position.unrealized_pnl_pct

            if position.unrealized_pnl < 0:
                self.last_loss_time = datetime.utcnow()

            # Remove position
            del self.positions[symbol]
            logger.info(f"Closed position for {symbol}: {reason}, PnL: {position.unrealized_pnl_pct:.2f}%")

        return order if success else None

    async def _calculate_position_size(self, symbol: str, current_price: float) -> float:
        """Calculate position size based on risk management rules"""
        try:
            # Get portfolio value
            portfolio = await exchange_service.get_portfolio()
            total_usdt = portfolio.get("total_usdt", 0)

            if total_usdt <= 0:
                return 0

            # Calculate max position value
            max_value = total_usdt * (self.config.max_position_size_pct / 100)

            # Calculate amount
            amount = max_value / current_price

            # Round to appropriate precision
            return round(amount, 6)

        except Exception as e:
            logger.error(f"Failed to calculate position size: {e}")
            return 0

    async def _execute_order(self, order: TradeOrder) -> bool:
        """Execute order on exchange"""
        try:
            if self.config.testnet_only:
                # Simulate order execution
                order.status = OrderStatus.FILLED
                order.filled_price = order.price
                order.filled_amount = order.amount
                order.exchange_order_id = f"sim_{order.id}"
                logger.info(f"[TESTNET] Simulated order: {order.side.value} {order.amount} {order.symbol}")
            else:
                # Real order execution
                result = await exchange_service.create_order(
                    symbol=order.symbol,
                    order_type=order.order_type.value,
                    side=order.side.value,
                    amount=order.amount,
                    price=order.price
                )

                if result.get("status") == "closed":
                    order.status = OrderStatus.FILLED
                    order.filled_price = result.get("average", order.price)
                    order.filled_amount = result.get("filled", order.amount)
                    order.exchange_order_id = result.get("id")
                else:
                    order.status = OrderStatus.OPEN
                    order.exchange_order_id = result.get("id")

            order.updated_at = datetime.utcnow()
            self.orders[order.id] = order
            self.trade_history.append(order)
            return True

        except Exception as e:
            order.status = OrderStatus.FAILED
            order.error = str(e)
            order.updated_at = datetime.utcnow()
            self.orders[order.id] = order
            logger.error(f"Order execution failed: {e}")
            return False

    def get_status(self) -> Dict:
        """Get current trading engine status"""
        return {
            "enabled": self.config.enabled,
            "testnet_only": self.config.testnet_only,
            "open_positions": len(self.positions),
            "max_positions": self.config.max_open_positions,
            "daily_pnl_pct": self.daily_pnl,
            "daily_loss_limit_pct": self.config.daily_loss_limit_pct,
            "positions": [
                {
                    "symbol": p.symbol,
                    "side": p.side.value,
                    "entry_price": p.entry_price,
                    "current_price": p.current_price,
                    "unrealized_pnl_pct": p.unrealized_pnl_pct,
                    "stop_loss": p.stop_loss,
                    "take_profit": p.take_profit
                }
                for p in self.positions.values()
            ],
            "recent_trades": len(self.trade_history)
        }


# Global instance
trading_engine = TradingEngine()


# ==================== SUPABASE AUTONOMOUS BOT ====================
import os

try:
    from supabase import create_client, Client
    SUPABASE_URL = os.getenv("SUPABASE_URL", "https://iyenuoujyruaotydjjqg.supabase.co")
    SUPABASE_KEY = os.getenv("SUPABASE_SERVICE_KEY", "")
    supabase_client: Optional[Client] = create_client(SUPABASE_URL, SUPABASE_KEY) if SUPABASE_KEY else None
except ImportError:
    supabase_client = None
    logger.warning("Supabase not installed for trading bot")


@dataclass
class BotSettings:
    """Bot configuration from database - optimiert für kurzfristiges Trading"""
    min_signal_score: int = 65
    max_position_size_percent: float = 20.0
    max_positions: int = 5
    stop_loss_percent: float = -2.5      # Engerer Stop-Loss für schnelle Trades
    take_profit_percent: float = 3.0      # Realistisches Take-Profit
    trailing_stop_percent: float = 1.5    # Trailing Stop aktiviert bei Profit
    required_confidence: float = 0.6
    min_volume_24h: float = 1000000.0
    enabled_coins: List[str] = field(default_factory=lambda: ['BTC', 'ETH', 'SOL', 'XRP', 'ADA'])
    is_active: bool = True


@dataclass
class BotPosition:
    """Current bot position"""
    coin: str
    side: str
    quantity: float
    entry_price: float
    current_price: float
    total_invested: float
    unrealized_pnl: float
    unrealized_pnl_percent: float
    stop_loss: Optional[float]
    take_profit: Optional[float]


@dataclass
class BotBalance:
    """Bot balance state"""
    balance_usdt: float
    initial_balance: float
    total_pnl: float
    total_pnl_percent: float
    total_trades: int
    winning_trades: int
    losing_trades: int


class SupabaseTradingBot:
    """
    Autonomous Trading Bot using Supabase

    Features:
    - Trades based on ML signals from analysis_logs
    - Uses virtual balance ($10k start)
    - Logs all trades to bot_trades table
    - Tracks positions in bot_positions table
    - Learns from past trades
    """

    def __init__(self):
        self.client = supabase_client
        self.settings: Optional[BotSettings] = None
        self.balance: Optional[BotBalance] = None
        self.positions: Dict[str, BotPosition] = {}

    async def initialize(self) -> bool:
        """Load settings and current state from Supabase"""
        if not self.client:
            logger.error("Supabase client not available")
            return False

        try:
            # Load settings
            settings_result = self.client.table("bot_settings").select("*").limit(1).execute()
            if settings_result.data:
                s = settings_result.data[0]
                self.settings = BotSettings(
                    min_signal_score=s.get('min_signal_score', 65),
                    max_position_size_percent=s.get('max_position_size_percent', 20.0),
                    max_positions=s.get('max_positions', 5),
                    stop_loss_percent=s.get('stop_loss_percent', -2.5),
                    take_profit_percent=s.get('take_profit_percent', 3.0),
                    trailing_stop_percent=s.get('trailing_stop_percent', 1.5),
                    required_confidence=s.get('required_confidence', 0.6),
                    min_volume_24h=s.get('min_volume_24h', 1000000.0),
                    enabled_coins=s.get('enabled_coins', ['BTC', 'ETH', 'SOL', 'XRP', 'ADA']),
                    is_active=s.get('is_active', True)
                )
            else:
                self.settings = BotSettings()

            # Load balance
            balance_result = self.client.table("bot_balance").select("*").limit(1).execute()
            if balance_result.data:
                b = balance_result.data[0]
                self.balance = BotBalance(
                    balance_usdt=b.get('balance_usdt', 10000.0),
                    initial_balance=b.get('initial_balance', 10000.0),
                    total_pnl=b.get('total_pnl', 0),
                    total_pnl_percent=b.get('total_pnl_percent', 0),
                    total_trades=b.get('total_trades', 0),
                    winning_trades=b.get('winning_trades', 0),
                    losing_trades=b.get('losing_trades', 0)
                )
            else:
                self.balance = BotBalance(
                    balance_usdt=10000.0,
                    initial_balance=10000.0,
                    total_pnl=0,
                    total_pnl_percent=0,
                    total_trades=0,
                    winning_trades=0,
                    losing_trades=0
                )

            # Load current positions
            positions_result = self.client.table("bot_positions").select("*").execute()
            self.positions = {}
            for p in positions_result.data:
                self.positions[p['coin']] = BotPosition(
                    coin=p['coin'],
                    side=p.get('side', 'LONG'),
                    quantity=p['quantity'],
                    entry_price=p['entry_price'],
                    current_price=p.get('current_price', p['entry_price']),
                    total_invested=p['total_invested'],
                    unrealized_pnl=p.get('unrealized_pnl', 0),
                    unrealized_pnl_percent=p.get('unrealized_pnl_percent', 0),
                    stop_loss=p.get('stop_loss'),
                    take_profit=p.get('take_profit')
                )

            logger.info(f"Bot initialized: Balance=${self.balance.balance_usdt:.2f}, Positions={len(self.positions)}")
            return True

        except Exception as e:
            logger.error(f"Failed to initialize bot: {e}")
            return False

    def _should_buy(self, signal: str, score: int, confidence: float, volume_24h: float, coin: str = "") -> bool:
        """Determine if we should buy based on signal"""
        if not self.settings.is_active:
            logger.debug(f"[{coin}] Skipping buy - bot not active")
            return False

        if signal not in ["STRONG_BUY", "BUY"]:
            logger.debug(f"[{coin}] Skipping buy - signal={signal} not BUY")
            return False

        if score < self.settings.min_signal_score:
            logger.debug(f"[{coin}] Skipping buy - score={score} < min={self.settings.min_signal_score}")
            return False

        if confidence < self.settings.required_confidence:
            logger.debug(f"[{coin}] Skipping buy - confidence={confidence} < required={self.settings.required_confidence}")
            return False

        if volume_24h < self.settings.min_volume_24h:
            logger.debug(f"[{coin}] Skipping buy - volume={volume_24h} < min={self.settings.min_volume_24h}")
            return False

        logger.info(f"[{coin}] BUY conditions met: signal={signal}, score={score}, conf={confidence:.2f}")
        return True

    def _should_sell(self, position: BotPosition, current_price: float, signal: str, score: int) -> tuple:
        """
        Determine if we should sell and why

        Returns: (should_sell: bool, reason: str)
        """
        # Check stop loss
        pnl_percent = ((current_price / position.entry_price) - 1) * 100
        if pnl_percent <= self.settings.stop_loss_percent:
            return True, "STOP_LOSS"

        # Check take profit
        if pnl_percent >= self.settings.take_profit_percent:
            return True, "TAKE_PROFIT"

        # Check signal reversal
        if signal in ["STRONG_SELL", "SELL"] and score <= (100 - self.settings.min_signal_score):
            return True, "SIGNAL_REVERSAL"

        return False, ""

    def _calculate_position_size(self, price: float) -> float:
        """Calculate how much to buy based on balance and settings"""
        if not self.balance:
            return 0

        # Max position value
        max_value = self.balance.balance_usdt * (self.settings.max_position_size_percent / 100)

        # Don't exceed available balance
        max_value = min(max_value, self.balance.balance_usdt * 0.95)  # Keep 5% reserve

        if max_value < 10:  # Minimum trade value
            return 0

        # Calculate quantity
        quantity = max_value / price
        return round(quantity, 8)

    async def execute_trade(
        self,
        coin: str,
        side: str,  # "BUY" or "SELL"
        quantity: float,
        price: float,
        signal_type: str,
        signal_score: int,
        signal_reasons: List[str],
        rsi: Optional[float] = None,
        macd: Optional[float] = None
    ) -> Dict:
        """Execute a trade via Supabase RPC function"""
        if not self.client:
            return {"success": False, "error": "Supabase not available"}

        try:
            # Calculate stop loss and take profit for BUY orders
            stop_loss = None
            take_profit = None
            if side == "BUY":
                stop_loss = price * (1 + self.settings.stop_loss_percent / 100)
                take_profit = price * (1 + self.settings.take_profit_percent / 100)

            # Call the Supabase function
            result = self.client.rpc("execute_bot_trade", {
                "p_coin": coin,
                "p_side": side,
                "p_quantity": quantity,
                "p_price": price,
                "p_signal_type": signal_type,
                "p_signal_score": signal_score,
                "p_signal_reasons": signal_reasons,
                "p_rsi": rsi,
                "p_macd": macd,
                "p_stop_loss": stop_loss,
                "p_take_profit": take_profit
            }).execute()

            trade_result = result.data if result.data else {"success": False, "error": "No response"}

            logger.info(f"Trade executed: {side} {quantity} {coin} @ ${price:.4f} - {trade_result}")
            return trade_result

        except Exception as e:
            logger.error(f"Trade execution failed: {e}")
            return {"success": False, "error": str(e)}

    async def process_analysis_results(self, analysis_results: List[Dict]) -> Dict:
        """
        Process analysis results and execute trades

        Args:
            analysis_results: List of analysis results from analysis_logs

        Returns:
            Summary of actions taken
        """
        if not await self.initialize():
            return {"error": "Bot initialization failed"}

        if not self.settings.is_active:
            logger.info("Bot is not active, skipping trade processing")
            return {"status": "inactive", "trades": []}

        logger.info(f"Processing {len(analysis_results)} analysis results")
        logger.info(f"Bot settings: min_score={self.settings.min_signal_score}, enabled_coins={self.settings.enabled_coins}")
        logger.info(f"Current positions: {list(self.positions.keys())}, max={self.settings.max_positions}")

        actions = []
        trades_executed = 0
        buys = 0
        sells = 0

        for result in analysis_results:
            # Handle both 'coin' (from Supabase) and 'symbol' (from AnalysisResult) keys
            raw_coin = result.get('coin') or result.get('symbol', '')
            coin = raw_coin.replace('USDT', '')

            if not coin:
                logger.debug(f"Skipping result with no coin: {result}")
                continue

            if coin not in self.settings.enabled_coins:
                logger.debug(f"Skipping {coin} - not in enabled_coins: {self.settings.enabled_coins}")
                continue

            price = result.get('price', 0)
            signal = result.get('ml_signal', 'HOLD')
            score = int(result.get('ml_score', 50))
            confidence = result.get('ml_confidence', 0.5)
            volume_24h = result.get('volume_24h', 0)
            reasons = result.get('top_reasons', [])
            rsi = result.get('rsi')
            macd = result.get('macd')

            # Check if we have an existing position
            if coin in self.positions:
                position = self.positions[coin]

                # Check if we should sell
                should_sell, sell_reason = self._should_sell(position, price, signal, score)

                if should_sell:
                    trade_result = await self.execute_trade(
                        coin=coin,
                        side="SELL",
                        quantity=position.quantity,
                        price=price,
                        signal_type=signal,
                        signal_score=score,
                        signal_reasons=reasons + [f"Close: {sell_reason}"],
                        rsi=rsi,
                        macd=macd
                    )

                    if trade_result.get('success'):
                        trades_executed += 1
                        sells += 1
                        pnl = trade_result.get('pnl', 0)
                        pnl_percent = trade_result.get('pnl_percent', 0)
                        actions.append({
                            "action": "SELL",
                            "coin": coin,
                            "reason": sell_reason,
                            "pnl": pnl,
                            "pnl_percent": pnl_percent
                        })
                        # Remove from local positions
                        del self.positions[coin]

                        # Send notification
                        await notification_service.notify_profit_loss(
                            coin=coin,
                            pnl=pnl,
                            pnl_percent=pnl_percent,
                            reason=sell_reason.upper().replace(" ", "_")
                        )

            else:
                # No position - check if we should buy
                if len(self.positions) >= self.settings.max_positions:
                    continue

                if self._should_buy(signal, score, confidence, volume_24h, coin):
                    quantity = self._calculate_position_size(price)

                    if quantity > 0:
                        trade_result = await self.execute_trade(
                            coin=coin,
                            side="BUY",
                            quantity=quantity,
                            price=price,
                            signal_type=signal,
                            signal_score=score,
                            signal_reasons=reasons,
                            rsi=rsi,
                            macd=macd
                        )

                        if trade_result.get('success'):
                            trades_executed += 1
                            buys += 1
                            total_value = quantity * price
                            actions.append({
                                "action": "BUY",
                                "coin": coin,
                                "quantity": quantity,
                                "price": price,
                                "signal": signal,
                                "score": score
                            })

                            # Send notification
                            await notification_service.notify_trade_executed(
                                action="BUY",
                                coin=coin,
                                amount=total_value,
                                price=price
                            )

                            # Also notify for strong signals
                            if signal in ["STRONG_BUY", "STRONG_SELL"]:
                                await notification_service.notify_strong_signal(
                                    coin=coin,
                                    signal=signal,
                                    score=score
                                )

        # Update last run timestamp
        if self.client:
            try:
                self.client.table("bot_settings").update({
                    "last_run_at": datetime.utcnow().isoformat()
                }).eq("id", 1).execute()
            except Exception as e:
                logger.warning(f"Failed to update last_run_at: {e}")

        # Reload balance after trades
        await self.initialize()

        summary = {
            "timestamp": datetime.utcnow().isoformat(),
            "trades_executed": trades_executed,
            "buys": buys,
            "sells": sells,
            "actions": actions,
            "balance": self.balance.balance_usdt if self.balance else 0,
            "total_pnl": self.balance.total_pnl if self.balance else 0,
            "open_positions": len(self.positions)
        }

        logger.info(f"Bot run complete: {trades_executed} trades (B:{buys}/S:{sells}), Balance: ${self.balance.balance_usdt:.2f}")
        return summary

    async def get_status(self) -> Dict:
        """Get current bot status"""
        await self.initialize()

        return {
            "is_active": self.settings.is_active if self.settings else False,
            "balance": {
                "current": self.balance.balance_usdt if self.balance else 0,
                "initial": self.balance.initial_balance if self.balance else 10000,
                "total_pnl": self.balance.total_pnl if self.balance else 0,
                "total_pnl_percent": self.balance.total_pnl_percent if self.balance else 0,
                "total_trades": self.balance.total_trades if self.balance else 0,
                "win_rate": (self.balance.winning_trades / self.balance.total_trades * 100
                            if self.balance and self.balance.total_trades > 0 else 0)
            },
            "positions": [
                {
                    "coin": p.coin,
                    "side": p.side,
                    "quantity": p.quantity,
                    "entry_price": p.entry_price,
                    "unrealized_pnl_percent": p.unrealized_pnl_percent
                }
                for p in self.positions.values()
            ],
            "settings": {
                "min_signal_score": self.settings.min_signal_score if self.settings else 65,
                "max_positions": self.settings.max_positions if self.settings else 5,
                "enabled_coins": self.settings.enabled_coins if self.settings else []
            }
        }


# Global bot instance
autonomous_bot = SupabaseTradingBot()
