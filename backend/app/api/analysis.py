"""
Mass Coin Analysis API
Analyzes 100+ coins and logs everything to Supabase
"""
import asyncio
import os
from datetime import datetime, timedelta
from typing import List, Dict, Any, Optional
from fastapi import APIRouter, HTTPException, BackgroundTasks
from pydantic import BaseModel
from loguru import logger
import httpx

# Supabase client
try:
    from supabase import create_client, Client
    SUPABASE_URL = os.getenv("SUPABASE_URL", "https://iyenuoujyruaotydjjqg.supabase.co")
    SUPABASE_KEY = os.getenv("SUPABASE_SERVICE_KEY", "")  # Use service key for backend
    supabase: Optional[Client] = create_client(SUPABASE_URL, SUPABASE_KEY) if SUPABASE_KEY else None
except ImportError:
    supabase = None
    logger.warning("Supabase not installed")

# ML imports
try:
    from app.ml.hybrid_model import HybridModel, ModelPrediction
    from app.ml.feature_engineer import FeatureEngineer
    ML_AVAILABLE = True
except ImportError:
    ML_AVAILABLE = False
    logger.warning("ML modules not available, using fallback")

# Import trading bot
try:
    from app.services.trading_engine import autonomous_bot
    BOT_AVAILABLE = True
except ImportError:
    BOT_AVAILABLE = False
    autonomous_bot = None
    logger.warning("Trading bot not available")

# Import ML trainer
try:
    from app.ml.trainer import ml_trainer
    TRAINER_AVAILABLE = True
except ImportError:
    TRAINER_AVAILABLE = False
    ml_trainer = None
    logger.warning("ML trainer not available")

# Import exchange service for live prices
try:
    from app.services.exchange import exchange_service
    EXCHANGE_AVAILABLE = True
except ImportError:
    EXCHANGE_AVAILABLE = False
    exchange_service = None
    logger.warning("Exchange service not available")

# Technical Analysis
try:
    import pandas as pd
    import numpy as np
    from ta.momentum import RSIIndicator, StochasticOscillator
    from ta.trend import MACD, EMAIndicator, SMAIndicator, ADXIndicator
    from ta.volatility import BollingerBands, AverageTrueRange
    TA_AVAILABLE = True
except ImportError:
    TA_AVAILABLE = False
    logger.warning("TA library not available")


router = APIRouter()


# Fallback static list (used if API fails)
FALLBACK_COINS = [
    "BTCUSDT", "ETHUSDT", "BNBUSDT", "XRPUSDT", "ADAUSDT",
    "DOGEUSDT", "SOLUSDT", "TRXUSDT", "DOTUSDT", "MATICUSDT",
    "LTCUSDT", "SHIBUSDT", "AVAXUSDT", "LINKUSDT", "ATOMUSDT",
    "UNIUSDT", "ETCUSDT", "XLMUSDT", "BCHUSDT", "APTUSDT",
    "FILUSDT", "LDOUSDT", "ARBUSDT", "NEARUSDT", "STXUSDT",
    "ICPUSDT", "AAVEUSDT", "GRTUSDT", "INJUSDT", "OPUSDT",
    "SUIUSDT", "SEIUSDT", "TIAUSDT", "JUPUSDT", "WLDUSDT"
]

# Cache for dynamic coin list
_cached_coins: List[str] = []
_cache_timestamp: Optional[datetime] = None
CACHE_DURATION_HOURS = 1  # Refresh every hour


async def fetch_top_coins_by_volume(limit: int = 200) -> List[str]:
    """
    Dynamically fetch top coins from Binance sorted by 24h volume.
    This ensures we always scan the most active/hot coins.

    Args:
        limit: Number of top coins to return (default 200)

    Returns:
        List of coin symbols sorted by volume (e.g., ['BTCUSDT', 'ETHUSDT', ...])
    """
    global _cached_coins, _cache_timestamp

    # Return cached list if still valid
    if _cached_coins and _cache_timestamp:
        cache_age = datetime.utcnow() - _cache_timestamp
        if cache_age < timedelta(hours=CACHE_DURATION_HOURS):
            logger.debug(f"Using cached coin list ({len(_cached_coins)} coins, age: {cache_age})")
            return _cached_coins[:limit]

    url = "https://api.binance.com/api/v3/ticker/24hr"

    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(url, timeout=30.0)
            response.raise_for_status()
            tickers = response.json()

            # Filter USDT pairs and sort by volume
            usdt_pairs = [
                t for t in tickers
                if t['symbol'].endswith('USDT')
                and float(t['quoteVolume']) > 100000  # Min $100k volume
                and not any(x in t['symbol'] for x in ['UP', 'DOWN', 'BEAR', 'BULL'])  # Exclude leveraged tokens
            ]

            # Sort by 24h quote volume (USD value)
            usdt_pairs.sort(key=lambda x: float(x['quoteVolume']), reverse=True)

            # Extract symbols
            top_coins = [t['symbol'] for t in usdt_pairs[:limit]]

            # Update cache
            _cached_coins = top_coins
            _cache_timestamp = datetime.utcnow()

            logger.info(f"Fetched {len(top_coins)} coins from Binance (sorted by volume)")
            logger.info(f"Top 10: {top_coins[:10]}")

            return top_coins

        except Exception as e:
            logger.error(f"Failed to fetch coins from Binance: {e}")
            logger.warning("Using fallback static coin list")
            return FALLBACK_COINS


# For backwards compatibility
TOP_100_COINS = FALLBACK_COINS


class AnalysisResult(BaseModel):
    """Single coin analysis result"""
    symbol: str
    timestamp: str
    price: float
    volume_24h: float
    price_change_24h: float

    # Technical indicators
    rsi: Optional[float] = None
    macd: Optional[float] = None
    macd_signal: Optional[float] = None
    ema_12: Optional[float] = None
    ema_26: Optional[float] = None
    ema_50: Optional[float] = None   # Medium term trend
    ema_200: Optional[float] = None  # Long term trend (used for trend filter)
    bb_upper: Optional[float] = None
    bb_lower: Optional[float] = None
    bb_middle: Optional[float] = None
    atr: Optional[float] = None

    # ADX - Average Directional Index (Trend Strength)
    # ADX > 25 = Strong trend, ADX < 20 = Weak/Sideways
    adx: Optional[float] = None
    adx_pos: Optional[float] = None  # +DI (bullish directional indicator)
    adx_neg: Optional[float] = None  # -DI (bearish directional indicator)

    # Volume Analysis
    volume_ratio: Optional[float] = None  # Current volume / 20-period average
    volume_spike: bool = False  # True if volume > 1.5x average

    # Trend info
    above_ema200: bool = False  # Is price above EMA200? (bullish trend)
    trend_strength: str = "WEAK"  # WEAK, MODERATE, STRONG, VERY_STRONG

    # Multi-Timeframe Analysis (1h + 4h)
    # Higher timeframe (4h) provides trend direction, lower (1h) provides entry timing
    higher_tf_ema50: Optional[float] = None  # 4h EMA50 value
    higher_tf_trend: str = "NEUTRAL"  # BULLISH, BEARISH, NEUTRAL (based on 4h EMA50)
    timeframes_aligned: bool = False  # True if 1h and 4h trends agree

    # Market Regime Detection
    # Classifies overall market condition for the coin
    market_regime: str = "UNKNOWN"  # TRENDING_UP, TRENDING_DOWN, RANGING, VOLATILE
    regime_confidence: float = 0.0  # 0-1, how confident we are in the regime classification
    bb_width: Optional[float] = None  # Bollinger Band width (volatility indicator)
    is_favorable_regime: bool = False  # True if regime is good for trading

    # Bullrun Detection - Coins showing strong bullish momentum
    # Used to boost signals and position sizes for hot coins
    bullrun_score: int = 0  # 0-100, higher = stronger bullrun signals
    is_bullrun: bool = False  # True if bullrun_score >= 65 (research-backed threshold)
    bullrun_signals: List[str] = []  # List of bullish signals detected

    # ML Decision
    ml_signal: str  # BUY, SELL, HOLD
    ml_score: int  # 0-100
    ml_confidence: float  # 0-1
    top_reasons: List[str] = []

    # Technical signal (rule-based)
    tech_signal: str
    tech_score: int


class FullAnalysisResponse(BaseModel):
    """Response for full analysis run"""
    timestamp: str
    coins_analyzed: int
    duration_ms: int
    strong_buys: List[AnalysisResult]
    strong_sells: List[AnalysisResult]
    top_opportunities: List[AnalysisResult]
    summary: Dict[str, int]


async def fetch_binance_klines(symbol: str, interval: str = "1h", limit: int = 100) -> List[Dict]:
    """Fetch klines from Binance API"""
    url = f"https://api.binance.com/api/v3/klines"
    params = {"symbol": symbol, "interval": interval, "limit": limit}

    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(url, params=params, timeout=10.0)
            response.raise_for_status()
            data = response.json()

            return [{
                "timestamp": k[0],
                "open": float(k[1]),
                "high": float(k[2]),
                "low": float(k[3]),
                "close": float(k[4]),
                "volume": float(k[5])
            } for k in data]
        except Exception as e:
            logger.error(f"Failed to fetch klines for {symbol}: {e}")
            return []


