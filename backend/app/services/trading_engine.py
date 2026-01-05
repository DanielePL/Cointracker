"""
Auto-Trading Engine
Executes trades based on ML signals with risk management
"""

import asyncio
import httpx
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


# =============================================
# SELF-LEARNING SYSTEM
# =============================================

# Cache for trail multipliers (refreshed every hour)
_trail_multiplier_cache: Dict[str, float] = {"normal": 1.0, "bullrun": 1.0}
_trail_multiplier_cache_time: Optional[datetime] = None
MULTIPLIER_CACHE_HOURS = 1


def get_trail_multipliers() -> Dict[str, float]:
    """
    Get trail multipliers from bot_tuning table (cached).
    These adjust based on learning from past exits.
    """
    global _trail_multiplier_cache, _trail_multiplier_cache_time

    # Return cached if still valid
    if _trail_multiplier_cache_time:
        cache_age = datetime.utcnow() - _trail_multiplier_cache_time
        if cache_age < timedelta(hours=MULTIPLIER_CACHE_HOURS):
            return _trail_multiplier_cache

    # Fetch from DB
    if supabase_client:
        try:
            result = supabase_client.table("bot_tuning").select("*").limit(1).execute()
            if result.data:
                tuning = result.data[0]
                _trail_multiplier_cache = {
                    "normal": float(tuning.get("trail_multiplier", 1.0)),
                    "bullrun": float(tuning.get("bullrun_trail_multiplier", 1.0))
                }
                _trail_multiplier_cache_time = datetime.utcnow()
                logger.debug(f"Trail multipliers loaded: {_trail_multiplier_cache}")
        except Exception as e:
            logger.warning(f"Failed to load trail multipliers: {e}")

    return _trail_multiplier_cache


async def record_exit_for_learning(
    coin: str,
    exit_price: float,
    exit_reason: str,
    exit_pnl_percent: float,
    trade_id: Optional[int] = None
) -> bool:
    """
    Record an exit for post-trade learning analysis.

    The system will track what happens after we exit to learn
    if our trailing stops are too tight or too loose.
    """
    if not supabase_client:
        return False

    try:
        supabase_client.table("bot_exit_analysis").insert({
            "coin": coin,
            "trade_id": trade_id,
            "exit_price": exit_price,
            "exit_reason": exit_reason,
            "exit_pnl_percent": exit_pnl_percent,
            "exit_at": datetime.utcnow().isoformat(),
            "analysis_complete": False
        }).execute()

        logger.info(f"[LEARNING] Recorded exit for {coin}: {exit_reason} at {exit_pnl_percent:.2f}%")
        return True

    except Exception as e:
        logger.warning(f"Failed to record exit for learning: {e}")
        return False


@dataclass
class BotSettings:
    """Bot configuration from database - optimiert für kurzfristiges Trading"""
    min_signal_score: int = 65
    max_position_size_percent: float = 20.0
    max_positions: int = 15  # Trade up to 15 positions simultaneously
    stop_loss_percent: float = -2.5      # Engerer Stop-Loss für schnelle Trades
    take_profit_percent: float = 3.0      # Realistisches Take-Profit
    trailing_stop_percent: float = 1.5    # Trailing Stop aktiviert bei Profit
    required_confidence: float = 0.6
    min_volume_24h: float = 1000000.0
    enabled_coins: List[str] = field(default_factory=lambda: [
        'BTC', 'ETH', 'SOL', 'XRP', 'ADA',
        'DOGE', 'AVAX', 'LINK', 'MATIC', 'DOT',
        'UNI', 'ATOM', 'LTC', 'APT', 'STX'
    ])
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
    highest_price: Optional[float] = None  # Track highest price for trailing stop
    trailing_stop: Optional[float] = None  # Current trailing stop price
    is_bullrun: bool = False  # Bullrun coins get slightly wider trailing stops


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


