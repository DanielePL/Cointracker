"""
CoinTracker Pro - Extended API Routes V2
Adds: WebSocket, On-Chain Data, Backtesting, Auto-Trading, JWT Auth
"""

from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect, Depends, Query
from typing import List, Optional
from datetime import datetime
from loguru import logger
import asyncio

from app.api.auth import (
    Token, UserCreate, UserLogin, User,
    create_user, authenticate_user, create_access_token,
    get_current_user, get_current_active_user
)
from app.services.websocket_manager import manager, binance_ws
from app.services.sentiment_aggregator import sentiment_aggregator
from app.services.onchain_data import onchain_data
from app.services.trading_engine import trading_engine
from app.services.backtester import backtester, BacktestConfig
from app.ml.hybrid_model import hybrid_model
from app.ml.feature_engineer import feature_engineer
from app.services.exchange import exchange_service
from app.services.fear_greed import fear_greed_service

router = APIRouter()


# ==================== AUTHENTICATION ====================

@router.post("/auth/register", response_model=User, tags=["Authentication"])
async def register(user: UserCreate):
    """Register a new user"""
    return create_user(user)


@router.post("/auth/login", response_model=Token, tags=["Authentication"])
async def login(user: UserLogin):
    """Login and get access token"""
    authenticated_user = authenticate_user(user.username, user.password)

    if not authenticated_user:
        raise HTTPException(
            status_code=401,
            detail="Incorrect username or password"
        )

    token = create_access_token(
        data={"sub": authenticated_user.username, "user_id": authenticated_user.id}
    )
    return token


@router.get("/auth/me", response_model=User, tags=["Authentication"])
async def get_me(current_user: User = Depends(get_current_active_user)):
    """Get current user info"""
    return current_user


# ==================== AGGREGATED SENTIMENT ====================

@router.get("/sentiment/aggregated/{symbol}", tags=["Sentiment"])
async def get_aggregated_sentiment(symbol: str = "BTC"):
    """Get sentiment from multiple sources (Fear&Greed, News, Social)"""
    base_symbol = symbol.split("/")[0] if "/" in symbol else symbol
    sentiment = await sentiment_aggregator.get_aggregated_sentiment(base_symbol)

    return {
        "symbol": base_symbol,
        "overall_score": sentiment.overall_score,
        "overall_label": sentiment.overall_label,
        "confidence": sentiment.confidence,
        "bullish_factors": sentiment.bullish_factors,
        "bearish_factors": sentiment.bearish_factors,
        "sources": [
            {
                "source": s.source,
                "value": s.value,
                "confidence": s.confidence,
                "raw_value": s.raw_value
            }
            for s in sentiment.sources
        ],
        "timestamp": sentiment.timestamp.isoformat()
    }


# ==================== ON-CHAIN DATA ====================

@router.get("/onchain/{symbol}", tags=["On-Chain"])
async def get_onchain_metrics(symbol: str = "BTC"):
    """Get on-chain metrics: exchange flows, whale activity, holder distribution"""
    base_symbol = symbol.split("/")[0] if "/" in symbol else symbol
    metrics = await onchain_data.get_onchain_analysis(base_symbol)

    return {
        "symbol": base_symbol,
        "exchange_netflow": metrics.total_exchange_netflow,
        "exchange_reserve": metrics.exchange_reserve,
        "exchange_reserve_change_24h": metrics.exchange_reserve_change_24h,
        "whale_transactions_24h": metrics.whale_transactions_24h,
        "whale_volume_24h": metrics.whale_volume_24h,
        "whale_accumulation": metrics.whale_accumulation,
        "active_addresses_24h": metrics.active_addresses_24h,
        "signal": metrics.signal,
        "reasons": metrics.reasons,
        "timestamp": metrics.timestamp.isoformat()
    }


@router.get("/whales/{symbol}", tags=["On-Chain"])
async def get_whale_transactions(
    symbol: str = "BTC",
    min_value_usd: int = 10_000_000
):
    """Get recent whale transactions (>$10M by default)"""
    base_symbol = symbol.split("/")[0] if "/" in symbol else symbol
    whales = await onchain_data.get_whale_alerts(base_symbol, min_value_usd)

    return {
        "symbol": base_symbol,
        "min_value_usd": min_value_usd,
        "transactions": [
            {
                "tx_hash": w.tx_hash,
                "amount": w.amount,
                "amount_usd": w.amount_usd,
                "from_label": w.from_label,
                "to_label": w.to_label,
                "is_exchange_inflow": w.is_exchange_inflow,
                "is_exchange_outflow": w.is_exchange_outflow,
                "timestamp": w.timestamp.isoformat()
            }
            for w in whales
        ]
    }


