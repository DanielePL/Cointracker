"""
CoinTracker Pro - FastAPI Application
Vollautomatischer Crypto Trading Bot mit ML-basierter Sentiment-Analyse
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager
from loguru import logger
import sys

from app.config import get_settings
from app.api.routes import router
from app.api.routes_v2 import router as router_v2, start_binance_stream
from app.api.analysis import router as analysis_router
from app.services.exchange import exchange_service


# Configure logging
logger.remove()
logger.add(
    sys.stdout,
    format="<green>{time:YYYY-MM-DD HH:mm:ss}</green> | <level>{level: <8}</level> | <cyan>{name}</cyan>:<cyan>{function}</cyan> - <level>{message}</level>",
    level="INFO"
)
logger.add(
    "logs/cointracker.log",
    rotation="10 MB",
    retention="7 days",
    level="DEBUG"
)


settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifecycle management."""
    # Startup
    logger.info("=" * 50)
    logger.info("CoinTracker Pro Starting...")
    logger.info("=" * 50)

    # Initialize exchange connection
    try:
        await exchange_service.initialize()
        logger.info("Exchange service initialized")
    except Exception as e:
        logger.warning(f"Exchange initialization failed (will retry on first request): {e}")

    logger.info(f"API Version: {settings.api_version}")
    logger.info(f"Debug Mode: {settings.debug}")
    logger.info(f"Testnet Mode: {settings.binance_testnet}")

    # Start Binance WebSocket streams for live prices
    try:
        await start_binance_stream()
        logger.info("Binance WebSocket streams started")
    except Exception as e:
        logger.warning(f"Binance WebSocket start failed (will retry): {e}")

    logger.info("=" * 50)
    logger.info("Server ready!")
    logger.info("=" * 50)

    yield

    # Shutdown
    logger.info("CoinTracker Pro Shutting down...")


# Create FastAPI app
app = FastAPI(
    title="CoinTracker Pro",
    description="""
    ## Vollautomatischer Crypto Trading Bot

    Ein intelligenter Trading Bot der nicht nur technische Indikatoren nutzt,
    sondern **versteht WARUM** Kurse sich bewegen - durch ML-basierte
    Sentiment- und Emotions-Analyse.

    ### Features:
    - **Technische Analyse**: RSI, MACD, Bollinger Bands, EMA, und mehr
    - **Sentiment Analysis**: Fear & Greed Index, News, Social Media
    - **ML Signals**: LSTM + XGBoost für intelligente Signale
    - **Vollautomatisches Trading**: Mit Risk Management
    - **Erklärbare KI**: Jedes Signal mit "Warum" Erklärung

    ### API Endpoints:
    - `/market/*` - Marktdaten und OHLCV
    - `/analysis/*` - Technische Analyse
    - `/sentiment/*` - Sentiment Daten
    - `/portfolio/*` - Portfolio Management
    - `/trade/*` - Order Execution
    """,
    version=settings.api_version,
    lifespan=lifespan
)


# CORS middleware for Android app
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, restrict to your app
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Include API routes
app.include_router(router, prefix="/api/v1", tags=["API"])
app.include_router(router_v2, prefix="/api/v2", tags=["API V2"])
app.include_router(analysis_router, prefix="/api/v3/analysis", tags=["Analysis"])


# Root endpoint
@app.get("/")
async def root():
    """Root endpoint with API info."""
    return {
        "app": "CoinTracker Pro",
        "version": settings.api_version,
        "docs": "/docs",
        "redoc": "/redoc",
        "api_prefix": "/api/v1"
    }


# Health check endpoint for Railway
@app.get("/health")
async def health():
    """Health check endpoint."""
    return {"status": "healthy", "version": settings.api_version}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.debug
    )