def calculate_tiered_trailing_stop_percent(profit_percent: float, is_bullrun: bool = False) -> float:
    """
    Calculate dynamic trailing stop percentage based on current profit.

    HYBRID APPROACH:
    - Normal coins: TIGHT stops - secure what you have
    - Bullrun coins: Slightly wider - let momentum play out

    SELF-LEARNING:
    - Multipliers from bot_tuning are applied based on past exit analysis
    - If we exit too early too often, multipliers increase (wider trails)
    - If exits are optimal, multipliers stay at 1.0

    Philosophy: "Was ich habe, habe ich" - lock in real gains over potential gains.

    Args:
        profit_percent: Current unrealized profit in percent (e.g., 14.5 for +14.5%)
        is_bullrun: True if coin has bullrun signals (gets slightly more room)

    Returns:
        Trailing stop percentage to use (e.g., 1.5 for 1.5%), adjusted by learning multiplier
    """
    # Get learned multipliers from bot_tuning (cached)
    multipliers = get_trail_multipliers()
    multiplier = multipliers["bullrun"] if is_bullrun else multipliers["normal"]

    if is_bullrun:
        # BULLRUN COINS: Slightly wider trails to capture momentum
        if profit_percent < 5:
            base_trail = 1.0   # Early profit - still relatively tight
        elif profit_percent < 10:
            base_trail = 1.8   # Building gains - some room
        elif profit_percent < 20:
            base_trail = 2.5   # Strong gains - let it run a bit
        else:
            base_trail = 3.5   # Exceptional - but still lock most in
    else:
        # NORMAL COINS: Tight stops - maximize certainty
        if profit_percent < 3:
            base_trail = 0.5   # Protect breakeven aggressively
        elif profit_percent < 5:
            base_trail = 0.8   # Small profit - keep it tight
        elif profit_percent < 10:
            base_trail = 1.2   # Moderate profit - still tight
        else:
            base_trail = 1.5   # Good profit - secure almost all of it

    # Apply learned multiplier (from bot_tuning, adjusted by exit analysis)
    # Multiplier > 1.0 = wider trails (if we exit too early too often)
    # Multiplier < 1.0 = tighter trails (if exits are optimal)
    adjusted_trail = base_trail * multiplier

    # Cap at reasonable bounds (0.3% min, 8% max)
    return max(0.3, min(8.0, adjusted_trail))