async def fetch_ticker_24h(symbol: str) -> Optional[Dict]:
    """Fetch 24h ticker data"""
    url = f"https://api.binance.com/api/v3/ticker/24hr"
    params = {"symbol": symbol}

    async with httpx.AsyncClient() as client:
        try:
            response = await client.get(url, params=params, timeout=10.0)
            response.raise_for_status()
            return response.json()
        except Exception as e:
            logger.error(f"Failed to fetch ticker for {symbol}: {e}")
            return None


def calculate_technical_indicators(klines: List[Dict]) -> Dict[str, Any]:
    """
    Calculate technical indicators from klines.

    Includes:
    - RSI, MACD, EMAs, Bollinger Bands, ATR (existing)
    - ADX (trend strength) - NEW
    - Volume ratio (volume confirmation) - NEW
    """
    if not TA_AVAILABLE or len(klines) < 26:
        return {}

    df = pd.DataFrame(klines)
    close = df['close']
    high = df['high']
    low = df['low']
    volume = df['volume']

    indicators = {}

    try:
        # RSI
        rsi = RSIIndicator(close, window=14)
        indicators['rsi'] = round(rsi.rsi().iloc[-1], 2)

        # MACD
        macd = MACD(close)
        indicators['macd'] = round(macd.macd().iloc[-1], 4)
        indicators['macd_signal'] = round(macd.macd_signal().iloc[-1], 4)

        # EMA - Short term
        ema12 = EMAIndicator(close, window=12)
        ema26 = EMAIndicator(close, window=26)
        indicators['ema_12'] = round(ema12.ema_indicator().iloc[-1], 4)
        indicators['ema_26'] = round(ema26.ema_indicator().iloc[-1], 4)

        # EMA50 - Medium term trend
        if len(close) >= 50:
            ema50 = EMAIndicator(close, window=50)
            indicators['ema_50'] = round(ema50.ema_indicator().iloc[-1], 4)

        # EMA200 - Long term trend (CRITICAL for trend filter)
        if len(close) >= 200:
            ema200 = EMAIndicator(close, window=200)
            indicators['ema_200'] = round(ema200.ema_indicator().iloc[-1], 4)

        # Bollinger Bands
        bb = BollingerBands(close, window=20, window_dev=2)
        indicators['bb_upper'] = round(bb.bollinger_hband().iloc[-1], 4)
        indicators['bb_lower'] = round(bb.bollinger_lband().iloc[-1], 4)
        indicators['bb_middle'] = round(bb.bollinger_mavg().iloc[-1], 4)

        # ATR
        atr = AverageTrueRange(high, low, close, window=14)
        indicators['atr'] = round(atr.average_true_range().iloc[-1], 4)

        # ============ NEW: ADX - Average Directional Index ============
        # ADX measures TREND STRENGTH (not direction)
        # ADX < 20: Weak trend / Sideways market - AVOID TRADING
        # ADX 20-25: Trend developing
        # ADX 25-50: Strong trend - GOOD FOR TRADING
        # ADX 50-75: Very strong trend
        # ADX > 75: Extremely strong (rare, often near reversal)
        if len(close) >= 14:
            adx_indicator = ADXIndicator(high, low, close, window=14)
            indicators['adx'] = round(adx_indicator.adx().iloc[-1], 2)
            indicators['adx_pos'] = round(adx_indicator.adx_pos().iloc[-1], 2)  # +DI
            indicators['adx_neg'] = round(adx_indicator.adx_neg().iloc[-1], 2)  # -DI

            # Determine trend strength label
            adx_value = indicators['adx']
            if adx_value >= 50:
                indicators['trend_strength'] = "VERY_STRONG"
            elif adx_value >= 25:
                indicators['trend_strength'] = "STRONG"
            elif adx_value >= 20:
                indicators['trend_strength'] = "MODERATE"
            else:
                indicators['trend_strength'] = "WEAK"

        # ============ NEW: Volume Analysis ============
        # Compare current volume to 20-period average
        # Volume spike = confirmation of price movement
        if len(volume) >= 20:
            avg_volume = volume.rolling(window=20).mean().iloc[-1]
            current_volume = volume.iloc[-1]

            if avg_volume > 0:
                volume_ratio = current_volume / avg_volume
                indicators['volume_ratio'] = round(volume_ratio, 2)
                # Volume spike = 1.5x or more above average
                indicators['volume_spike'] = volume_ratio >= 1.5
            else:
                indicators['volume_ratio'] = 1.0
                indicators['volume_spike'] = False

    except Exception as e:
        logger.warning(f"Error calculating indicators: {e}")

    return indicators


def detect_market_regime(price: float, indicators: Dict[str, Any]) -> Dict[str, Any]:
    """
    Detect the current market regime (trending, ranging, volatile).

    Returns:
        Dict with market_regime, regime_confidence, bb_width, is_favorable_regime
    """
    adx = indicators.get('adx', 0)
    adx_pos = indicators.get('adx_pos', 0)  # +DI
    adx_neg = indicators.get('adx_neg', 0)  # -DI
    bb_upper = indicators.get('bb_upper', 0)
    bb_lower = indicators.get('bb_lower', 0)
    bb_middle = indicators.get('bb_middle', 0)
    ema_50 = indicators.get('ema_50', 0)
    ema_200 = indicators.get('ema_200', 0)

    # Calculate Bollinger Band width (volatility measure)
    bb_width = 0.0
    if bb_middle and bb_middle > 0:
        bb_width = ((bb_upper - bb_lower) / bb_middle) * 100  # As percentage

    # Determine market regime
    regime = "UNKNOWN"
    confidence = 0.0
    is_favorable = False

    # Check for trending market (ADX > 25)
    if adx and adx >= 25:
        # Strong trend - determine direction
        if adx_pos > adx_neg and price > ema_50 and (not ema_200 or price > ema_200):
            regime = "TRENDING_UP"
            confidence = min(0.5 + (adx - 25) / 50, 1.0)  # Higher ADX = higher confidence
            is_favorable = True  # Good for buying
        elif adx_neg > adx_pos and price < ema_50:
            regime = "TRENDING_DOWN"
            confidence = min(0.5 + (adx - 25) / 50, 1.0)
            is_favorable = False  # Avoid buying in downtrends
        else:
            regime = "TRENDING_UP" if price > ema_50 else "TRENDING_DOWN"
            confidence = 0.5
            is_favorable = (regime == "TRENDING_UP")

    # Check for ranging/sideways market (ADX < 20)
    elif adx and adx < 20:
        # Weak trend - market is ranging
        if bb_width < 3:  # Very narrow bands = tight consolidation
            regime = "RANGING"
            confidence = 0.7
            is_favorable = False  # Avoid trading in ranges
        else:
            regime = "RANGING"
            confidence = 0.5
            is_favorable = False

    # Check for volatile market (high BB width, moderate ADX)
    elif bb_width and bb_width > 8:  # Wide bands = high volatility
        regime = "VOLATILE"
        confidence = 0.6
        is_favorable = False  # Too risky for standard entries

    # Moderate trend (ADX 20-25)
    else:
        if price > ema_50 and (not ema_200 or price > ema_200):
            regime = "TRENDING_UP"
            confidence = 0.4
            is_favorable = True  # Cautiously favorable
        elif price < ema_50:
            regime = "TRENDING_DOWN"
            confidence = 0.4
            is_favorable = False
        else:
            regime = "RANGING"
            confidence = 0.3
            is_favorable = False

    return {
        "market_regime": regime,
        "regime_confidence": round(confidence, 2),
        "bb_width": round(bb_width, 2) if bb_width else None,
        "is_favorable_regime": is_favorable
    }


