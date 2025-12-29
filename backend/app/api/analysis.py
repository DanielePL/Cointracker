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
    from ta.trend import MACD, EMAIndicator, SMAIndicator
    from ta.volatility import BollingerBands, AverageTrueRange
    TA_AVAILABLE = True
except ImportError:
    TA_AVAILABLE = False
    logger.warning("TA library not available")


router = APIRouter()


# Top 100 coins by market cap (Binance symbols)
TOP_100_COINS = [
    "BTCUSDT", "ETHUSDT", "BNBUSDT", "XRPUSDT", "ADAUSDT",
    "DOGEUSDT", "SOLUSDT", "TRXUSDT", "DOTUSDT", "MATICUSDT",
    "LTCUSDT", "SHIBUSDT", "AVAXUSDT", "LINKUSDT", "ATOMUSDT",
    "UNIUSDT", "ETCUSDT", "XLMUSDT", "XMRUSDT", "BCHUSDT",
    "APTUSDT", "FILUSDT", "LDOUSDT", "ARBUSDT", "NEARUSDT",
    "VETUSDT", "ICPUSDT", "QNTUSDT", "AAVEUSDT", "GRTUSDT",
    "ALGOUSDT", "STXUSDT", "EGLDUSDT", "SANDUSDT", "MANAUSDT",
    "EOSUSDT", "THETAUSDT", "AXSUSDT", "IMXUSDT", "INJUSDT",
    "FTMUSDT", "RENUSDT", "KAVAUSDT", "FLOWUSDT", "CHZUSDT",
    "APEUSDT", "GMXUSDT", "CFXUSDT", "MINAUSDT", "RNDRUSDT",
    "SNXUSDT", "CRVUSDT", "LRCUSDT", "ENJUSDT", "BATUSDT",
    "MKRUSDT", "COMPUSDT", "ZECUSDT", "DASHUSDT", "YFIUSDT",
    "1INCHUSDT", "ANKRUSDT", "CELOUSDT", "IOTAUSDT", "ZILUSDT",
    "RUNEUSDT", "SKLUSDT", "HBARUSDT", "KLAYUSDT", "GALAUSDT",
    "ENSUSDT", "WOOUSDT", "PEOPLEUSDT", "OGNUSDT", "AUDIOUSDT",
    "SUSHIUSDT", "COTIUSDT", "RLCUSDT", "CELRUSDT", "STMXUSDT",
    "DYDXUSDT", "OPUSDT", "SUIUSDT", "SEIUSDT", "TIAUSDT",
    "BLURUSDT", "WLDUSDT", "PENDLEUSDT", "JUPUSDT", "STRKUSDT",
    "PIXELUSDT", "PORTALUSDT", "AEVOUSDT", "WUSDT", "ENAUSDT",
    "TAOUSDT", "NOTUSDT", "IOUSDT", "ZKUSDT", "LISTAUSDT"
]


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
    bb_upper: Optional[float] = None
    bb_lower: Optional[float] = None
    bb_middle: Optional[float] = None
    atr: Optional[float] = None

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


def calculate_technical_indicators(klines: List[Dict]) -> Dict[str, float]:
    """Calculate technical indicators from klines"""
    if not TA_AVAILABLE or len(klines) < 26:
        return {}

    df = pd.DataFrame(klines)
    close = df['close']
    high = df['high']
    low = df['low']

    indicators = {}

    try:
        # RSI
        rsi = RSIIndicator(close, window=14)
        indicators['rsi'] = round(rsi.rsi().iloc[-1], 2)

        # MACD
        macd = MACD(close)
        indicators['macd'] = round(macd.macd().iloc[-1], 4)
        indicators['macd_signal'] = round(macd.macd_signal().iloc[-1], 4)

        # EMA
        ema12 = EMAIndicator(close, window=12)
        ema26 = EMAIndicator(close, window=26)
        indicators['ema_12'] = round(ema12.ema_indicator().iloc[-1], 4)
        indicators['ema_26'] = round(ema26.ema_indicator().iloc[-1], 4)

        # Bollinger Bands
        bb = BollingerBands(close, window=20, window_dev=2)
        indicators['bb_upper'] = round(bb.bollinger_hband().iloc[-1], 4)
        indicators['bb_lower'] = round(bb.bollinger_lband().iloc[-1], 4)
        indicators['bb_middle'] = round(bb.bollinger_mavg().iloc[-1], 4)

        # ATR
        atr = AverageTrueRange(high, low, close, window=14)
        indicators['atr'] = round(atr.average_true_range().iloc[-1], 4)

    except Exception as e:
        logger.warning(f"Error calculating indicators: {e}")

    return indicators


def calculate_tech_signal(price: float, indicators: Dict[str, float]) -> tuple:
    """Calculate technical signal (rule-based)"""
    score = 50  # Neutral
    reasons = []

    # RSI
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

    # MACD
    macd = indicators.get('macd', 0)
    macd_signal = indicators.get('macd_signal', 0)
    if macd > macd_signal:
        score += 15
        reasons.append("MACD bullish crossover")
    else:
        score -= 15
        reasons.append("MACD bearish")

    # EMA trend
    ema12 = indicators.get('ema_12', price)
    ema26 = indicators.get('ema_26', price)
    if ema12 > ema26:
        score += 10
        reasons.append("EMA uptrend")
    else:
        score -= 10
        reasons.append("EMA downtrend")

    # Bollinger position
    bb_upper = indicators.get('bb_upper', price * 1.1)
    bb_lower = indicators.get('bb_lower', price * 0.9)
    if price < bb_lower:
        score += 15
        reasons.append("Below Bollinger lower band")
    elif price > bb_upper:
        score -= 15
        reasons.append("Above Bollinger upper band")

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
    """Analyze a single coin"""
    try:
        # Fetch data
        klines_task = fetch_binance_klines(symbol)
        ticker_task = fetch_ticker_24h(symbol)

        klines, ticker = await asyncio.gather(klines_task, ticker_task)

        if not klines or not ticker:
            return None

        # Current price and stats
        price = float(ticker['lastPrice'])
        volume_24h = float(ticker['quoteVolume'])
        price_change_24h = float(ticker['priceChangePercent'])

        # Technical indicators
        indicators = calculate_technical_indicators(klines)

        # Tech signal (rule-based)
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
            rsi=indicators.get('rsi'),
            macd=indicators.get('macd'),
            macd_signal=indicators.get('macd_signal'),
            ema_12=indicators.get('ema_12'),
            ema_26=indicators.get('ema_26'),
            bb_upper=indicators.get('bb_upper'),
            bb_lower=indicators.get('bb_lower'),
            bb_middle=indicators.get('bb_middle'),
            atr=indicators.get('atr'),
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
    Run full analysis on top coins

    - **coins**: Number of coins to analyze (max 100)
    - **log_to_db**: Whether to log results to Supabase
    """
    start_time = datetime.utcnow()

    # Limit coins
    coins_to_analyze = TOP_100_COINS[:min(coins, 100)]

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
    """List all supported coins"""
    return {"coins": TOP_100_COINS, "count": len(TOP_100_COINS)}


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
            # Run fresh analysis
            coins_to_analyze = TOP_100_COINS[:min(coins, 100)]
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
    coins: int = 50
):
    """
    Run full analysis AND trigger bot trading

    This is the main endpoint for scheduled execution.
    Analyzes coins, logs to Supabase, then executes trades.
    """
    start_time = datetime.utcnow()

    # Analyze coins
    coins_to_analyze = TOP_100_COINS[:min(coins, 100)]
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