# ==================== ML SIGNALS V2 ====================

@router.get("/signals-v2/{symbol}", tags=["ML Signals"])
async def get_hybrid_signal(symbol: str):
    """
    Get ML signal from hybrid LSTM + XGBoost model.
    Includes feature importance and temporal pattern analysis.
    """
    import pandas as pd

    symbol = symbol.replace("_", "/")

    # Get OHLCV data
    ohlcv = await exchange_service.get_ohlcv(symbol, "1h", 200)
    if not ohlcv or len(ohlcv) < 200:
        raise HTTPException(status_code=400, detail="Insufficient historical data")

    # Convert to DataFrame
    df = pd.DataFrame(ohlcv, columns=['timestamp', 'open', 'high', 'low', 'close', 'volume'])

    # Get fear & greed
    fg = await fear_greed_service.get_current()

    # Generate features
    features = await feature_engineer.create_features(df, fg, symbol=symbol)

    # Get prediction from hybrid model
    prediction = hybrid_model.predict(features)

    return {
        "symbol": symbol,
        "signal": prediction.signal,
        "score": prediction.score,
        "confidence": prediction.confidence,
        "direction_probs": prediction.direction_probs,
        "reasons": prediction.top_reasons,
        "feature_importance": dict(sorted(
            prediction.feature_importance.items(),
            key=lambda x: abs(x[1]),
            reverse=True
        )[:10]),  # Top 10 features
        "timestamp": prediction.timestamp.isoformat()
    }


# ==================== AUTO-TRADING ====================

@router.get("/trading/status", tags=["Trading"])
async def get_trading_status(current_user: User = Depends(get_current_active_user)):
    """Get auto-trading engine status"""
    return trading_engine.get_status()


@router.post("/trading/enable", tags=["Trading"])
async def enable_auto_trading(
    enabled: bool = True,
    testnet_only: bool = True,
    current_user: User = Depends(get_current_active_user)
):
    """Enable or disable auto-trading"""
    trading_engine.config.enabled = enabled
    trading_engine.config.testnet_only = testnet_only

    logger.info(f"Trading {'enabled' if enabled else 'disabled'} by {current_user.username}")

    return {
        "status": "ok",
        "enabled": enabled,
        "testnet_only": testnet_only
    }


@router.post("/trading/config", tags=["Trading"])
async def update_trading_config(
    max_position_size_pct: Optional[float] = None,
    stop_loss_pct: Optional[float] = None,
    take_profit_pct: Optional[float] = None,
    min_signal_score: Optional[int] = None,
    current_user: User = Depends(get_current_active_user)
):
    """Update trading configuration"""
    if max_position_size_pct is not None:
        trading_engine.config.max_position_size_pct = max_position_size_pct
    if stop_loss_pct is not None:
        trading_engine.config.stop_loss_pct = stop_loss_pct
    if take_profit_pct is not None:
        trading_engine.config.take_profit_pct = take_profit_pct
    if min_signal_score is not None:
        trading_engine.config.min_signal_score = min_signal_score

    return {
        "status": "ok",
        "config": {
            "max_position_size_pct": trading_engine.config.max_position_size_pct,
            "stop_loss_pct": trading_engine.config.stop_loss_pct,
            "take_profit_pct": trading_engine.config.take_profit_pct,
            "min_signal_score": trading_engine.config.min_signal_score,
            "trailing_stop_pct": trading_engine.config.trailing_stop_pct
        }
    }


# ==================== BACKTESTING ====================