def calculate_bullrun_score(
    price: float,
    price_change_24h: float,
    indicators: Dict[str, Any]
) -> Dict[str, Any]:
    """
    Calculate bullrun score for a coin.

    A high bullrun score indicates strong bullish momentum:
    - Score >= 85: HOT coin, very strong bullrun signals
    - Score >= 75: Strong bullrun, good entry
    - Score >= 65: Bullrun detected (research-backed threshold)
    - Score >= 50: Moderate bullish signals
    - Score < 50: Not in bullrun

    Factors:
    - Price above EMAs (EMA50, EMA200)
    - RSI in momentum zone (50-70)
    - MACD bullish crossover
    - Volume above average
    - ADX showing trend strength
    - Positive 24h price change
    """
    score = 0
    signals = []

    # Get indicators
    ema_50 = indicators.get('ema_50')
    ema_200 = indicators.get('ema_200')
    rsi = indicators.get('rsi')
    macd = indicators.get('macd')
    macd_signal = indicators.get('macd_signal')
    adx = indicators.get('adx', 0)
    adx_pos = indicators.get('adx_pos', 0)
    adx_neg = indicators.get('adx_neg', 0)
    volume_ratio = indicators.get('volume_ratio', 1.0)

    # 1. Price above EMA50 (+15 points)
    if ema_50 and price > ema_50:
        score += 15
        signals.append("Above EMA50")

    # 2. Price above EMA200 (+15 points) - Long term uptrend
    if ema_200 and price > ema_200:
        score += 15
        signals.append("Above EMA200")

    # 3. RSI in momentum zone 50-70 (+20 points)
    if rsi:
        if 50 <= rsi <= 70:
            score += 20
            signals.append(f"RSI {rsi:.0f} (momentum)")
        elif 40 <= rsi < 50:
            score += 10
            signals.append(f"RSI {rsi:.0f} (building)")
        elif rsi > 70:
            score += 5  # Overbought but still bullish
            signals.append(f"RSI {rsi:.0f} (overbought)")

    # 4. MACD bullish (+15 points)
    if macd is not None and macd_signal is not None:
        if macd > macd_signal:
            score += 15
            signals.append("MACD bullish")
        if macd > 0:
            score += 5
            signals.append("MACD positive")

    # 5. ADX trend strength with bullish direction (+15 points)
    if adx and adx >= 25 and adx_pos > adx_neg:
        score += 15
        signals.append(f"ADX {adx:.0f} (strong trend)")
    elif adx and adx >= 20 and adx_pos > adx_neg:
        score += 8
        signals.append(f"ADX {adx:.0f} (moderate trend)")

    # 6. Volume above average (+10 points)
    if volume_ratio and volume_ratio >= 1.5:
        score += 10
        signals.append(f"Volume +{(volume_ratio-1)*100:.0f}%")
    elif volume_ratio and volume_ratio >= 1.2:
        score += 5
        signals.append(f"Volume +{(volume_ratio-1)*100:.0f}%")

    # 7. Positive 24h price change (+10 points)
    if price_change_24h > 5:
        score += 10
        signals.append(f"24h +{price_change_24h:.1f}%")
    elif price_change_24h > 2:
        score += 5
        signals.append(f"24h +{price_change_24h:.1f}%")

    # Cap at 100
    score = min(score, 100)
    # Research suggests earlier detection improves momentum capture
    is_bullrun = score >= 65

    if is_bullrun:
        logger.info(f"🚀 BULLRUN detected! Score={score}, signals={signals}")

    return {
        "bullrun_score": score,
        "is_bullrun": is_bullrun,
        "bullrun_signals": signals
    }


def calculate_tech_signal(price: float, indicators: Dict[str, Any]) -> tuple:
    """
    Calculate technical signal (rule-based) with ADX and Volume filters.

    NEW FILTERS:
    1. ADX Filter: Only trade when trend is strong enough (ADX > 20)
    2. Volume Filter: Prefer entries with above-average volume
    3. +DI/-DI: Confirm trend direction
    """
    score = 50  # Neutral
    reasons = []

    # ============ ADX TREND STRENGTH FILTER ============
    # This is the most important filter - avoid trading in sideways markets
    adx = indicators.get('adx', 25)
    adx_pos = indicators.get('adx_pos', 0)  # +DI (bullish)
    adx_neg = indicators.get('adx_neg', 0)  # -DI (bearish)
    trend_strength = indicators.get('trend_strength', 'MODERATE')

    # Penalize weak trends heavily
    if adx < 20:
        score -= 20
        reasons.append(f"Weak trend (ADX {adx:.0f}) - AVOID")
    elif adx >= 25:
        score += 10
        reasons.append(f"Strong trend (ADX {adx:.0f})")
    elif adx >= 40:
        score += 15
        reasons.append(f"Very strong trend (ADX {adx:.0f})")

    # +DI vs -DI confirms direction
    if adx_pos > adx_neg:
        score += 5
        reasons.append(f"+DI > -DI (bullish momentum)")
    elif adx_neg > adx_pos:
        score -= 5
        reasons.append(f"-DI > +DI (bearish momentum)")

    # ============ VOLUME CONFIRMATION ============
    volume_ratio = indicators.get('volume_ratio', 1.0)
    volume_spike = indicators.get('volume_spike', False)

    if volume_spike:
        score += 10
        reasons.append(f"Volume spike ({volume_ratio:.1f}x avg)")
    elif volume_ratio < 0.5:
        score -= 10
        reasons.append(f"Low volume ({volume_ratio:.1f}x avg) - weak signal")

    # ============ RSI ============
    rsi = indicators.get('rsi', 50)
    if rsi < 30:
        score += 20
        reasons.append(f"RSI oversold ({rsi:.1f})")
    elif rsi > 70:
        score -= 20
        reasons.append(f"RSI overbought ({rsi:.1f})")
    elif rsi < 40:
        score += 10
        reasons.append(f"RSI low ({rsi:.1f})")
    elif rsi > 60:
        score -= 10
        reasons.append(f"RSI high ({rsi:.1f})")

    # ============ MACD ============
    macd = indicators.get('macd', 0)
    macd_signal = indicators.get('macd_signal', 0)
    if macd > macd_signal:
        score += 15
        reasons.append("MACD bullish crossover")
    else:
        score -= 15
        reasons.append("MACD bearish")

    # ============ EMA TREND (short term) ============
    ema12 = indicators.get('ema_12', price)
    ema26 = indicators.get('ema_26', price)
    if ema12 > ema26:
        score += 10
        reasons.append("EMA uptrend")
    else:
        score -= 10
        reasons.append("EMA downtrend")

    # ============ EMA200 (long term - CRITICAL) ============
    ema200 = indicators.get('ema_200')
    if ema200:
        if price > ema200:
            score += 10
            reasons.append("Above EMA200 (bullish trend)")
        else:
            score -= 15  # Stronger penalty for downtrend
            reasons.append("Below EMA200 (bearish trend)")

    # ============ BOLLINGER BANDS ============
    bb_upper = indicators.get('bb_upper', price * 1.1)
    bb_lower = indicators.get('bb_lower', price * 0.9)
    if price < bb_lower:
        score += 15
        reasons.append("Below Bollinger lower band")
    elif price > bb_upper:
        score -= 15
        reasons.append("Above Bollinger upper band")

    # ============ FINAL ADJUSTMENTS ============
    # If trend is weak AND no volume, strongly discourage trading
    if adx < 20 and volume_ratio < 1.0:
        score -= 10
        reasons.append("No trend + low volume = NO TRADE")

    # Clamp score
    score = max(0, min(100, score))

    # Determine signal
    if score >= 70:
        signal = "STRONG_BUY"
    elif score >= 55:
        signal = "BUY"
    elif score <= 30:
        signal = "STRONG_SELL"
    elif score <= 45:
        signal = "SELL"
    else:
        signal = "HOLD"

    return signal, score, reasons


