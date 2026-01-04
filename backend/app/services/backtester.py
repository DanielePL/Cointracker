"""
Backtesting Engine
Test trading strategies against historical data

Enhanced with ADX, Volume, Multi-Timeframe, and Market Regime filters.
"""

import numpy as np
import pandas as pd
from typing import Dict, List, Optional, Tuple
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from loguru import logger
import asyncio

try:
    from ta.trend import EMAIndicator, ADXIndicator
    from ta.volatility import BollingerBands
    TA_AVAILABLE = True
except ImportError:
    TA_AVAILABLE = False

from app.services.exchange import exchange_service
try:
    from app.services.indicators import TechnicalAnalyzer
    from app.ml.feature_engineer import FeatureEngineer, FeatureVector
    from app.ml.hybrid_model import HybridModel, ModelPrediction
    ML_AVAILABLE = True
except ImportError:
    ML_AVAILABLE = False
    logger.warning("ML modules not available for backtester")


@dataclass
class BacktestTrade:
    """Single trade in backtest"""
    entry_time: datetime
    exit_time: datetime
    symbol: str
    side: str  # "buy" or "sell"
    entry_price: float
    exit_price: float
    amount: float
    pnl: float
    pnl_pct: float
    signal_score: int
    exit_reason: str  # "take_profit", "stop_loss", "signal_reversal", "end_of_test"


@dataclass
class BacktestResult:
    """Complete backtest results"""
    # Period
    start_date: datetime
    end_date: datetime
    duration_days: int

    # Returns
    total_return_pct: float
    annualized_return_pct: float
    buy_and_hold_return_pct: float
    alpha: float  # Excess return over buy & hold

    # Risk Metrics
    sharpe_ratio: float
    sortino_ratio: float
    max_drawdown_pct: float
    max_drawdown_duration_days: int
    volatility_pct: float

    # Trade Statistics
    total_trades: int
    winning_trades: int
    losing_trades: int
    win_rate: float
    profit_factor: float  # Gross profit / Gross loss
    avg_win_pct: float
    avg_loss_pct: float
    largest_win_pct: float
    largest_loss_pct: float
    avg_trade_duration_hours: float

    # Additional
    trades: List[BacktestTrade] = field(default_factory=list)
    equity_curve: List[float] = field(default_factory=list)
    drawdown_curve: List[float] = field(default_factory=list)


@dataclass
class BacktestConfig:
    """Backtest configuration"""
    initial_capital: float = 10000.0
    position_size_pct: float = 10.0  # % of capital per trade
    stop_loss_pct: float = 3.0
    take_profit_pct: float = 6.0
    min_signal_score: int = 65
    commission_pct: float = 0.1  # Trading fee
    slippage_pct: float = 0.05   # Price slippage


@dataclass
class EnhancedBacktestConfig:
    """Enhanced backtest configuration with all our filters"""
    initial_capital: float = 10000.0
    position_size_pct: float = 10.0
    stop_loss_pct: float = -5.0  # Match trading engine
    take_profit_pct: float = 10.0
    trailing_stop_pct: float = 3.0  # Trailing stop after profit
    min_signal_score: int = 60
    commission_pct: float = 0.1
    slippage_pct: float = 0.05

    # Filter settings (matching trading engine)
    min_adx: float = 20.0  # Minimum ADX for trend strength
    min_volume_ratio: float = 0.5  # Minimum volume ratio
    require_ema200_above: bool = True  # Price must be above EMA200
    require_timeframe_alignment: bool = True  # 1h and 4h must agree
    require_favorable_regime: bool = True  # Only trade in TRENDING_UP

    # Dynamic position sizing
    use_dynamic_sizing: bool = True
    max_position_multiplier: float = 1.5