@router.post("/backtest", tags=["Backtesting"])
async def run_backtest(
    symbol: str = "BTC/USDT",
    timeframe: str = "1h",
    days: int = Query(90, ge=7, le=365),
    position_size_pct: float = Query(10.0, ge=1, le=50),
    stop_loss_pct: float = Query(3.0, ge=0.5, le=10),
    take_profit_pct: float = Query(6.0, ge=1, le=50),
    current_user: User = Depends(get_current_active_user)
):
    """
    Run backtest on historical data.
    Returns performance metrics and trade history.
    """
    try:
        symbol = symbol.replace("_", "/")

        backtester.config = BacktestConfig(
            position_size_pct=position_size_pct,
            stop_loss_pct=stop_loss_pct,
            take_profit_pct=take_profit_pct
        )

        result = await backtester.run_backtest(symbol, timeframe, days=days)

        return {
            "symbol": symbol,
            "timeframe": timeframe,
            "period_days": days,
            "performance": {
                "total_return_pct": round(result.total_return_pct, 2),
                "buy_hold_return_pct": round(result.buy_and_hold_return_pct, 2),
                "alpha": round(result.alpha, 2),
                "annualized_return_pct": round(result.annualized_return_pct, 2),
            },
            "risk_metrics": {
                "sharpe_ratio": round(result.sharpe_ratio, 2),
                "sortino_ratio": round(result.sortino_ratio, 2),
                "max_drawdown_pct": round(result.max_drawdown_pct, 2),
                "volatility_pct": round(result.volatility_pct, 2),
            },
            "trade_stats": {
                "total_trades": result.total_trades,
                "winning_trades": result.winning_trades,
                "losing_trades": result.losing_trades,
                "win_rate": round(result.win_rate, 1),
                "profit_factor": round(result.profit_factor, 2),
                "avg_win_pct": round(result.avg_win_pct, 2),
                "avg_loss_pct": round(result.avg_loss_pct, 2),
            },
            "recent_trades": [
                {
                    "entry_time": t.entry_time.isoformat(),
                    "exit_time": t.exit_time.isoformat(),
                    "side": t.side,
                    "entry_price": t.entry_price,
                    "exit_price": t.exit_price,
                    "pnl_pct": round(t.pnl_pct, 2),
                    "exit_reason": t.exit_reason
                }
                for t in result.trades[-10:]  # Last 10 trades
            ]
        }

    except Exception as e:
        logger.error(f"Backtest failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ==================== WEBSOCKET ====================

@router.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """
    WebSocket endpoint for real-time data streaming.

    Messages:
    - {"action": "subscribe", "symbol": "BTC/USDT"}
    - {"action": "unsubscribe", "symbol": "BTC/USDT"}
    - {"action": "ping"}

    Receives:
    - {"type": "ticker", "symbol": "BTC/USDT", "price": 50000, ...}
    - {"type": "kline", "symbol": "BTC/USDT", "open": 50000, ...}
    """
    await manager.connect(websocket)

    try:
        # Send welcome message
        await websocket.send_json({
            "type": "connected",
            "message": "Connected to CoinTracker Pro WebSocket",
            "timestamp": datetime.utcnow().isoformat()
        })

        while True:
            data = await websocket.receive_json()
            action = data.get("action")

            if action == "subscribe":
                symbol = data.get("symbol", "BTC/USDT")
                manager.subscribe(websocket, symbol)
                await websocket.send_json({
                    "type": "subscribed",
                    "symbol": symbol,
                    "timestamp": datetime.utcnow().isoformat()
                })
                logger.info(f"Client subscribed to {symbol}")

            elif action == "unsubscribe":
                symbol = data.get("symbol", "BTC/USDT")
                manager.unsubscribe(websocket, symbol)
                await websocket.send_json({
                    "type": "unsubscribed",
                    "symbol": symbol
                })

            elif action == "ping":
                await websocket.send_json({
                    "type": "pong",
                    "timestamp": datetime.utcnow().isoformat()
                })

    except WebSocketDisconnect:
        manager.disconnect(websocket)
        logger.info("WebSocket client disconnected")
    except Exception as e:
        logger.error(f"WebSocket error: {e}")
        manager.disconnect(websocket)


# ==================== STARTUP ====================

async def start_binance_stream():
    """Start Binance WebSocket streams (called from main.py lifespan)"""
    symbols = ["BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT", "ADA/USDT"]
    asyncio.create_task(binance_ws.start_ticker_stream(symbols))
    logger.info(f"Started Binance price streaming for {len(symbols)} symbols")