async def analyze_single_coin(symbol: str) -> Optional[AnalysisResult]:
    """Analyze a single coin with ADX, Volume, and Multi-Timeframe filters"""
    try:
        # Fetch data - use 200 candles for EMA200 calculation
        # Also fetch 4h klines for multi-timeframe analysis
        klines_1h_task = fetch_binance_klines(symbol, interval="1h", limit=200)
        klines_4h_task = fetch_binance_klines(symbol, interval="4h", limit=60)  # ~10 days of 4h data
        ticker_task = fetch_ticker_24h(symbol)

        klines, klines_4h, ticker = await asyncio.gather(klines_1h_task, klines_4h_task, ticker_task)

        if not klines or not ticker:
            return None

        # Current price and stats
        price = float(ticker['lastPrice'])
        volume_24h = float(ticker['quoteVolume'])
        price_change_24h = float(ticker['priceChangePercent'])

        # Technical indicators (now includes ADX and Volume analysis)
        indicators = calculate_technical_indicators(klines)

        # Extract key indicators
        ema_200 = indicators.get('ema_200')
        above_ema200 = price > ema_200 if ema_200 else False
        trend_strength = indicators.get('trend_strength', 'MODERATE')
        volume_ratio = indicators.get('volume_ratio', 1.0)
        volume_spike = indicators.get('volume_spike', False)

        # Multi-Timeframe Analysis: Calculate 4h EMA50 for higher timeframe trend
        higher_tf_ema50 = None
        higher_tf_trend = "NEUTRAL"
        timeframes_aligned = False

        if klines_4h and len(klines_4h) >= 50:
            try:
                close_4h = pd.Series([float(k[4]) for k in klines_4h])
                higher_tf_ema50 = round(EMAIndicator(close_4h, window=50).ema_indicator().iloc[-1], 2)

                # Determine 4h trend: price above EMA50 = bullish, below = bearish
                if higher_tf_ema50:
                    if price > higher_tf_ema50 * 1.005:  # 0.5% above = clearly bullish
                        higher_tf_trend = "BULLISH"
                    elif price < higher_tf_ema50 * 0.995:  # 0.5% below = clearly bearish
                        higher_tf_trend = "BEARISH"
                    else:
                        higher_tf_trend = "NEUTRAL"  # Near the EMA = neutral

                # Check if timeframes are aligned
                # 1h trend (from EMA200) and 4h trend should agree
                trend_1h = "BULLISH" if above_ema200 else "BEARISH"
                timeframes_aligned = (trend_1h == higher_tf_trend) or (higher_tf_trend == "NEUTRAL")

                logger.debug(f"[{symbol}] MTF: 1h={trend_1h}, 4h={higher_tf_trend}, aligned={timeframes_aligned}")
            except Exception as e:
                logger.warning(f"[{symbol}] 4h analysis failed: {e}")

        # Market Regime Detection
        regime_info = detect_market_regime(price, indicators)
        market_regime = regime_info['market_regime']
        regime_confidence = regime_info['regime_confidence']
        bb_width = regime_info['bb_width']
        is_favorable_regime = regime_info['is_favorable_regime']

        logger.debug(f"[{symbol}] Regime: {market_regime} (conf={regime_confidence}, favorable={is_favorable_regime})")

        # Bullrun Detection - Check if coin is in a bullrun
        bullrun_info = calculate_bullrun_score(price, price_change_24h, indicators)
        bullrun_score = bullrun_info['bullrun_score']
        is_bullrun = bullrun_info['is_bullrun']
        bullrun_signals = bullrun_info['bullrun_signals']

        if is_bullrun:
            logger.info(f"[{symbol}] 🚀 BULLRUN! Score={bullrun_score}, signals={bullrun_signals}")

        # Tech signal (rule-based with ADX + Volume filters)
        tech_signal, tech_score, tech_reasons = calculate_tech_signal(price, indicators)

        # ML signal (if available)
        ml_signal = tech_signal
        ml_score = tech_score
        ml_confidence = 0.5 + (abs(tech_score - 50) / 100)  # Simple confidence based on score distance from neutral
        ml_reasons = tech_reasons

        if ML_AVAILABLE:
            try:
                # Use actual ML model
                model = HybridModel()
                prediction = model.predict(klines, indicators)
                ml_signal = prediction.signal
                ml_score = prediction.score
                ml_confidence = prediction.confidence
                ml_reasons = prediction.top_reasons
            except Exception as e:
                logger.warning(f"ML prediction failed for {symbol}, using tech signal: {e}")

        return AnalysisResult(
            symbol=symbol,
            timestamp=datetime.utcnow().isoformat(),
            price=price,
            volume_24h=volume_24h,
            price_change_24h=price_change_24h,
            # Traditional indicators
            rsi=indicators.get('rsi'),
            macd=indicators.get('macd'),
            macd_signal=indicators.get('macd_signal'),
            ema_12=indicators.get('ema_12'),
            ema_26=indicators.get('ema_26'),
            ema_50=indicators.get('ema_50'),
            ema_200=ema_200,
            bb_upper=indicators.get('bb_upper'),
            bb_lower=indicators.get('bb_lower'),
            bb_middle=indicators.get('bb_middle'),
            atr=indicators.get('atr'),
            # NEW: ADX indicators
            adx=indicators.get('adx'),
            adx_pos=indicators.get('adx_pos'),
            adx_neg=indicators.get('adx_neg'),
            # NEW: Volume analysis
            volume_ratio=volume_ratio,
            volume_spike=volume_spike,
            # Trend info
            above_ema200=above_ema200,
            trend_strength=trend_strength,
            # Multi-Timeframe
            higher_tf_ema50=higher_tf_ema50,
            higher_tf_trend=higher_tf_trend,
            timeframes_aligned=timeframes_aligned,
            # Market Regime
            market_regime=market_regime,
            regime_confidence=regime_confidence,
            bb_width=bb_width,
            is_favorable_regime=is_favorable_regime,
            # Bullrun Detection
            bullrun_score=bullrun_score,
            is_bullrun=is_bullrun,
            bullrun_signals=bullrun_signals,
            # Signals
            ml_signal=ml_signal,
            ml_score=ml_score,
            ml_confidence=ml_confidence,
            top_reasons=ml_reasons[:3],
            tech_signal=tech_signal,
            tech_score=tech_score
        )

    except Exception as e:
        logger.error(f"Failed to analyze {symbol}: {e}")
        return None


async def log_to_supabase(results: List[AnalysisResult], duration_ms: int):
    """Log analysis results to Supabase"""
    if not supabase:
        logger.warning("Supabase not configured, skipping log")
        return

    try:
        # Log individual coin analyses
        analysis_logs = []
        for r in results:
            analysis_logs.append({
                "coin": r.symbol,
                "timestamp": r.timestamp,
                "price": r.price,
                "volume_24h": r.volume_24h,
                "price_change_24h": r.price_change_24h,
                "rsi": r.rsi,
                "macd": r.macd,
                "macd_signal": r.macd_signal,
                "ema_12": r.ema_12,
                "ema_26": r.ema_26,
                "ema_50": r.ema_50,
                "ema_200": r.ema_200,
                "above_ema200": r.above_ema200,
                "bb_upper": r.bb_upper,
                "bb_lower": r.bb_lower,
                "ml_signal": r.ml_signal,
                "ml_score": r.ml_score,
                "ml_confidence": r.ml_confidence,
                "tech_signal": r.tech_signal,
                "tech_score": r.tech_score,
                "top_reasons": r.top_reasons
            })

        # Batch insert
        supabase.table("analysis_logs").insert(analysis_logs).execute()

        # Log run summary
        summary = {
            "executed_at": datetime.utcnow().isoformat(),
            "coins_analyzed": len(results),
            "duration_ms": duration_ms,
            "strong_buys": len([r for r in results if r.ml_signal == "STRONG_BUY"]),
            "strong_sells": len([r for r in results if r.ml_signal == "STRONG_SELL"]),
            "avg_confidence": sum(r.ml_confidence for r in results) / len(results) if results else 0
        }
        supabase.table("analysis_runs").insert(summary).execute()

        logger.info(f"Logged {len(results)} analyses to Supabase")

    except Exception as e:
        logger.error(f"Failed to log to Supabase: {e}")


@router.get("/run", response_model=FullAnalysisResponse)
async def run_full_analysis(
    background_tasks: BackgroundTasks,
    coins: int = 100,
    log_to_db: bool = True
):
    """
    Run full analysis on top coins (dynamically fetched by 24h volume)

    - **coins**: Number of coins to analyze (max 200, fetched from Binance by volume)
    - **log_to_db**: Whether to log results to Supabase
    """
    start_time = datetime.utcnow()

    # Dynamically fetch top coins by volume (up to 200)
    coins_to_analyze = await fetch_top_coins_by_volume(limit=min(coins, 200))

    # Analyze all coins concurrently (in batches to avoid rate limits)
    results: List[AnalysisResult] = []
    batch_size = 10

    for i in range(0, len(coins_to_analyze), batch_size):
        batch = coins_to_analyze[i:i + batch_size]
        batch_results = await asyncio.gather(*[analyze_single_coin(s) for s in batch])
        results.extend([r for r in batch_results if r is not None])

        # Small delay between batches to respect rate limits
        if i + batch_size < len(coins_to_analyze):
            await asyncio.sleep(0.5)

    duration_ms = int((datetime.utcnow() - start_time).total_seconds() * 1000)

    # Log to Supabase in background
    if log_to_db:
        background_tasks.add_task(log_to_supabase, results, duration_ms)

    # Categorize results
    strong_buys = sorted([r for r in results if r.ml_signal == "STRONG_BUY"],
                         key=lambda x: x.ml_score, reverse=True)
    strong_sells = sorted([r for r in results if r.ml_signal == "STRONG_SELL"],
                          key=lambda x: x.ml_score)
    top_opportunities = sorted(results, key=lambda x: x.ml_score, reverse=True)[:10]

    # Summary
    summary = {
        "STRONG_BUY": len([r for r in results if r.ml_signal == "STRONG_BUY"]),
        "BUY": len([r for r in results if r.ml_signal == "BUY"]),
        "HOLD": len([r for r in results if r.ml_signal == "HOLD"]),
        "SELL": len([r for r in results if r.ml_signal == "SELL"]),
        "STRONG_SELL": len([r for r in results if r.ml_signal == "STRONG_SELL"])
    }

    logger.info(f"Analysis complete: {len(results)} coins in {duration_ms}ms")
    logger.info(f"Summary: {summary}")

    return FullAnalysisResponse(
        timestamp=datetime.utcnow().isoformat(),
        coins_analyzed=len(results),
        duration_ms=duration_ms,
        strong_buys=strong_buys,
        strong_sells=strong_sells,
        top_opportunities=top_opportunities,
        summary=summary
    )


@router.get("/coin/{symbol}")
async def analyze_coin(symbol: str):
    """Analyze a single coin"""
    symbol = symbol.upper()
    if not symbol.endswith("USDT"):
        symbol += "USDT"

    result = await analyze_single_coin(symbol)
    if not result:
        raise HTTPException(status_code=404, detail=f"Could not analyze {symbol}")

    return result


@router.get("/coins")
async def list_coins():
    """List all supported coins (dynamically fetched by volume)"""
    coins = await fetch_top_coins_by_volume(limit=200)
    return {"coins": coins, "count": len(coins), "source": "dynamic_binance_api"}


# ==================== BOT ENDPOINTS ====================

@router.get("/bot/status")
async def get_bot_status():
    """Get current bot status, balance, and positions"""
    if not BOT_AVAILABLE or not autonomous_bot:
        raise HTTPException(status_code=503, detail="Trading bot not available")

    try:
        status = await autonomous_bot.get_status()
        return status
    except Exception as e:
        logger.error(f"Failed to get bot status: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/bot/trade")