class SupabaseTradingBot:
    """
    Autonomous Trading Bot using Supabase

    Features:
    - Trades based on ML signals from analysis_logs
    - Uses virtual balance ($10k start)
    - Logs all trades to bot_trades table
    - Tracks positions in bot_positions table
    - Learns from past trades
    - Dynamic tiered trailing stops based on profit level
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
                entry_price = p['entry_price']
                highest_price = p.get('highest_price', entry_price)
                self.positions[p['coin']] = BotPosition(
                    coin=p['coin'],
                    side=p.get('side', 'LONG'),
                    quantity=p['quantity'],
                    entry_price=entry_price,
                    current_price=p.get('current_price', entry_price),
                    total_invested=p['total_invested'],
                    unrealized_pnl=p.get('unrealized_pnl', 0),
                    unrealized_pnl_percent=p.get('unrealized_pnl_percent', 0),
                    stop_loss=p.get('stop_loss'),
                    take_profit=p.get('take_profit'),
                    highest_price=highest_price,
                    trailing_stop=p.get('trailing_stop'),
                    is_bullrun=p.get('is_bullrun', False)
                )

            logger.info(f"Bot initialized: Balance=${self.balance.balance_usdt:.2f}, Positions={len(self.positions)}")
            return True

        except Exception as e:
            logger.error(f"Failed to initialize bot: {e}")
            return False

    def _should_buy(self, signal: str, score: int, confidence: float, volume_24h: float, coin: str = "",
                    price: float = 0, ema_200: Optional[float] = None,
                    adx: Optional[float] = None, volume_ratio: Optional[float] = None,
                    timeframes_aligned: bool = True, higher_tf_trend: str = "NEUTRAL",
                    market_regime: str = "UNKNOWN", is_favorable_regime: bool = True,
                    bullrun_score: int = 0, is_bullrun: bool = False) -> bool:
        """
        Determine if we should buy based on multiple filters.

        Filters applied:
        1. Basic: signal type, score, confidence, volume
        2. EMA200 Trend Filter: Only buy when price > EMA200 (uptrend)
        3. ADX Trend Strength: Only buy when ADX > 20 (avoid sideways markets)
        4. Volume Ratio: Only buy when volume_ratio > 0.5 (confirm market interest)
        5. Multi-Timeframe: Only buy when 1h and 4h trends are aligned
        6. Market Regime: Only buy in favorable market regimes (TRENDING_UP)
        7. Bullrun Boost: Lower thresholds for coins in bullrun (score >= 65, research-backed)
        """
        if not self.settings.is_active:
            logger.debug(f"[{coin}] Skipping buy - bot not active")
            return False

        if signal not in ["STRONG_BUY", "BUY"]:
            logger.debug(f"[{coin}] Skipping buy - signal={signal} not BUY")
            return False

        # ============ BULLRUN BOOST ============
        # For coins in a bullrun, we boost the signal score and lower thresholds
        # This allows us to catch momentum plays that might have slightly lower scores
        effective_score = score
        effective_min_score = self.settings.min_signal_score
        effective_min_confidence = self.settings.required_confidence

        if is_bullrun:
            # Bullrun detected! Apply boosts
            # Score boost: +5 to +15 points based on bullrun strength
            if bullrun_score >= 90:
                score_boost = 15  # Very hot coin
            elif bullrun_score >= 80:
                score_boost = 10  # Hot coin
            else:
                score_boost = 5   # Moderate bullrun

            effective_score = min(score + score_boost, 100)

            # Lower thresholds for bullrun coins
            effective_min_score = max(self.settings.min_signal_score - 10, 55)  # Don't go below 55
            effective_min_confidence = max(self.settings.required_confidence - 0.1, 0.5)  # Don't go below 0.5

            logger.info(f"[{coin}] 🚀 BULLRUN BOOST! score={score}+{score_boost}={effective_score}, min_score={effective_min_score}, bullrun={bullrun_score}")

        if effective_score < effective_min_score:
            logger.debug(f"[{coin}] Skipping buy - score={effective_score} < min={effective_min_score}")
            return False

        if confidence < effective_min_confidence:
            logger.debug(f"[{coin}] Skipping buy - confidence={confidence} < required={effective_min_confidence}")
            return False

        if volume_24h < self.settings.min_volume_24h:
            logger.debug(f"[{coin}] Skipping buy - volume={volume_24h} < min={self.settings.min_volume_24h}")
            return False

        # EMA200 Trend Filter - Only buy in uptrends
        if ema_200 is not None and price > 0:
            if price < ema_200:
                logger.debug(f"[{coin}] Skipping buy - price ${price:.2f} below EMA200 ${ema_200:.2f} (DOWNTREND)")
                return False
            else:
                logger.info(f"[{coin}] EMA200 filter passed - price ${price:.2f} > EMA200 ${ema_200:.2f} (UPTREND)")

        # ADX Trend Strength Filter - Avoid sideways markets
        # ADX < 20 = weak trend (sideways), ADX > 25 = strong trend
        if adx is not None:
            if adx < 20:
                logger.debug(f"[{coin}] Skipping buy - ADX={adx:.1f} < 20 (SIDEWAYS/WEAK TREND)")
                return False
            else:
                trend_str = "VERY STRONG" if adx >= 50 else "STRONG" if adx >= 25 else "MODERATE"
                logger.info(f"[{coin}] ADX filter passed - ADX={adx:.1f} ({trend_str} TREND)")

        # Volume Ratio Filter - Confirm market interest
        # volume_ratio < 0.10 = extremely dead volume, > 1.5 = volume spike
        # Note: Low volume (0.10-0.50) still trades but may have reduced liquidity
        # Threshold lowered from 0.2 to 0.10 - market often operates at 10-20% of average volume
        if volume_ratio is not None:
            if volume_ratio < 0.10:
                logger.debug(f"[{coin}] Skipping buy - volume_ratio={volume_ratio:.2f} < 0.10 (DEAD VOLUME)")
                return False
            elif volume_ratio >= 1.5:
                logger.info(f"[{coin}] Volume SPIKE detected - volume_ratio={volume_ratio:.2f}x (STRONG CONFIRMATION)")
            else:
                logger.info(f"[{coin}] Volume filter passed - volume_ratio={volume_ratio:.2f}x")

        # Multi-Timeframe Filter - 1h and 4h trends should align
        # Block only if 4h trend is actively BEARISH (against us)
        # NEUTRAL is acceptable - means no strong opposition
        if not timeframes_aligned and higher_tf_trend == "BEARISH":
            logger.debug(f"[{coin}] Skipping buy - 4h trend is BEARISH (against 1h signal)")
            return False
        elif higher_tf_trend == "BULLISH":
            logger.info(f"[{coin}] Multi-Timeframe CONFIRMED - 4h trend is BULLISH")
        elif higher_tf_trend == "NEUTRAL":
            logger.info(f"[{coin}] Multi-Timeframe OK - 4h trend NEUTRAL (no opposition)")

        # Market Regime Filter - Only trade in favorable regimes
        # TRENDING_UP = ideal, avoid RANGING, VOLATILE, TRENDING_DOWN
        if not is_favorable_regime:
            logger.debug(f"[{coin}] Skipping buy - unfavorable regime: {market_regime}")
            return False
        else:
            logger.info(f"[{coin}] Market Regime FAVORABLE - {market_regime}")

        bullrun_info = f", 🚀bullrun={bullrun_score}" if is_bullrun else ""
        logger.info(f"[{coin}] ALL BUY CONDITIONS MET: signal={signal}, score={effective_score}, conf={confidence:.2f}, regime={market_regime}{bullrun_info}")
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

    def _calculate_position_size(
        self,
        price: float,
        confidence: float = 0.5,
        adx: Optional[float] = None,
        volume_spike: bool = False,
        regime_confidence: float = 0.5,
        coin: str = "",
        bullrun_score: int = 0,
        is_bullrun: bool = False
    ) -> float:
        """
        Calculate dynamic position size based on signal quality.

        Position sizing factors:
        1. Base position from settings (max_position_size_percent)
        2. Confidence multiplier (0.7x to 1.3x based on ML confidence)
        3. ADX bonus (up to +15% for very strong trends)
        4. Volume spike bonus (+10% for volume confirmation)
        5. Regime confidence factor
        6. Bullrun bonus (up to +30% for hot bullrun coins)

        This ensures we bet bigger on high-conviction trades
        and smaller on uncertain ones.
        """
        if not self.balance:
            return 0

        # Base position value
        base_value = self.balance.balance_usdt * (self.settings.max_position_size_percent / 100)

        # Calculate confidence multiplier (0.7x to 1.3x)
        # Low confidence (< 0.5) = smaller position
        # High confidence (> 0.8) = larger position
        if confidence >= 0.8:
            conf_multiplier = 1.0 + (confidence - 0.8) * 1.5  # Up to 1.3x at 1.0 confidence
        elif confidence >= 0.5:
            conf_multiplier = 1.0  # Normal position
        else:
            conf_multiplier = 0.7 + (confidence * 0.6)  # 0.7x at 0.0, 1.0x at 0.5

        # ADX bonus: Strong trend = slightly larger position
        adx_multiplier = 1.0
        if adx:
            if adx >= 50:
                adx_multiplier = 1.15  # Very strong trend: +15%
            elif adx >= 35:
                adx_multiplier = 1.10  # Strong trend: +10%
            elif adx >= 25:
                adx_multiplier = 1.05  # Moderate trend: +5%

        # Volume spike bonus
        volume_multiplier = 1.10 if volume_spike else 1.0

        # Regime confidence factor
        regime_multiplier = 0.9 + (regime_confidence * 0.2)  # 0.9x to 1.1x

        # ============ BULLRUN BONUS ============
        # Coins in a bullrun get larger positions to maximize gains on momentum plays
        # Score 90+: +30% position size (very hot)
        # Score 80-89: +20% position size (hot)
        # Score 70-79: +10% position size (moderate bullrun)
        bullrun_multiplier = 1.0
        if is_bullrun:
            if bullrun_score >= 90:
                bullrun_multiplier = 1.30  # Very hot coin: +30%
            elif bullrun_score >= 80:
                bullrun_multiplier = 1.20  # Hot coin: +20%
            else:
                bullrun_multiplier = 1.10  # Moderate bullrun: +10%

            logger.info(f"[{coin}] 🚀 BULLRUN POSITION BOOST: +{int((bullrun_multiplier - 1) * 100)}% (bullrun_score={bullrun_score})")

        # Combined multiplier (capped at 1.5x as per Kelly Criterion research - fractional Kelly is safer)
        max_multiplier = 1.5
        total_multiplier = min(
            conf_multiplier * adx_multiplier * volume_multiplier * regime_multiplier * bullrun_multiplier,
            max_multiplier
        )

        # Apply multiplier to base value
        position_value = base_value * total_multiplier

        # Don't exceed available balance (keep 5% reserve)
        position_value = min(position_value, self.balance.balance_usdt * 0.95)

        if position_value < 10:  # Minimum trade value
            return 0

        # Calculate quantity
        quantity = position_value / price

        bullrun_info = f", 🚀bullrun_boost" if is_bullrun else ""
        logger.info(f"[{coin}] Position sizing: base=${base_value:.2f}, multiplier={total_multiplier:.2f}, final=${position_value:.2f}{bullrun_info}")

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
        macd: Optional[float] = None,
        is_bullrun: bool = False  # Bullrun coins get wider trailing stops
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
                "p_take_profit": take_profit,
                "p_is_bullrun": is_bullrun  # Bullrun coins get wider trailing stops
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

            # REMOVED: enabled_coins filter - Bot trades ANY coin with strong signals
            # The signal score filter (min_signal_score) is the only gate

            price = result.get('price', 0)
            signal = result.get('ml_signal', 'HOLD')
            score = int(result.get('ml_score', 50))
            confidence = result.get('ml_confidence', 0.5)
            volume_24h = result.get('volume_24h', 0)
            reasons = result.get('top_reasons', [])
            rsi = result.get('rsi')
            macd = result.get('macd')
            ema_200 = result.get('ema_200')  # EMA200 for trend filter
            adx = result.get('adx')  # ADX for trend strength filter
            volume_ratio = result.get('volume_ratio')  # Volume ratio for confirmation
            timeframes_aligned = result.get('timeframes_aligned', True)  # Multi-timeframe confirmation
            higher_tf_trend = result.get('higher_tf_trend', 'NEUTRAL')  # 4h trend direction
            market_regime = result.get('market_regime', 'UNKNOWN')  # Market regime classification
            is_favorable_regime = result.get('is_favorable_regime', True)  # Is regime good for trading
            volume_spike = result.get('volume_spike', False)  # Volume spike for position sizing
            regime_confidence = result.get('regime_confidence', 0.5)  # Regime confidence for position sizing

            # Bullrun Detection - Boost signals and position sizes for hot coins
            bullrun_score = result.get('bullrun_score', 0)  # 0-100, higher = stronger bullrun
            is_bullrun = result.get('is_bullrun', False)  # True if bullrun_score >= 65
            bullrun_signals = result.get('bullrun_signals', [])  # List of bullish signals detected

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

                        # Record exit for learning system
                        await record_exit_for_learning(
                            coin=coin,
                            exit_price=price,
                            exit_reason="SIGNAL_REVERSAL",
                            exit_pnl_percent=pnl_percent
                        )

            else:
                # No position - check if we should buy
                if len(self.positions) >= self.settings.max_positions:
                    continue

                if self._should_buy(signal, score, confidence, volume_24h, coin, price, ema_200, adx, volume_ratio, timeframes_aligned, higher_tf_trend, market_regime, is_favorable_regime, bullrun_score, is_bullrun):
                    quantity = self._calculate_position_size(
                        price=price,
                        confidence=confidence,
                        adx=adx,
                        volume_spike=volume_spike,
                        regime_confidence=regime_confidence,
                        coin=coin,
                        bullrun_score=bullrun_score,
                        is_bullrun=is_bullrun
                    )

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
                            macd=macd,
                            is_bullrun=is_bullrun  # Pass bullrun status for trailing stop logic
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
                                "score": score,
                                "is_bullrun": is_bullrun
                            })

                            # Update is_bullrun flag in position (for trailing stop logic)
                            if is_bullrun and self.client:
                                try:
                                    self.client.table("bot_positions").update({
                                        "is_bullrun": True
                                    }).eq("coin", coin).execute()
                                    logger.info(f"[{coin}] Marked as BULLRUN position (wider trailing stops)")
                                except Exception as e:
                                    logger.warning(f"Failed to set is_bullrun: {e}")

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
        """Get current bot status with live prices"""
        await self.initialize()

        # Fetch live prices for positions
        positions_with_live_data = []
        total_unrealized_pnl = 0.0
        total_position_value = 0.0

        if self.positions:
            try:
                symbols = [f"{coin}/USDT" for coin in self.positions.keys()]
                tickers = await exchange_service.get_multiple_tickers(symbols)

                for p in self.positions.values():
                    symbol = f"{p.coin}/USDT"
                    entry_price = p.entry_price
                    quantity = p.quantity

                    if symbol in tickers:
                        current_price = tickers[symbol].price
                        current_value = current_price * quantity
                        entry_value = entry_price * quantity
                        unrealized_pnl = current_value - entry_value
                        unrealized_pnl_pct = ((current_price - entry_price) / entry_price * 100) if entry_price > 0 else 0

                        total_unrealized_pnl += unrealized_pnl
                        total_position_value += current_value

                        positions_with_live_data.append({
                            "coin": p.coin,
                            "side": p.side,
                            "quantity": round(quantity, 6),
                            "entry_price": round(entry_price, 2),
                            "current_price": round(current_price, 2),
                            "current_value": round(current_value, 2),
                            "unrealized_pnl": round(unrealized_pnl, 2),
                            "unrealized_pnl_percent": round(unrealized_pnl_pct, 2),
                            "stop_loss": p.stop_loss,
                            "take_profit": p.take_profit
                        })
                    else:
                        positions_with_live_data.append({
                            "coin": p.coin,
                            "side": p.side,
                            "quantity": round(quantity, 6),
                            "entry_price": round(entry_price, 2),
                            "current_price": round(entry_price, 2),
                            "current_value": round(entry_price * quantity, 2),
                            "unrealized_pnl": 0,
                            "unrealized_pnl_percent": 0,
                            "stop_loss": p.stop_loss,
                            "take_profit": p.take_profit
                        })
                        total_position_value += entry_price * quantity

            except Exception as e:
                logger.warning(f"Could not fetch live prices for status: {e}")
                # Fallback to cached data
                for p in self.positions.values():
                    positions_with_live_data.append({
                        "coin": p.coin,
                        "side": p.side,
                        "quantity": round(p.quantity, 6),
                        "entry_price": round(p.entry_price, 2),
                        "current_price": round(p.current_price, 2),
                        "current_value": round(p.current_price * p.quantity, 2),
                        "unrealized_pnl": round(p.unrealized_pnl, 2),
                        "unrealized_pnl_percent": round(p.unrealized_pnl_percent, 2),
                        "stop_loss": p.stop_loss,
                        "take_profit": p.take_profit
                    })
                    total_position_value += p.current_price * p.quantity

        return {
            "is_active": self.settings.is_active if self.settings else False,
            "balance": {
                "current": self.balance.balance_usdt if self.balance else 0,
                "initial": self.balance.initial_balance if self.balance else 10000,
                "total_pnl": self.balance.total_pnl if self.balance else 0,
                "total_pnl_percent": self.balance.total_pnl_percent if self.balance else 0,
                "total_trades": self.balance.total_trades if self.balance else 0,
                "win_rate": round(self.balance.winning_trades / self.balance.total_trades * 100, 1)
                            if self.balance and self.balance.total_trades > 0 else 0
            },
            "positions": positions_with_live_data,
            "positions_summary": {
                "count": len(positions_with_live_data),
                "total_value": round(total_position_value, 2),
                "total_unrealized_pnl": round(total_unrealized_pnl, 2)
            },
            "settings": {
                "min_signal_score": self.settings.min_signal_score if self.settings else 65,
                "max_positions": self.settings.max_positions if self.settings else 5,
                "enabled_coins": self.settings.enabled_coins if self.settings else []
            }
        }

    async def check_stop_losses(self) -> Dict:
        """
        Check all open positions for stop-loss/take-profit/trailing-stop triggers.

        This function implements a TRAILING STOP-LOSS that:
        1. Tracks the highest price since entry
        2. When price rises, the stop-loss moves up (locks in profits)
        3. When price falls below trailing stop, triggers a sell

        Example with 1.5% trailing stop:
        - Entry: $100
        - Price rises to $110 -> trailing stop = $108.35 (110 * 0.985)
        - Price drops to $108 -> SELL (below trailing stop)
        - Locked in 8% profit instead of waiting for fixed take-profit

        Returns:
            Dict with positions checked and any trades executed
        """
        await self.initialize()

        if not self.positions:
            return {
                "positions_checked": 0,
                "trades_executed": 0,
                "trailing_stops_updated": 0,
                "message": "No open positions"
            }

        trades_executed = []
        positions_checked = 0
        trailing_stops_updated = 0

        try:
            # Get live prices for all position symbols
            symbols = [f"{coin}/USDT" for coin in self.positions.keys()]

            async with httpx.AsyncClient(timeout=10.0) as client:
                tickers = {}
                for symbol in symbols:
                    try:
                        binance_symbol = symbol.replace("/", "")
                        resp = await client.get(
                            f"https://api.binance.com/api/v3/ticker/price?symbol={binance_symbol}"
                        )
                        if resp.status_code == 200:
                            data = resp.json()
                            tickers[symbol] = float(data['price'])
                    except Exception as e:
                        logger.warning(f"Failed to get price for {symbol}: {e}")

            # Check each position
            for coin, position in list(self.positions.items()):
                positions_checked += 1
                symbol = f"{coin}/USDT"

                if symbol not in tickers:
                    continue

                current_price = tickers[symbol]
                entry_price = position.entry_price
                highest_price = position.highest_price or entry_price

                # Calculate PnL
                pnl_percent = ((current_price / entry_price) - 1) * 100

                # Update highest price if current price is higher
                if current_price > highest_price:
                    highest_price = current_price
                    position.highest_price = highest_price

                    # Calculate profit from entry to highest (this determines trail width)
                    profit_from_entry = ((highest_price / entry_price) - 1) * 100

                    # Use HYBRID tiered trailing stop
                    # Normal coins: tight stops (secure gains)
                    # Bullrun coins: slightly wider (let momentum run)
                    trail_percent = calculate_tiered_trailing_stop_percent(profit_from_entry, position.is_bullrun)
                    new_trailing_stop = highest_price * (1 - trail_percent / 100)

                    # Only update if new trailing stop is higher (locks in more profit)
                    if position.trailing_stop is None or new_trailing_stop > position.trailing_stop:
                        old_trailing = position.trailing_stop
                        position.trailing_stop = new_trailing_stop
                        trailing_stops_updated += 1

                        # Update in database
                        if self.client:
                            try:
                                self.client.table("bot_positions").update({
                                    "highest_price": highest_price,
                                    "trailing_stop": new_trailing_stop
                                }).eq("coin", coin).execute()
                            except Exception as e:
                                logger.warning(f"Failed to update trailing stop in DB: {e}")

                        bullrun_tag = " [BULLRUN]" if position.is_bullrun else ""
                        logger.info(f"[{coin}]{bullrun_tag} Trailing stop: ${old_trailing:.2f if old_trailing else 0:.2f} -> ${new_trailing_stop:.2f} (profit: {profit_from_entry:.1f}% -> trail: {trail_percent}%)")

                logger.debug(f"[{coin}] Price: ${current_price:.4f}, Entry: ${entry_price:.4f}, Highest: ${highest_price:.4f}, PnL: {pnl_percent:.2f}%, Trailing Stop: ${position.trailing_stop:.4f if position.trailing_stop else 0}")

                # Check TRAILING STOP first (when in profit)
                if position.trailing_stop and current_price <= position.trailing_stop and pnl_percent > 0:
                    profit_locked = ((position.trailing_stop / entry_price) - 1) * 100
                    logger.info(f"[{coin}] TRAILING STOP triggered! Price ${current_price:.2f} <= Trailing ${position.trailing_stop:.2f}, Profit locked: {profit_locked:.2f}%")

                    trade_result = await self.execute_trade(
                        coin=coin,
                        side="SELL",
                        quantity=position.quantity,
                        price=current_price,
                        signal_type="TRAILING_STOP",
                        signal_score=75,
                        signal_reasons=[f"Trailing stop triggered at {pnl_percent:.2f}% (locked {profit_locked:.1f}% profit)"]
                    )

                    if trade_result.get('success'):
                        trades_executed.append({
                            "coin": coin,
                            "reason": "TRAILING_STOP",
                            "pnl_percent": round(pnl_percent, 2),
                            "pnl": trade_result.get('pnl', 0),
                            "profit_locked": round(profit_locked, 2)
                        })
                        del self.positions[coin]

                        # Send notification
                        await notification_service.notify_profit_loss(
                            coin=coin,
                            pnl=trade_result.get('pnl', 0),
                            pnl_percent=pnl_percent,
                            reason="TRAILING_STOP"
                        )

                        # Record exit for learning system
                        await record_exit_for_learning(
                            coin=coin,
                            exit_price=current_price,
                            exit_reason="TRAILING_STOP",
                            exit_pnl_percent=pnl_percent
                        )

                # Check fixed STOP LOSS (when in loss)
                elif pnl_percent <= self.settings.stop_loss_percent:
                    logger.warning(f"[{coin}] STOP LOSS triggered! PnL: {pnl_percent:.2f}% <= {self.settings.stop_loss_percent}%")

                    trade_result = await self.execute_trade(
                        coin=coin,
                        side="SELL",
                        quantity=position.quantity,
                        price=current_price,
                        signal_type="STOP_LOSS",
                        signal_score=0,
                        signal_reasons=[f"Stop-loss triggered at {pnl_percent:.2f}%"]
                    )

                    if trade_result.get('success'):
                        trades_executed.append({
                            "coin": coin,
                            "reason": "STOP_LOSS",
                            "pnl_percent": round(pnl_percent, 2),
                            "pnl": trade_result.get('pnl', 0)
                        })
                        del self.positions[coin]

                        # Send notification
                        await notification_service.notify_profit_loss(
                            coin=coin,
                            pnl=trade_result.get('pnl', 0),
                            pnl_percent=pnl_percent,
                            reason="STOP_LOSS"
                        )

                        # Record exit for learning system (stop loss = we bought wrong)
                        await record_exit_for_learning(
                            coin=coin,
                            exit_price=current_price,
                            exit_reason="STOP_LOSS",
                            exit_pnl_percent=pnl_percent
                        )

                # Check TAKE PROFIT (maximum target reached)
                elif pnl_percent >= self.settings.take_profit_percent:
                    logger.info(f"[{coin}] TAKE PROFIT triggered! PnL: {pnl_percent:.2f}% >= {self.settings.take_profit_percent}%")

                    trade_result = await self.execute_trade(
                        coin=coin,
                        side="SELL",
                        quantity=position.quantity,
                        price=current_price,
                        signal_type="TAKE_PROFIT",
                        signal_score=100,
                        signal_reasons=[f"Take-profit triggered at {pnl_percent:.2f}%"]
                    )

                    if trade_result.get('success'):
                        trades_executed.append({
                            "coin": coin,
                            "reason": "TAKE_PROFIT",
                            "pnl_percent": round(pnl_percent, 2),
                            "pnl": trade_result.get('pnl', 0)
                        })
                        del self.positions[coin]

                        # Send notification
                        await notification_service.notify_profit_loss(
                            coin=coin,
                            pnl=trade_result.get('pnl', 0),
                            pnl_percent=pnl_percent,
                            reason="TAKE_PROFIT"
                        )

                        # Record exit for learning system
                        await record_exit_for_learning(
                            coin=coin,
                            exit_price=current_price,
                            exit_reason="TAKE_PROFIT",
                            exit_pnl_percent=pnl_percent
                        )

        except Exception as e:
            logger.error(f"Error checking stop losses: {e}")
            return {
                "error": str(e),
                "positions_checked": positions_checked,
                "trades_executed": len(trades_executed),
                "trailing_stops_updated": trailing_stops_updated
            }

        return {
            "positions_checked": positions_checked,
            "trades_executed": len(trades_executed),
            "trailing_stops_updated": trailing_stops_updated,
            "trades": trades_executed,
            "message": f"Checked {positions_checked} positions, executed {len(trades_executed)} trades, updated {trailing_stops_updated} trailing stops"
        }


# Global bot instance
autonomous_bot = SupabaseTradingBot()
