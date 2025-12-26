"""
CoinTracker Pro - API Routes
"""
from fastapi import APIRouter, HTTPException, Depends, Query
from typing import List, Optional
from datetime import datetime

from app.models.schemas import (
    Ticker, OHLCV, TechnicalIndicators, FearGreedIndex,
    SentimentData, TradingSignal, PortfolioSummary,
    OrderRequest, OrderResponse
)
from app.services.exchange import exchange_service
from app.services.indicators import indicator_service
from app.services.fear_greed import fear_greed_service, sentiment_service
from app.config import get_settings

router = APIRouter()
settings = get_settings()


# === Health Check ===

@router.get("/health")
async def health_check():
    """API health check."""
    return {
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat(),
        "version": settings.api_version
    }


# === Market Data ===

@router.get("/market/ticker/{symbol}", response_model=Ticker)
async def get_ticker(symbol: str):
    """Get current ticker for a trading pair."""
    try:
        # Convert symbol format if needed (BTC_USDT -> BTC/USDT)
        symbol = symbol.replace("_", "/")
        return await exchange_service.get_ticker(symbol)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/market/tickers", response_model=dict)
async def get_multiple_tickers(
    symbols: str = Query(..., description="Comma-separated symbols: BTC/USDT,ETH/USDT")
):
    """Get tickers for multiple trading pairs."""
    try:
        symbol_list = [s.strip().replace("_", "/") for s in symbols.split(",")]
        return await exchange_service.get_multiple_tickers(symbol_list)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/market/ohlcv/{symbol}", response_model=List[OHLCV])
async def get_ohlcv(
    symbol: str,
    timeframe: str = Query("1h", description="Timeframe: 1m, 5m, 15m, 1h, 4h, 1d"),
    limit: int = Query(100, ge=1, le=1000)
):
    """Get OHLCV candlestick data."""
    try:
        symbol = symbol.replace("_", "/")
        return await exchange_service.get_ohlcv(symbol, timeframe, limit)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


# === Technical Analysis ===

@router.get("/analysis/indicators/{symbol}", response_model=TechnicalIndicators)
async def get_technical_indicators(
    symbol: str,
    timeframe: str = Query("1h", description="Timeframe for analysis")
):
    """Get all technical indicators for a symbol."""
    try:
        symbol = symbol.replace("_", "/")

        # Fetch enough data for indicators (need 200+ candles for EMA200)
        df = await exchange_service.get_ohlcv_dataframe(symbol, timeframe, limit=250)

        if df.empty:
            raise HTTPException(status_code=404, detail="No data available")

        indicators = indicator_service.calculate_all(df)
        return indicators

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/analysis/summary/{symbol}")
async def get_analysis_summary(
    symbol: str,
    timeframe: str = Query("1h")
):
    """Get a summary of technical analysis with buy/sell signals."""
    try:
        symbol = symbol.replace("_", "/")

        df = await exchange_service.get_ohlcv_dataframe(symbol, timeframe, limit=250)
        indicators = indicator_service.calculate_all(df)
        ticker = await exchange_service.get_ticker(symbol)

        # Generate simple signals
        signals = []

        # RSI signals
        if indicators.rsi_14 < 30:
            signals.append({"type": "RSI", "signal": "OVERSOLD", "strength": "strong"})
        elif indicators.rsi_14 > 70:
            signals.append({"type": "RSI", "signal": "OVERBOUGHT", "strength": "strong"})

        # MACD signals
        if indicators.macd_cross == 1:
            signals.append({"type": "MACD", "signal": "BULLISH_CROSS", "strength": "medium"})
        elif indicators.macd_cross == -1:
            signals.append({"type": "MACD", "signal": "BEARISH_CROSS", "strength": "medium"})

        # Bollinger Band signals
        if indicators.bb_position < 0.1:
            signals.append({"type": "BB", "signal": "NEAR_LOWER", "strength": "medium"})
        elif indicators.bb_position > 0.9:
            signals.append({"type": "BB", "signal": "NEAR_UPPER", "strength": "medium"})

        # EMA alignment
        if indicators.ema_alignment == 1:
            signals.append({"type": "EMA", "signal": "GOLDEN_CROSS", "strength": "strong"})
        else:
            signals.append({"type": "EMA", "signal": "DEATH_CROSS", "strength": "strong"})

        # Overall sentiment
        bullish_count = sum(1 for s in signals if "OVERSOLD" in s["signal"] or "BULLISH" in s["signal"] or "LOWER" in s["signal"] or "GOLDEN" in s["signal"])
        bearish_count = len(signals) - bullish_count

        overall = "BULLISH" if bullish_count > bearish_count else "BEARISH" if bearish_count > bullish_count else "NEUTRAL"

        return {
            "symbol": symbol,
            "price": ticker.price,
            "change_24h_pct": ticker.change_24h_pct,
            "indicators": indicators,
            "signals": signals,
            "overall_sentiment": overall,
            "timestamp": datetime.utcnow().isoformat()
        }

    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


