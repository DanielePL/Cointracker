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