async def trigger_bot_trading(use_latest: bool = True, coins: int = 20):
    """
    Trigger bot to process signals and execute trades

    - **use_latest**: Use latest analysis_logs instead of running new analysis
    - **coins**: Number of coins to analyze if running new analysis
    """
    if not BOT_AVAILABLE or not autonomous_bot:
        raise HTTPException(status_code=503, detail="Trading bot not available")

    try:
        if use_latest and supabase:
            # Get latest analysis logs from Supabase
            result = supabase.table("analysis_logs") \
                .select("*") \
                .order("timestamp", desc=True) \
                .limit(100) \
                .execute()

            # Group by coin and get latest for each
            latest_by_coin = {}
            for log in result.data:
                coin = log.get('coin')
                if coin not in latest_by_coin:
                    latest_by_coin[coin] = log

            analysis_results = list(latest_by_coin.values())
        else:
            # Run fresh analysis with dynamic coin list
            coins_to_analyze = await fetch_top_coins_by_volume(limit=min(coins, 200))
            analysis_results = []

            for i in range(0, len(coins_to_analyze), 10):
                batch = coins_to_analyze[i:i + 10]
                batch_results = await asyncio.gather(*[analyze_single_coin(s) for s in batch])
                for r in batch_results:
                    if r:
                        analysis_results.append(r.model_dump())
                await asyncio.sleep(0.5)

        # Process with bot
        bot_result = await autonomous_bot.process_analysis_results(analysis_results)
        return bot_result

    except Exception as e:
        logger.error(f"Bot trading failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/bot/trades")
async def get_bot_trades(limit: int = 50):
    """Get bot trade history"""
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        result = supabase.table("bot_trades") \
            .select("*") \
            .order("opened_at", desc=True) \
            .limit(limit) \
            .execute()

        return {"trades": result.data, "count": len(result.data)}

    except Exception as e:
        logger.error(f"Failed to get bot trades: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/bot/positions")
async def get_bot_positions():
    """Get current open positions with live prices and unrealized PnL"""
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        result = supabase.table("bot_positions") \
            .select("*") \
            .execute()

        positions = result.data

        # Fetch live prices for all positions
        if positions and EXCHANGE_AVAILABLE and exchange_service:
            symbols = [f"{p['coin']}/USDT" for p in positions]
            try:
                tickers = await exchange_service.get_multiple_tickers(symbols)

                total_unrealized_pnl = 0.0
                total_position_value = 0.0

                for position in positions:
                    symbol = f"{position['coin']}/USDT"
                    entry_price = float(position.get('entry_price', 0))
                    quantity = float(position.get('quantity', 0))

                    if symbol in tickers:
                        current_price = tickers[symbol].price
                        position['current_price'] = current_price

                        # Calculate unrealized PnL
                        entry_value = entry_price * quantity
                        current_value = current_price * quantity
                        unrealized_pnl = current_value - entry_value
                        unrealized_pnl_pct = ((current_price - entry_price) / entry_price * 100) if entry_price > 0 else 0

                        position['unrealized_pnl'] = round(unrealized_pnl, 2)
                        position['unrealized_pnl_pct'] = round(unrealized_pnl_pct, 2)
                        position['current_value'] = round(current_value, 2)

                        total_unrealized_pnl += unrealized_pnl
                        total_position_value += current_value
                    else:
                        position['current_price'] = entry_price
                        position['unrealized_pnl'] = 0
                        position['unrealized_pnl_pct'] = 0
                        position['current_value'] = entry_price * quantity

            except Exception as e:
                logger.warning(f"Could not fetch live prices: {e}")
                # Keep positions without live data
                total_unrealized_pnl = 0
                total_position_value = sum(float(p.get('entry_price', 0)) * float(p.get('quantity', 0)) for p in positions)
        else:
            total_unrealized_pnl = 0
            total_position_value = sum(float(p.get('entry_price', 0)) * float(p.get('quantity', 0)) for p in positions)

        return {
            "positions": positions,
            "count": len(positions),
            "total_unrealized_pnl": round(total_unrealized_pnl, 2),
            "total_position_value": round(total_position_value, 2)
        }

    except Exception as e:
        logger.error(f"Failed to get bot positions: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/bot/learning")
async def get_bot_learning_insights():
    """
    Get comprehensive learning insights from past trades

    Analyzes:
    - Signal type performance (STRONG_BUY vs BUY etc.)
    - Coin performance (which coins profit/lose)
    - Score bracket analysis (which signal scores work best)
    - Recommendations for optimization
    """
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        result = supabase.rpc("get_bot_learning_insights").execute()
        return result.data

    except Exception as e:
        logger.error(f"Failed to get learning insights: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/bot/optimize")
async def auto_optimize_bot():
    """
    Auto-optimize bot settings based on historical performance

    This will:
    - Analyze which signal scores perform best
    - Remove poorly performing coins from enabled_coins
    - Adjust min_signal_score based on data

    Requires at least 10 closed trades to optimize.
    """
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        result = supabase.rpc("bot_auto_optimize").execute()
        logger.info(f"Bot optimization result: {result.data}")
        return result.data

    except Exception as e:
        logger.error(f"Failed to optimize bot: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/bot/analysis/signals")
async def get_signal_analysis():
    """Get performance breakdown by signal type"""
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        result = supabase.table("bot_signal_analysis").select("*").execute()
        return {"signal_analysis": result.data}

    except Exception as e:
        logger.error(f"Failed to get signal analysis: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/bot/analysis/coins")
async def get_coin_analysis():
    """Get performance breakdown by coin"""
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        result = supabase.table("bot_coin_analysis").select("*").execute()
        return {"coin_analysis": result.data}

    except Exception as e:
        logger.error(f"Failed to get coin analysis: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/run-and-trade")
async def run_analysis_and_trade(
    background_tasks: BackgroundTasks,
    coins: int = 100
):
    """
    Run full analysis AND trigger bot trading (coins fetched dynamically by volume)

    This is the main endpoint for scheduled execution.
    Analyzes top coins (up to 200), logs to Supabase, then executes trades.
    Coin list is refreshed hourly from Binance sorted by 24h volume.
    """
    start_time = datetime.utcnow()

    # Dynamically fetch top coins by volume
    coins_to_analyze = await fetch_top_coins_by_volume(limit=min(coins, 200))
    results: List[AnalysisResult] = []
    batch_size = 10

    for i in range(0, len(coins_to_analyze), batch_size):
        batch = coins_to_analyze[i:i + batch_size]
        batch_results = await asyncio.gather(*[analyze_single_coin(s) for s in batch])
        results.extend([r for r in batch_results if r is not None])
        if i + batch_size < len(coins_to_analyze):
            await asyncio.sleep(0.5)

    duration_ms = int((datetime.utcnow() - start_time).total_seconds() * 1000)

    # Log to Supabase in background
    background_tasks.add_task(log_to_supabase, results, duration_ms)

    # Execute bot trades
    bot_result = {"status": "bot_unavailable"}
    if BOT_AVAILABLE and autonomous_bot:
        try:
            analysis_dicts = [r.model_dump() for r in results]
            bot_result = await autonomous_bot.process_analysis_results(analysis_dicts)
        except Exception as e:
            logger.error(f"Bot trading failed: {e}")
            bot_result = {"error": str(e)}

    # Summary
    summary = {
        "STRONG_BUY": len([r for r in results if r.ml_signal == "STRONG_BUY"]),
        "BUY": len([r for r in results if r.ml_signal == "BUY"]),
        "HOLD": len([r for r in results if r.ml_signal == "HOLD"]),
        "SELL": len([r for r in results if r.ml_signal == "SELL"]),
        "STRONG_SELL": len([r for r in results if r.ml_signal == "STRONG_SELL"])
    }

    return {
        "timestamp": datetime.utcnow().isoformat(),
        "analysis": {
            "coins_analyzed": len(results),
            "duration_ms": duration_ms,
            "summary": summary
        },
        "bot": bot_result
    }


# ==================== ML TRAINING ENDPOINTS ====================

@router.get("/ml/stats")
async def get_ml_training_stats():
    """Get ML training data statistics"""
    if not TRAINER_AVAILABLE or not ml_trainer:
        raise HTTPException(status_code=503, detail="ML trainer not available")

    try:
        stats = await ml_trainer.get_training_stats()
        return stats
    except Exception as e:
        logger.error(f"Failed to get ML stats: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/ml/label")
async def trigger_ml_labeling():
    """
    Trigger auto-labeling of training data

    This looks at analysis_logs older than 24h and labels them
    based on what happened to the price afterwards.
    """
    if not TRAINER_AVAILABLE or not ml_trainer:
        raise HTTPException(status_code=503, detail="ML trainer not available")

    try:
        result = await ml_trainer.trigger_labeling()
        return result
    except Exception as e:
        logger.error(f"Labeling failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/ml/train")
async def train_ml_models(min_samples: int = 500):
    """
    Train ML models (LSTM + XGBoost)

    Requires at least min_samples labeled data points.
    Training takes 2-5 minutes depending on data size.
    """
    if not TRAINER_AVAILABLE or not ml_trainer:
        raise HTTPException(status_code=503, detail="ML trainer not available")

    try:
        result = await ml_trainer.train_all(min_samples=min_samples)
        return result
    except Exception as e:
        logger.error(f"Training failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/ml/status")