# === Sentiment ===

@router.get("/sentiment/fear-greed", response_model=FearGreedIndex)
async def get_fear_greed():
    """Get current Fear & Greed Index."""
    return await fear_greed_service.get_with_changes()


@router.get("/sentiment/fear-greed/history", response_model=List[FearGreedIndex])
async def get_fear_greed_history(
    days: int = Query(30, ge=1, le=365)
):
    """Get historical Fear & Greed Index values."""
    return await fear_greed_service.get_historical(days)


@router.get("/sentiment/combined", response_model=SentimentData)
async def get_combined_sentiment():
    """Get combined sentiment from all sources."""
    return await sentiment_service.get_combined_sentiment()


# === Portfolio ===

@router.get("/portfolio/summary", response_model=PortfolioSummary)
async def get_portfolio():
    """Get portfolio summary."""
    try:
        return await exchange_service.get_portfolio_summary()
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/portfolio/balance")
async def get_balance():
    """Get account balances."""
    try:
        return await exchange_service.get_balance()
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


# === Trading ===

@router.post("/trade/order", response_model=OrderResponse)
async def place_order(order: OrderRequest):
    """Place a trading order."""
    try:
        return await exchange_service.place_order(order)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.delete("/trade/order/{order_id}")
async def cancel_order(order_id: str, symbol: str):
    """Cancel an open order."""
    try:
        symbol = symbol.replace("_", "/")
        success = await exchange_service.cancel_order(order_id, symbol)
        if success:
            return {"status": "cancelled", "order_id": order_id}
        else:
            raise HTTPException(status_code=400, detail="Failed to cancel order")
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/trade/orders/open")
async def get_open_orders(symbol: Optional[str] = None):
    """Get all open orders."""
    try:
        if symbol:
            symbol = symbol.replace("_", "/")
        return await exchange_service.get_open_orders(symbol)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


# === ML Signals ===

@router.get("/signals/{symbol}", response_model=TradingSignal)
async def get_trading_signal(symbol: str):
    """
    Get ML-generated trading signal with full explanation.

    The signal includes:
    - Score (0-100): 0=strong sell, 50=neutral, 100=strong buy
    - Direction: BUY, SELL, or HOLD
    - Confidence: 0-1
    - Top reasons explaining WHY the signal was generated
    - Similar historical patterns and their outcomes
    """
    from app.ml.signal_generator import signal_generator

    try:
        symbol = symbol.replace("_", "/")
        signal = await signal_generator.generate_signal(symbol)
        return signal
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@router.get("/signals")
async def get_all_signals():
    """Get trading signals for all supported pairs."""
    from app.ml.signal_generator import signal_generator
    import asyncio

    signals = {}
    for symbol in settings.supported_pairs:
        try:
            signal = await signal_generator.generate_signal(symbol)
            signals[symbol] = signal
        except Exception as e:
            signals[symbol] = {"error": str(e)}
        await asyncio.sleep(0.5)  # Rate limiting

    return signals


# === Dashboard ===

@router.get("/dashboard")
async def get_dashboard():
    """Get all dashboard data in one call."""
    try:
        # Fetch all data in parallel
        import asyncio

        symbols = settings.supported_pairs

        tickers_task = exchange_service.get_multiple_tickers(symbols)
        fear_greed_task = fear_greed_service.get_with_changes()
        portfolio_task = exchange_service.get_portfolio_summary()

        tickers, fear_greed, portfolio = await asyncio.gather(
            tickers_task,
            fear_greed_task,
            portfolio_task,
            return_exceptions=True
        )

        # Handle potential errors
        if isinstance(tickers, Exception):
            tickers = {}
        if isinstance(fear_greed, Exception):
            fear_greed = None
        if isinstance(portfolio, Exception):
            portfolio = None

        return {
            "tickers": tickers,
            "fear_greed": fear_greed,
            "portfolio": portfolio,
            "timestamp": datetime.utcnow().isoformat()
        }

    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))