class Backtester:
    """
    Backtest trading strategies against historical data
    """

    def __init__(self, config: Optional[BacktestConfig] = None):
        self.config = config or BacktestConfig()
        self.analyzer = TechnicalAnalyzer()
        self.feature_engineer = FeatureEngineer()
        self.model = HybridModel()

    async def run_backtest(
        self,
        symbol: str = "BTC/USDT",
        timeframe: str = "1h",
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        days: int = 365
    ) -> BacktestResult:
        """
        Run backtest on historical data

        Args:
            symbol: Trading pair
            timeframe: Candle timeframe
            start_date: Backtest start date
            end_date: Backtest end date (defaults to now)
            days: Number of days to backtest (if start_date not specified)
        """
        logger.info(f"Starting backtest for {symbol}, timeframe={timeframe}")

        # Fetch historical data
        ohlcv_data = await self._fetch_historical_data(symbol, timeframe, days)

        if len(ohlcv_data) < 200:
            raise ValueError(f"Insufficient data: {len(ohlcv_data)} candles")

        logger.info(f"Loaded {len(ohlcv_data)} candles for backtest")

        # Run simulation
        trades, equity_curve = await self._simulate_trading(ohlcv_data, symbol)

        # Calculate metrics
        result = self._calculate_metrics(
            trades,
            equity_curve,
            ohlcv_data,
            start_date or ohlcv_data['timestamp'].iloc[0],
            end_date or ohlcv_data['timestamp'].iloc[-1]
        )

        logger.info(f"Backtest complete: {result.total_trades} trades, {result.total_return_pct:.2f}% return")

        return result

    async def run_enhanced_backtest(
        self,
        symbol: str = "BTC/USDT",
        days: int = 90,
        config: Optional[EnhancedBacktestConfig] = None
    ) -> BacktestResult:
        """
        Run enhanced backtest with ADX, Volume, Multi-TF, and Market Regime filters.

        This tests the exact same logic as our trading engine's _should_buy().
        """
        if not TA_AVAILABLE:
            raise RuntimeError("TA library required for enhanced backtest")

        cfg = config or EnhancedBacktestConfig()
        logger.info(f"Starting ENHANCED backtest for {symbol}, {days} days")
        logger.info(f"Filters: ADX>{cfg.min_adx}, VolRatio>{cfg.min_volume_ratio}, EMA200={cfg.require_ema200_above}")

        # Fetch 1h data
        ohlcv_1h = await self._fetch_historical_data(symbol, "1h", days)
        if len(ohlcv_1h) < 250:
            raise ValueError(f"Insufficient 1h data: {len(ohlcv_1h)} candles")

        # Fetch 4h data for multi-timeframe
        ohlcv_4h = await self._fetch_historical_data(symbol, "4h", days)

        logger.info(f"Loaded {len(ohlcv_1h)} 1h candles, {len(ohlcv_4h)} 4h candles")

        # Calculate indicators for the entire dataset
        ohlcv_1h = self._calculate_indicators(ohlcv_1h)
        ohlcv_4h = self._calculate_indicators(ohlcv_4h, prefix="4h_")

        # Run enhanced simulation
        trades, equity_curve = self._simulate_enhanced_trading(ohlcv_1h, ohlcv_4h, cfg)

        # Calculate metrics
        result = self._calculate_metrics(
            trades,
            equity_curve,
            ohlcv_1h,
            ohlcv_1h['timestamp'].iloc[200],
            ohlcv_1h['timestamp'].iloc[-1]
        )

        logger.info(f"Enhanced backtest complete: {result.total_trades} trades, Win Rate: {result.win_rate:.1f}%")

        return result

    def _calculate_indicators(self, df: pd.DataFrame, prefix: str = "") -> pd.DataFrame:
        """Calculate all technical indicators needed for the strategy"""
        close = df['close']
        high = df['high']
        low = df['low']
        volume = df['volume']

        # EMA indicators
        df[f'{prefix}ema_50'] = EMAIndicator(close, window=50).ema_indicator()
        df[f'{prefix}ema_200'] = EMAIndicator(close, window=200).ema_indicator()

        # ADX indicators
        adx_indicator = ADXIndicator(high, low, close, window=14)
        df[f'{prefix}adx'] = adx_indicator.adx()
        df[f'{prefix}adx_pos'] = adx_indicator.adx_pos()
        df[f'{prefix}adx_neg'] = adx_indicator.adx_neg()

        # Bollinger Bands
        bb = BollingerBands(close, window=20, window_dev=2)
        df[f'{prefix}bb_upper'] = bb.bollinger_hband()
        df[f'{prefix}bb_lower'] = bb.bollinger_lband()
        df[f'{prefix}bb_middle'] = bb.bollinger_mavg()
        df[f'{prefix}bb_width'] = ((df[f'{prefix}bb_upper'] - df[f'{prefix}bb_lower']) / df[f'{prefix}bb_middle']) * 100

        # Volume ratio
        df[f'{prefix}volume_ma'] = volume.rolling(window=20).mean()
        df[f'{prefix}volume_ratio'] = volume / df[f'{prefix}volume_ma']
        df[f'{prefix}volume_spike'] = df[f'{prefix}volume_ratio'] >= 1.5

        return df

    def _detect_regime(self, row: pd.Series) -> Tuple[str, float, bool]:
        """Detect market regime from indicators"""
        adx = row.get('adx', 0)
        adx_pos = row.get('adx_pos', 0)
        adx_neg = row.get('adx_neg', 0)
        price = row['close']
        ema_50 = row.get('ema_50', price)
        ema_200 = row.get('ema_200', price)
        bb_width = row.get('bb_width', 5)

        if pd.isna(adx) or adx == 0:
            return "UNKNOWN", 0.0, False

        # Trending market
        if adx >= 25:
            if adx_pos > adx_neg and price > ema_50 and price > ema_200:
                conf = min(0.5 + (adx - 25) / 50, 1.0)
                return "TRENDING_UP", conf, True
            elif adx_neg > adx_pos and price < ema_50:
                conf = min(0.5 + (adx - 25) / 50, 1.0)
                return "TRENDING_DOWN", conf, False
        # Ranging
        elif adx < 20:
            return "RANGING", 0.6, False
        # Volatile
        elif bb_width > 8:
            return "VOLATILE", 0.6, False

        # Moderate trend
        if price > ema_50 and price > ema_200:
            return "TRENDING_UP", 0.4, True
        return "TRENDING_DOWN", 0.4, False

    def _should_buy_enhanced(
        self,
        row: pd.Series,
        row_4h: Optional[pd.Series],
        cfg: EnhancedBacktestConfig
    ) -> Tuple[bool, Dict]:
        """
        Check if we should buy using all our enhanced filters.
        Returns (should_buy, reasons_dict)
        """
        reasons = {}
        price = row['close']

        # 1. EMA200 Filter
        ema_200 = row.get('ema_200')
        if cfg.require_ema200_above and pd.notna(ema_200):
            if price < ema_200:
                reasons['ema200'] = f"FAIL: price {price:.2f} < EMA200 {ema_200:.2f}"
                return False, reasons
            reasons['ema200'] = "PASS"

        # 2. ADX Filter
        adx = row.get('adx')
        if pd.notna(adx):
            if adx < cfg.min_adx:
                reasons['adx'] = f"FAIL: ADX {adx:.1f} < {cfg.min_adx}"
                return False, reasons
            reasons['adx'] = f"PASS: ADX {adx:.1f}"

        # 3. Volume Ratio Filter
        volume_ratio = row.get('volume_ratio')
        if pd.notna(volume_ratio):
            if volume_ratio < cfg.min_volume_ratio:
                reasons['volume'] = f"FAIL: VR {volume_ratio:.2f} < {cfg.min_volume_ratio}"
                return False, reasons
            reasons['volume'] = f"PASS: VR {volume_ratio:.2f}"

        # 4. Multi-Timeframe Filter
        if cfg.require_timeframe_alignment and row_4h is not None:
            ema_50_4h = row_4h.get('4h_ema_50')
            if pd.notna(ema_50_4h):
                higher_tf_bullish = price > ema_50_4h * 1.005
                higher_tf_bearish = price < ema_50_4h * 0.995
                trend_1h_bullish = price > ema_200 if pd.notna(ema_200) else True

                if higher_tf_bearish:
                    reasons['mtf'] = "FAIL: 4h bearish"
                    return False, reasons
                if not (higher_tf_bullish or trend_1h_bullish):
                    reasons['mtf'] = "FAIL: Timeframes not aligned"
                    return False, reasons
                reasons['mtf'] = "PASS"

        # 5. Market Regime Filter
        if cfg.require_favorable_regime:
            regime, conf, is_favorable = self._detect_regime(row)
            if not is_favorable:
                reasons['regime'] = f"FAIL: {regime}"
                return False, reasons
            reasons['regime'] = f"PASS: {regime}"

        return True, reasons

    def _calculate_position_multiplier(
        self,
        row: pd.Series,
        cfg: EnhancedBacktestConfig
    ) -> float:
        """Calculate dynamic position size multiplier"""
        if not cfg.use_dynamic_sizing:
            return 1.0

        multiplier = 1.0

        # ADX bonus
        adx = row.get('adx', 0)
        if pd.notna(adx) and adx >= 50:
            multiplier *= 1.15
        elif pd.notna(adx) and adx >= 35:
            multiplier *= 1.10
        elif pd.notna(adx) and adx >= 25:
            multiplier *= 1.05

        # Volume spike bonus
        if row.get('volume_spike', False):
            multiplier *= 1.10

        return min(multiplier, cfg.max_position_multiplier)

    def _simulate_enhanced_trading(
        self,
        df_1h: pd.DataFrame,
        df_4h: pd.DataFrame,
        cfg: EnhancedBacktestConfig
    ) -> Tuple[List[BacktestTrade], List[float]]:
        """Simulate trading with enhanced filters"""
        trades: List[BacktestTrade] = []
        equity_curve: List[float] = []

        capital = cfg.initial_capital
        position = None
        peak_price = 0  # For trailing stop

        # Start after warmup period
        start_idx = 200

        for i in range(start_idx, len(df_1h)):
            row = df_1h.iloc[i]
            current_price = row['close']
            current_time = row['timestamp']

            # Find corresponding 4h candle
            row_4h = None
            if len(df_4h) > 0:
                mask = df_4h['timestamp'] <= current_time
                if mask.any():
                    row_4h = df_4h[mask].iloc[-1]

            # Manage existing position
            if position:
                entry_price = position['entry_price']
                pnl_pct = ((current_price / entry_price) - 1) * 100

                # Update peak price for trailing stop
                if current_price > peak_price:
                    peak_price = current_price

                # Check stop loss
                if pnl_pct <= cfg.stop_loss_pct:
                    trade = self._create_trade(position, current_price, current_time, "stop_loss")
                    trades.append(trade)
                    capital += trade.pnl
                    position = None
                    peak_price = 0

                # Check take profit
                elif pnl_pct >= cfg.take_profit_pct:
                    trade = self._create_trade(position, current_price, current_time, "take_profit")
                    trades.append(trade)
                    capital += trade.pnl
                    position = None
                    peak_price = 0

                # Check trailing stop (after we're in profit)
                elif pnl_pct > cfg.trailing_stop_pct:
                    trailing_level = peak_price * (1 - cfg.trailing_stop_pct / 100)
                    if current_price < trailing_level:
                        trade = self._create_trade(position, current_price, current_time, "trailing_stop")
                        trades.append(trade)
                        capital += trade.pnl
                        position = None
                        peak_price = 0

            # Check for new entry
            if position is None:
                should_buy, reasons = self._should_buy_enhanced(row, row_4h, cfg)

                if should_buy:
                    # Calculate position size with dynamic multiplier
                    multiplier = self._calculate_position_multiplier(row, cfg)
                    position_value = capital * (cfg.position_size_pct / 100) * multiplier

                    # Apply commission and slippage
                    position_value *= (1 - cfg.commission_pct / 100)
                    entry_price = current_price * (1 + cfg.slippage_pct / 100)
                    amount = position_value / entry_price

                    position = {
                        'side': 'buy',
                        'entry_price': entry_price,
                        'entry_time': current_time,
                        'amount': amount,
                        'signal_score': 70,  # Placeholder
                        'multiplier': multiplier
                    }
                    peak_price = entry_price

            # Calculate equity
            if position:
                position_value = position['amount'] * current_price
                current_equity = capital - (position['amount'] * position['entry_price']) + position_value
            else:
                current_equity = capital

            equity_curve.append(current_equity)

        # Close remaining position
        if position:
            trade = self._create_trade(
                position,
                df_1h.iloc[-1]['close'],
                df_1h.iloc[-1]['timestamp'],
                "end_of_test"
            )
            trades.append(trade)

        return trades, equity_curve

    def _create_trade(
        self,
        position: Dict,
        exit_price: float,
        exit_time: datetime,
        reason: str
    ) -> BacktestTrade:
        """Create a trade record"""
        actual_exit = exit_price * (1 - 0.05 / 100)  # Slippage
        pnl = (actual_exit - position['entry_price']) * position['amount']
        pnl -= position['amount'] * actual_exit * (0.1 / 100)  # Commission
        pnl_pct = ((actual_exit / position['entry_price']) - 1) * 100

        return BacktestTrade(
            entry_time=position['entry_time'],
            exit_time=exit_time,
            symbol="TEST",
            side=position['side'],
            entry_price=position['entry_price'],
            exit_price=actual_exit,
            amount=position['amount'],
            pnl=pnl,
            pnl_pct=pnl_pct,
            signal_score=position.get('signal_score', 50),
            exit_reason=reason
        )

    async def _fetch_historical_data(
        self,
        symbol: str,
        timeframe: str,
        days: int
    ) -> pd.DataFrame:
        """Fetch historical OHLCV data"""
        try:
            # Calculate number of candles needed
            timeframe_hours = {
                "1m": 1/60, "5m": 5/60, "15m": 15/60, "30m": 0.5,
                "1h": 1, "4h": 4, "1d": 24
            }
            hours_per_candle = timeframe_hours.get(timeframe, 1)
            limit = int(days * 24 / hours_per_candle)
            limit = min(limit, 1000)  # API limit

            ohlcv = await exchange_service.get_ohlcv(symbol, timeframe, limit)

            df = pd.DataFrame(ohlcv, columns=['timestamp', 'open', 'high', 'low', 'close', 'volume'])
            df['timestamp'] = pd.to_datetime(df['timestamp'], unit='ms')

            return df

        except Exception as e:
            logger.error(f"Failed to fetch historical data: {e}")
            raise

    async def _simulate_trading(
        self,
        data: pd.DataFrame,
        symbol: str
    ) -> Tuple[List[BacktestTrade], List[float]]:
        """
        Simulate trading through historical data
        """
        trades: List[BacktestTrade] = []
        equity_curve: List[float] = []

        capital = self.config.initial_capital
        position = None  # Current position
        feature_history: List[FeatureVector] = []

        # Need at least 200 candles for indicators
        start_idx = 200

        for i in range(start_idx, len(data)):
            current_candle = data.iloc[i]
            current_price = current_candle['close']
            current_time = current_candle['timestamp']

            # Get historical data up to this point
            historical_data = data.iloc[:i+1]

            # Generate features
            features = await self.feature_engineer.create_features(
                historical_data.tail(200),
                symbol=symbol
            )
            feature_history.append(features)

            # Get signal from model
            sequence = None
            if len(feature_history) >= 24:
                sequence = self.feature_engineer.create_sequence(feature_history, 24)

            prediction = self.model.predict(features, sequence)

            # Manage existing position
            if position:
                # Check stop loss
                if position['side'] == 'buy':
                    pnl_pct = ((current_price / position['entry_price']) - 1) * 100
                    if pnl_pct <= -self.config.stop_loss_pct:
                        trade = self._close_position(position, current_price, current_time, "stop_loss")
                        trades.append(trade)
                        capital += trade.pnl
                        position = None
                    elif pnl_pct >= self.config.take_profit_pct:
                        trade = self._close_position(position, current_price, current_time, "take_profit")
                        trades.append(trade)
                        capital += trade.pnl
                        position = None
                    elif "SELL" in prediction.signal and prediction.score >= self.config.min_signal_score:
                        trade = self._close_position(position, current_price, current_time, "signal_reversal")
                        trades.append(trade)
                        capital += trade.pnl
                        position = None

                elif position['side'] == 'sell':
                    pnl_pct = ((position['entry_price'] / current_price) - 1) * 100
                    if pnl_pct <= -self.config.stop_loss_pct:
                        trade = self._close_position(position, current_price, current_time, "stop_loss")
                        trades.append(trade)
                        capital += trade.pnl
                        position = None
                    elif pnl_pct >= self.config.take_profit_pct:
                        trade = self._close_position(position, current_price, current_time, "take_profit")
                        trades.append(trade)
                        capital += trade.pnl
                        position = None
                    elif "BUY" in prediction.signal and prediction.score >= self.config.min_signal_score:
                        trade = self._close_position(position, current_price, current_time, "signal_reversal")
                        trades.append(trade)
                        capital += trade.pnl
                        position = None

            # Open new position if no current position
            if position is None and prediction.score >= self.config.min_signal_score:
                if "BUY" in prediction.signal:
                    position_value = capital * (self.config.position_size_pct / 100)
                    # Apply commission
                    position_value *= (1 - self.config.commission_pct / 100)
                    # Apply slippage
                    entry_price = current_price * (1 + self.config.slippage_pct / 100)
                    amount = position_value / entry_price

                    position = {
                        'side': 'buy',
                        'entry_price': entry_price,
                        'entry_time': current_time,
                        'amount': amount,
                        'signal_score': prediction.score
                    }

                elif "SELL" in prediction.signal:
                    position_value = capital * (self.config.position_size_pct / 100)
                    position_value *= (1 - self.config.commission_pct / 100)
                    entry_price = current_price * (1 - self.config.slippage_pct / 100)
                    amount = position_value / entry_price

                    position = {
                        'side': 'sell',
                        'entry_price': entry_price,
                        'entry_time': current_time,
                        'amount': amount,
                        'signal_score': prediction.score
                    }

            # Calculate current equity
            if position:
                if position['side'] == 'buy':
                    position_value = position['amount'] * current_price
                else:
                    position_value = position['amount'] * (2 * position['entry_price'] - current_price)
                current_equity = capital - (position['amount'] * position['entry_price']) + position_value
            else:
                current_equity = capital

            equity_curve.append(current_equity)

        # Close any remaining position at end
        if position:
            trade = self._close_position(
                position,
                data.iloc[-1]['close'],
                data.iloc[-1]['timestamp'],
                "end_of_test"
            )
            trades.append(trade)

        return trades, equity_curve

    def _close_position(
        self,
        position: Dict,
        exit_price: float,
        exit_time: datetime,
        reason: str
    ) -> BacktestTrade:
        """Close a position and create trade record"""
        # Apply slippage on exit
        if position['side'] == 'buy':
            actual_exit = exit_price * (1 - self.config.slippage_pct / 100)
            pnl = (actual_exit - position['entry_price']) * position['amount']
            pnl_pct = ((actual_exit / position['entry_price']) - 1) * 100
        else:
            actual_exit = exit_price * (1 + self.config.slippage_pct / 100)
            pnl = (position['entry_price'] - actual_exit) * position['amount']
            pnl_pct = ((position['entry_price'] / actual_exit) - 1) * 100

        # Apply commission
        pnl -= position['amount'] * actual_exit * (self.config.commission_pct / 100)

        return BacktestTrade(
            entry_time=position['entry_time'],
            exit_time=exit_time,
            symbol="BTC/USDT",  # TODO: Pass symbol
            side=position['side'],
            entry_price=position['entry_price'],
            exit_price=actual_exit,
            amount=position['amount'],
            pnl=pnl,
            pnl_pct=pnl_pct,
            signal_score=position['signal_score'],
            exit_reason=reason
        )

    def _calculate_metrics(
        self,
        trades: List[BacktestTrade],
        equity_curve: List[float],
        ohlcv: pd.DataFrame,
        start_date: datetime,
        end_date: datetime
    ) -> BacktestResult:
        """Calculate backtest performance metrics"""

        duration = (end_date - start_date).days
        initial_capital = self.config.initial_capital

        # Returns
        final_equity = equity_curve[-1] if equity_curve else initial_capital
        total_return = ((final_equity / initial_capital) - 1) * 100

        # Buy and hold return
        start_price = ohlcv['close'].iloc[0]
        end_price = ohlcv['close'].iloc[-1]
        buy_hold_return = ((end_price / start_price) - 1) * 100

        # Annualized return
        years = duration / 365
        annualized_return = ((1 + total_return/100) ** (1/years) - 1) * 100 if years > 0 else 0

        # Trade statistics
        winning_trades = [t for t in trades if t.pnl > 0]
        losing_trades = [t for t in trades if t.pnl < 0]

        win_rate = len(winning_trades) / len(trades) * 100 if trades else 0
        avg_win = np.mean([t.pnl_pct for t in winning_trades]) if winning_trades else 0
        avg_loss = np.mean([t.pnl_pct for t in losing_trades]) if losing_trades else 0

        gross_profit = sum(t.pnl for t in winning_trades)
        gross_loss = abs(sum(t.pnl for t in losing_trades))
        profit_factor = gross_profit / gross_loss if gross_loss > 0 else float('inf')

        # Risk metrics
        if len(equity_curve) > 1:
            returns = pd.Series(equity_curve).pct_change().dropna()
            volatility = returns.std() * np.sqrt(252 * 24)  # Annualized for hourly data
            sharpe = (annualized_return / 100 - 0.02) / volatility if volatility > 0 else 0

            # Sortino (only downside volatility)
            downside_returns = returns[returns < 0]
            downside_vol = downside_returns.std() * np.sqrt(252 * 24) if len(downside_returns) > 0 else volatility
            sortino = (annualized_return / 100 - 0.02) / downside_vol if downside_vol > 0 else 0

            # Max drawdown
            peak = pd.Series(equity_curve).expanding().max()
            drawdown = (pd.Series(equity_curve) - peak) / peak * 100
            max_drawdown = drawdown.min()

            # Drawdown duration
            in_drawdown = drawdown < 0
            drawdown_periods = (~in_drawdown).cumsum()
            max_dd_duration = in_drawdown.groupby(drawdown_periods).sum().max()
        else:
            volatility = sharpe = sortino = max_drawdown = 0
            max_dd_duration = 0

        # Average trade duration
        if trades:
            durations = [(t.exit_time - t.entry_time).total_seconds() / 3600 for t in trades]
            avg_duration = np.mean(durations)
        else:
            avg_duration = 0

        return BacktestResult(
            start_date=start_date,
            end_date=end_date,
            duration_days=duration,
            total_return_pct=total_return,
            annualized_return_pct=annualized_return,
            buy_and_hold_return_pct=buy_hold_return,
            alpha=total_return - buy_hold_return,
            sharpe_ratio=sharpe,
            sortino_ratio=sortino,
            max_drawdown_pct=max_drawdown,
            max_drawdown_duration_days=int(max_dd_duration / 24) if max_dd_duration else 0,
            volatility_pct=volatility * 100,
            total_trades=len(trades),
            winning_trades=len(winning_trades),
            losing_trades=len(losing_trades),
            win_rate=win_rate,
            profit_factor=profit_factor,
            avg_win_pct=avg_win,
            avg_loss_pct=avg_loss,
            largest_win_pct=max(t.pnl_pct for t in trades) if trades else 0,
            largest_loss_pct=min(t.pnl_pct for t in trades) if trades else 0,
            avg_trade_duration_hours=avg_duration,
            trades=trades,
            equity_curve=equity_curve,
            drawdown_curve=list(drawdown) if 'drawdown' in dir() else []
        )


# Global instance
backtester = Backtester()