async def get_ml_model_status():
    """Check if trained ML models are available"""
    from pathlib import Path

    model_dir = Path("models")
    lstm_exists = (model_dir / "lstm_encoder.pt").exists()
    xgb_exists = (model_dir / "xgboost_model.json").exists()

    # Get training stats
    stats = {}
    if TRAINER_AVAILABLE and ml_trainer:
        try:
            stats = await ml_trainer.get_training_stats()
        except:
            pass

    return {
        "lstm_model_available": lstm_exists,
        "xgboost_model_available": xgb_exists,
        "models_directory": str(model_dir.absolute()),
        "training_data": stats,
        "pytorch_available": TORCH_AVAILABLE if 'TORCH_AVAILABLE' in dir() else False,
        "xgboost_available": XGBOOST_AVAILABLE if 'XGBOOST_AVAILABLE' in dir() else False
    }


class BullrunCoin(BaseModel):
    """Coin with bullrun indicators"""
    symbol: str
    price: float
    price_change_24h: float
    volume_change: float  # vs 20-day average
    bullrun_score: int  # 0-100
    signals: List[str]  # List of bullish signals
    rsi: Optional[float] = None
    above_ema50: bool = False
    above_ema200: bool = False
    macd_bullish: bool = False


@router.get("/bullrun-scanner")
async def get_bullrun_coins(limit: int = 10):
    """
    Scan market for coins showing bullrun characteristics.

    Returns top coins ranked by bullrun score based on:
    - Price above EMA50 and EMA200
    - RSI between 50-70 (momentum without overbought)
    - MACD bullish crossover
    - Volume above 20-day average
    - Positive price momentum
    """
    bullrun_coins = []

    # Dynamically fetch top coins by volume for bullrun scan
    coins_to_scan = await fetch_top_coins_by_volume(limit=100)  # Scan top 100 by volume
    batch_size = 10

    for i in range(0, len(coins_to_scan), batch_size):
        batch = coins_to_scan[i:i + batch_size]
        batch_results = await asyncio.gather(
            *[analyze_bullrun_coin(symbol) for symbol in batch],
            return_exceptions=True
        )

        for result in batch_results:
            if isinstance(result, BullrunCoin) and result.bullrun_score >= 50:
                bullrun_coins.append(result)

        if i + batch_size < len(coins_to_scan):
            await asyncio.sleep(0.3)

    # Sort by bullrun score
    bullrun_coins.sort(key=lambda x: x.bullrun_score, reverse=True)

    # Calculate market summary
    total_bullish = len([c for c in bullrun_coins if c.bullrun_score >= 65])
    total_moderate = len([c for c in bullrun_coins if 50 <= c.bullrun_score < 70])

    return {
        "timestamp": datetime.utcnow().isoformat(),
        "market_summary": {
            "coins_scanned": len(coins_to_scan),
            "strong_bullrun": total_bullish,
            "moderate_bullish": total_moderate,
            "market_sentiment": "BULLISH" if total_bullish >= 5 else "NEUTRAL" if total_moderate >= 5 else "BEARISH"
        },
        "top_bullrun_coins": [coin.model_dump() for coin in bullrun_coins[:limit]]
    }


async def analyze_bullrun_coin(symbol: str) -> Optional[BullrunCoin]:
    """Analyze a single coin for bullrun characteristics"""
    try:
        # Fetch klines (1h, 200 candles for EMA200)
        klines = await fetch_binance_klines(symbol, interval="1h", limit=200)
        if not klines or len(klines) < 50:
            return None

        # Get 24h ticker for price change and volume
        async with httpx.AsyncClient(timeout=10.0) as client:
            ticker_resp = await client.get(
                f"https://api.binance.com/api/v3/ticker/24hr?symbol={symbol}"
            )
            if ticker_resp.status_code != 200:
                return None
            ticker = ticker_resp.json()

        price = float(ticker['lastPrice'])
        price_change_24h = float(ticker['priceChangePercent'])
        volume_24h = float(ticker['quoteVolume'])

        if not TA_AVAILABLE:
            # Fallback without TA library
            return BullrunCoin(
                symbol=symbol.replace("USDT", ""),
                price=price,
                price_change_24h=price_change_24h,
                volume_change=0,
                bullrun_score=50 if price_change_24h > 0 else 30,
                signals=["Price momentum positive"] if price_change_24h > 0 else []
            )

        # Calculate indicators
        df = pd.DataFrame(klines)
        df['close'] = df['close'].astype(float)
        df['volume'] = df['volume'].astype(float)

        # EMAs
        ema50 = EMAIndicator(df['close'], window=50).ema_indicator().iloc[-1]
        ema200 = EMAIndicator(df['close'], window=200).ema_indicator().iloc[-1] if len(df) >= 200 else None

        # RSI
        rsi = RSIIndicator(df['close'], window=14).rsi().iloc[-1]

        # MACD
        macd_indicator = MACD(df['close'])
        macd_line = macd_indicator.macd().iloc[-1]
        macd_signal = macd_indicator.macd_signal().iloc[-1]
        macd_bullish = macd_line > macd_signal

        # Volume analysis (compare to 20-day average)
        avg_volume_20d = df['volume'].tail(20).mean()
        current_volume = df['volume'].iloc[-1]
        volume_change = ((current_volume / avg_volume_20d) - 1) * 100 if avg_volume_20d > 0 else 0

        # Check conditions
        above_ema50 = price > ema50 if ema50 else False
        above_ema200 = price > ema200 if ema200 else False

        # Calculate bullrun score
        score = 0
        signals = []

        # Price above EMAs (+30 points)
        if above_ema50:
            score += 15
            signals.append("Above EMA50")
        if above_ema200:
            score += 15
            signals.append("Above EMA200")

        # RSI in sweet spot 50-70 (+20 points)
        if rsi and 50 <= rsi <= 70:
            score += 20
            signals.append(f"RSI {rsi:.0f} (momentum)")
        elif rsi and 40 <= rsi < 50:
            score += 10
            signals.append(f"RSI {rsi:.0f} (building)")

        # MACD bullish (+15 points)
        if macd_bullish:
            score += 15
            signals.append("MACD bullish")

        # Volume above average (+15 points)
        if volume_change > 50:
            score += 15
            signals.append(f"Volume +{volume_change:.0f}%")
        elif volume_change > 20:
            score += 10
            signals.append(f"Volume +{volume_change:.0f}%")

        # Positive price momentum (+20 points)
        if price_change_24h >= 5:
            score += 20
            signals.append(f"+{price_change_24h:.1f}% 24h")
        elif price_change_24h >= 2:
            score += 15
            signals.append(f"+{price_change_24h:.1f}% 24h")
        elif price_change_24h > 0:
            score += 5
            signals.append(f"+{price_change_24h:.1f}% 24h")

        return BullrunCoin(
            symbol=symbol.replace("USDT", ""),
            price=price,
            price_change_24h=price_change_24h,
            volume_change=volume_change,
            bullrun_score=min(score, 100),
            signals=signals,
            rsi=rsi,
            above_ema50=above_ema50,
            above_ema200=above_ema200,
            macd_bullish=macd_bullish
        )

    except Exception as e:
        logger.debug(f"Failed to analyze {symbol} for bullrun: {e}")
        return None


@router.get("/bot/settings")
async def get_bot_settings():
    """
    Get current bot settings including trading type configuration.
    """
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        result = supabase.table("bot_settings").select("*").limit(1).execute()

        settings = result.data[0] if result.data else {}

        # Add exchange trading info
        trading_info = {}
        if EXCHANGE_AVAILABLE and exchange_service:
            trading_info = exchange_service.get_trading_info()

        return {
            "bot_settings": settings,
            "trading_config": trading_info,
            "available_trading_types": ["spot", "margin", "future"],
            "available_leverage": [1, 2, 3, 5, 10, 20, 50, 75, 100, 125]
        }

    except Exception as e:
        logger.error(f"Failed to get bot settings: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/bot/settings")
async def update_bot_settings(
    trading_type: str = None,
    leverage: int = None,
    min_signal_score: int = None,
    max_positions: int = None,
    max_position_size_percent: float = None,
    stop_loss_percent: float = None,
    take_profit_percent: float = None,
    trailing_stop_percent: float = None,
    is_active: bool = None,
    enabled_coins: list = None
):
    """
    Update bot settings including trading type (spot/margin/future).

    Args:
        trading_type: 'spot', 'margin', or 'future'
        leverage: 1-125 (for margin/future)
        min_signal_score: Minimum ML score to trade (0-100)
        max_positions: Maximum concurrent positions
        stop_loss_percent: Stop loss % (negative, e.g., -2.5)
        take_profit_percent: Take profit % (positive, e.g., 5.0)
        trailing_stop_percent: Trailing stop % (e.g., 1.5)
        is_active: Enable/disable bot
        enabled_coins: List of coins to trade ['BTC', 'ETH', ...]
    """
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        updates = {}
        exchange_updates = {}

        # Trading type and leverage (for exchange)
        if trading_type:
            if trading_type not in ["spot", "margin", "future"]:
                raise HTTPException(status_code=400, detail="Invalid trading_type. Use 'spot', 'margin', or 'future'")
            updates["trading_type"] = trading_type
            exchange_updates["trading_type"] = trading_type

            if EXCHANGE_AVAILABLE and exchange_service:
                exchange_service.set_trading_type(trading_type)

        if leverage is not None:
            if leverage < 1 or leverage > 125:
                raise HTTPException(status_code=400, detail="Leverage must be between 1 and 125")
            updates["leverage"] = leverage
            exchange_updates["leverage"] = leverage

            if EXCHANGE_AVAILABLE and exchange_service:
                exchange_service.set_leverage(leverage)

        # Bot-specific settings
        if min_signal_score is not None:
            updates["min_signal_score"] = min_signal_score

        if max_positions is not None:
            updates["max_positions"] = max_positions

        if max_position_size_percent is not None:
            updates["max_position_size_percent"] = max_position_size_percent

        if stop_loss_percent is not None:
            updates["stop_loss_percent"] = stop_loss_percent

        if take_profit_percent is not None:
            updates["take_profit_percent"] = take_profit_percent

        if trailing_stop_percent is not None:
            updates["trailing_stop_percent"] = trailing_stop_percent

        if is_active is not None:
            updates["is_active"] = is_active

        if enabled_coins is not None:
            updates["enabled_coins"] = enabled_coins

        # Update database
        if updates:
            updates["updated_at"] = datetime.utcnow().isoformat()
            supabase.table("bot_settings").update(updates).eq("id", 1).execute()

        # Get updated settings
        result = supabase.table("bot_settings").select("*").limit(1).execute()
        updated_settings = result.data[0] if result.data else {}

        return {
            "success": True,
            "message": "Settings updated successfully",
            "settings": updated_settings,
            "exchange_config": exchange_service.get_trading_info() if EXCHANGE_AVAILABLE and exchange_service else {}
        }

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update bot settings: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/bot/check-stops")
async def check_stop_losses():
    """
    Check stop-losses for all open positions using live prices.

    This endpoint should be called frequently (every 1 minute) to ensure
    stop-losses are triggered promptly even in fast-moving markets.

    This is a lightweight endpoint that only:
    1. Fetches current prices for open positions
    2. Checks if any position hit stop-loss or take-profit
    3. Executes sells if needed

    Returns:
        Summary of positions checked and any trades executed
    """
    if not BOT_AVAILABLE or not autonomous_bot:
        return {"error": "Bot not available", "positions_checked": 0}

    try:
        result = await autonomous_bot.check_stop_losses()
        return result
    except Exception as e:
        logger.error(f"Stop-loss check failed: {e}")
        return {"error": str(e), "positions_checked": 0}


# ============================================
# BACKTESTING ENDPOINTS
# ============================================

try:
    from app.services.backtester import backtester, EnhancedBacktestConfig
    BACKTESTER_AVAILABLE = True
except ImportError:
    BACKTESTER_AVAILABLE = False
    logger.warning("Backtester not available")


class BacktestRequest(BaseModel):
    """Request parameters for enhanced backtest"""
    symbol: str = "BTC/USDT"
    days: int = 90
    initial_capital: float = 10000.0
    position_size_pct: float = 10.0
    stop_loss_pct: float = -5.0
    take_profit_pct: float = 10.0
    min_adx: float = 20.0
    min_volume_ratio: float = 0.5
    require_ema200_above: bool = True
    require_timeframe_alignment: bool = True
    require_favorable_regime: bool = True
    use_dynamic_sizing: bool = True


class BacktestResponse(BaseModel):
    """Backtest result summary"""
    symbol: str
    days_tested: int
    total_return_pct: float
    buy_and_hold_return_pct: float
    alpha: float
    win_rate: float
    total_trades: int
    winning_trades: int
    losing_trades: int
    profit_factor: float
    max_drawdown_pct: float
    sharpe_ratio: float
    avg_win_pct: float
    avg_loss_pct: float
    largest_win_pct: float
    largest_loss_pct: float
    final_capital: float


@router.post("/backtest", response_model=BacktestResponse)
async def run_enhanced_backtest(request: BacktestRequest):
    """
    Run enhanced backtest with all our trading filters.

    Tests the exact same strategy logic as the live trading bot:
    - ADX filter (trend strength > 20)
    - Volume ratio filter (> 0.5)
    - EMA200 trend filter
    - Multi-timeframe confirmation (1h + 4h)
    - Market regime detection
    - Dynamic position sizing

    **Note**: This can take 30-60 seconds to run as it fetches historical data.
    """
    if not BACKTESTER_AVAILABLE:
        raise HTTPException(status_code=503, detail="Backtester not available")

    try:
        # Create config from request
        config = EnhancedBacktestConfig(
            initial_capital=request.initial_capital,
            position_size_pct=request.position_size_pct,
            stop_loss_pct=request.stop_loss_pct,
            take_profit_pct=request.take_profit_pct,
            min_adx=request.min_adx,
            min_volume_ratio=request.min_volume_ratio,
            require_ema200_above=request.require_ema200_above,
            require_timeframe_alignment=request.require_timeframe_alignment,
            require_favorable_regime=request.require_favorable_regime,
            use_dynamic_sizing=request.use_dynamic_sizing
        )

        # Run backtest
        result = await backtester.run_enhanced_backtest(
            symbol=request.symbol,
            days=request.days,
            config=config
        )

        # Calculate final capital
        final_capital = request.initial_capital * (1 + result.total_return_pct / 100)

        return BacktestResponse(
            symbol=request.symbol,
            days_tested=result.duration_days,
            total_return_pct=round(result.total_return_pct, 2),
            buy_and_hold_return_pct=round(result.buy_and_hold_return_pct, 2),
            alpha=round(result.alpha, 2),
            win_rate=round(result.win_rate, 1),
            total_trades=result.total_trades,
            winning_trades=result.winning_trades,
            losing_trades=result.losing_trades,
            profit_factor=round(result.profit_factor, 2) if result.profit_factor != float('inf') else 999.99,
            max_drawdown_pct=round(result.max_drawdown_pct, 2),
            sharpe_ratio=round(result.sharpe_ratio, 2),
            avg_win_pct=round(result.avg_win_pct, 2),
            avg_loss_pct=round(result.avg_loss_pct, 2),
            largest_win_pct=round(result.largest_win_pct, 2),
            largest_loss_pct=round(result.largest_loss_pct, 2),
            final_capital=round(final_capital, 2)
        )

    except Exception as e:
        logger.error(f"Backtest failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/backtest/quick/{symbol}")
async def quick_backtest(symbol: str = "BTCUSDT", days: int = 30):
    """
    Run a quick backtest with default settings.

    Good for quickly testing the strategy on different coins.
    Uses 30 days of data by default.
    """
    if not BACKTESTER_AVAILABLE:
        raise HTTPException(status_code=503, detail="Backtester not available")

    # Convert BTCUSDT to BTC/USDT format
    if "/" not in symbol:
        symbol = symbol.replace("USDT", "/USDT")

    try:
        result = await backtester.run_enhanced_backtest(
            symbol=symbol,
            days=days
        )

        return {
            "symbol": symbol,
            "days": days,
            "total_return_pct": round(result.total_return_pct, 2),
            "win_rate": round(result.win_rate, 1),
            "total_trades": result.total_trades,
            "winning_trades": result.winning_trades,
            "losing_trades": result.losing_trades,
            "max_drawdown_pct": round(result.max_drawdown_pct, 2),
            "sharpe_ratio": round(result.sharpe_ratio, 2),
            "buy_and_hold_return_pct": round(result.buy_and_hold_return_pct, 2),
            "strategy_outperformed": result.alpha > 0
        }

    except Exception as e:
        logger.error(f"Quick backtest failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# =============================================
# SELF-LEARNING SYSTEM ENDPOINTS
# =============================================

@router.post("/learning/update-exits")
async def update_exit_analysis():
    """
    Update post-exit price tracking for learning.
    Checks prices 1h, 4h, 24h, 48h after each exit to learn if we exited too early.
    Should be called periodically (e.g., every hour).
    """
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        # Get incomplete exit analyses
        result = supabase.table("bot_exit_analysis") \
            .select("*") \
            .eq("analysis_complete", False) \
            .execute()

        if not result.data:
            return {"message": "No pending exit analyses", "updated": 0}

        updated = 0
        now = datetime.utcnow()

        for exit_record in result.data:
            coin = exit_record['coin']
            exit_at = datetime.fromisoformat(exit_record['exit_at'].replace('Z', '+00:00'))
            exit_price = float(exit_record['exit_price'])

            hours_since_exit = (now - exit_at.replace(tzinfo=None)).total_seconds() / 3600

            # Get current price
            try:
                symbol = f"{coin}USDT"
                url = f"https://api.binance.com/api/v3/ticker/price?symbol={symbol}"

                async with httpx.AsyncClient() as client:
                    response = await client.get(url, timeout=10.0)
                    if response.status_code == 200:
                        current_price = float(response.json()['price'])
                    else:
                        continue

                update_data = {}

                # Update price snapshots based on time since exit
                if hours_since_exit >= 1 and exit_record.get('price_1h_after') is None:
                    update_data['price_1h_after'] = current_price

                if hours_since_exit >= 4 and exit_record.get('price_4h_after') is None:
                    update_data['price_4h_after'] = current_price

                if hours_since_exit >= 24 and exit_record.get('price_24h_after') is None:
                    update_data['price_24h_after'] = current_price

                if hours_since_exit >= 48 and exit_record.get('price_48h_after') is None:
                    update_data['price_48h_after'] = current_price

                # Track highest price after exit
                highest = exit_record.get('highest_after_exit') or exit_price
                if current_price > highest:
                    update_data['highest_after_exit'] = current_price
                    update_data['highest_after_exit_at'] = now.isoformat()

                # Track lowest price after exit
                lowest = exit_record.get('lowest_after_exit') or exit_price
                if current_price < lowest:
                    update_data['lowest_after_exit'] = current_price

                # Complete analysis after 48h
                if hours_since_exit >= 48:
                    highest_after = update_data.get('highest_after_exit') or exit_record.get('highest_after_exit') or exit_price
                    missed_profit = ((highest_after / exit_price) - 1) * 100

                    update_data['missed_profit_percent'] = round(missed_profit, 2)
                    update_data['exit_was_optimal'] = missed_profit < 2.0  # Less than 2% missed = optimal
                    update_data['should_have_held'] = missed_profit > 5.0  # More than 5% missed = should have held
                    update_data['analysis_complete'] = True
                    update_data['analyzed_at'] = now.isoformat()

                if update_data:
                    supabase.table("bot_exit_analysis") \
                        .update(update_data) \
                        .eq("id", exit_record['id']) \
                        .execute()
                    updated += 1

            except Exception as e:
                logger.warning(f"Failed to update exit analysis for {coin}: {e}")
                continue

        return {
            "message": f"Updated {updated} exit analyses",
            "updated": updated,
            "pending": len(result.data) - updated
        }

    except Exception as e:
        logger.error(f"Failed to update exit analyses: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/learning/auto-tune")
async def auto_tune_trailing_stops():
    """
    Automatically adjust trailing stop multipliers based on exit analysis.

    Decision matrix:
    - premature_exit_rate < 40%: System OK, no change
    - premature_exit_rate 40-60%: Slight increase (1.1x)
    - premature_exit_rate 60-80%: Moderate increase (1.2x)
    - premature_exit_rate > 80%: Significant increase (1.3x)
    """
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        # Get tuning settings
        tuning_result = supabase.table("bot_tuning").select("*").limit(1).execute()
        if not tuning_result.data:
            return {"error": "No tuning settings found"}

        tuning = tuning_result.data[0]
        min_trades = tuning.get('min_trades_for_learning', 20)
        window_days = tuning.get('learning_window_days', 7)

        # Get exit stats from the last N days
        cutoff_date = (datetime.utcnow() - timedelta(days=window_days)).isoformat()

        stats_result = supabase.table("bot_exit_analysis") \
            .select("*") \
            .eq("analysis_complete", True) \
            .gte("exit_at", cutoff_date) \
            .execute()

        if not stats_result.data or len(stats_result.data) < min_trades:
            return {
                "message": f"Not enough data for tuning (need {min_trades}, have {len(stats_result.data or [])})",
                "trades_analyzed": len(stats_result.data or []),
                "min_required": min_trades
            }

        # Calculate stats
        exits = stats_result.data
        total = len(exits)
        premature = sum(1 for e in exits if e.get('should_have_held', False))
        optimal = sum(1 for e in exits if e.get('exit_was_optimal', False))
        avg_missed = sum(e.get('missed_profit_percent', 0) or 0 for e in exits) / total

        premature_rate = (premature / total) * 100

        # Current multipliers
        current_normal = float(tuning.get('trail_multiplier', 1.0))
        current_bullrun = float(tuning.get('bullrun_trail_multiplier', 1.0))

        # Determine adjustment
        adjustment_reason = None
        new_normal = current_normal
        new_bullrun = current_bullrun

        if premature_rate > 80:
            new_normal = min(current_normal * 1.3, 2.0)  # Max 2x
            new_bullrun = min(current_bullrun * 1.3, 2.0)
            adjustment_reason = f"URGENT: {premature_rate:.1f}% premature exits - widening trails significantly"
        elif premature_rate > 60:
            new_normal = min(current_normal * 1.2, 2.0)
            new_bullrun = min(current_bullrun * 1.2, 2.0)
            adjustment_reason = f"HIGH: {premature_rate:.1f}% premature exits - widening trails"
        elif premature_rate > 40:
            new_normal = min(current_normal * 1.1, 2.0)
            new_bullrun = min(current_bullrun * 1.1, 2.0)
            adjustment_reason = f"MODERATE: {premature_rate:.1f}% premature exits - slight adjustment"
        elif premature_rate < 20 and current_normal > 1.0:
            # Could tighten if we're rarely exiting too early
            new_normal = max(current_normal * 0.95, 0.8)  # Min 0.8x
            new_bullrun = max(current_bullrun * 0.95, 0.8)
            adjustment_reason = f"LOW: {premature_rate:.1f}% premature exits - tightening slightly"

        # Update if changed
        if adjustment_reason:
            supabase.table("bot_tuning").update({
                "previous_trail_multiplier": current_normal,
                "trail_multiplier": round(new_normal, 2),
                "bullrun_trail_multiplier": round(new_bullrun, 2),
                "last_adjusted_at": datetime.utcnow().isoformat(),
                "adjustment_reason": adjustment_reason,
                "updated_at": datetime.utcnow().isoformat()
            }).eq("id", tuning['id']).execute()

            logger.info(f"[AUTO-TUNE] {adjustment_reason}")
            logger.info(f"[AUTO-TUNE] Multipliers: normal {current_normal} -> {new_normal}, bullrun {current_bullrun} -> {new_bullrun}")

        return {
            "trades_analyzed": total,
            "premature_exits": premature,
            "optimal_exits": optimal,
            "premature_exit_rate": round(premature_rate, 1),
            "avg_missed_profit": round(avg_missed, 2),
            "previous_multiplier": current_normal,
            "new_multiplier": round(new_normal, 2) if adjustment_reason else current_normal,
            "adjustment_made": adjustment_reason is not None,
            "adjustment_reason": adjustment_reason or "No adjustment needed"
        }

    except Exception as e:
        logger.error(f"Auto-tune failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/learning/stats")
async def get_learning_stats():
    """
    Get current learning statistics and system performance.
    """
    if not supabase:
        raise HTTPException(status_code=503, detail="Supabase not available")

    try:
        # Get tuning settings
        tuning_result = supabase.table("bot_tuning").select("*").limit(1).execute()
        tuning = tuning_result.data[0] if tuning_result.data else {}

        # Get exit analysis stats
        stats_result = supabase.table("bot_exit_analysis") \
            .select("*") \
            .eq("analysis_complete", True) \
            .execute()

        exits = stats_result.data or []
        total = len(exits)

        if total == 0:
            return {
                "message": "No exit data yet",
                "total_exits_analyzed": 0,
                "current_multipliers": {
                    "normal": tuning.get('trail_multiplier', 1.0),
                    "bullrun": tuning.get('bullrun_trail_multiplier', 1.0)
                }
            }

        premature = sum(1 for e in exits if e.get('should_have_held', False))
        optimal = sum(1 for e in exits if e.get('exit_was_optimal', False))
        avg_missed = sum(e.get('missed_profit_percent', 0) or 0 for e in exits) / total

        # Stats by exit reason
        by_reason = {}
        for e in exits:
            reason = e.get('exit_reason', 'UNKNOWN')
            if reason not in by_reason:
                by_reason[reason] = {"count": 0, "premature": 0, "avg_missed": 0}
            by_reason[reason]["count"] += 1
            if e.get('should_have_held'):
                by_reason[reason]["premature"] += 1
            by_reason[reason]["avg_missed"] += e.get('missed_profit_percent', 0) or 0

        for reason in by_reason:
            count = by_reason[reason]["count"]
            by_reason[reason]["avg_missed"] = round(by_reason[reason]["avg_missed"] / count, 2)
            by_reason[reason]["premature_rate"] = round((by_reason[reason]["premature"] / count) * 100, 1)

        return {
            "total_exits_analyzed": total,
            "optimal_exits": optimal,
            "optimal_rate": round((optimal / total) * 100, 1),
            "premature_exits": premature,
            "premature_rate": round((premature / total) * 100, 1),
            "avg_missed_profit": round(avg_missed, 2),
            "by_exit_reason": by_reason,
            "current_multipliers": {
                "normal": tuning.get('trail_multiplier', 1.0),
                "bullrun": tuning.get('bullrun_trail_multiplier', 1.0)
            },
            "last_tuning": {
                "adjusted_at": tuning.get('last_adjusted_at'),
                "reason": tuning.get('adjustment_reason')
            }
        }

    except Exception as e:
        logger.error(f"Failed to get learning stats: {e}")
        raise HTTPException(status_code=500, detail=str(e))
