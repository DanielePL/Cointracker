"""
CoinTracker Pro - Pydantic Schemas
"""
from pydantic import BaseModel, Field
from typing import Optional, List
from datetime import datetime
from enum import Enum


# === Enums ===

class SignalDirection(str, Enum):
    BUY = "BUY"
    SELL = "SELL"
    HOLD = "HOLD"


class OrderType(str, Enum):
    MARKET = "MARKET"
    LIMIT = "LIMIT"
    STOP_LOSS = "STOP_LOSS"
    TAKE_PROFIT = "TAKE_PROFIT"


class OrderSide(str, Enum):
    BUY = "BUY"
    SELL = "SELL"


# === Market Data ===

class OHLCV(BaseModel):
    """Candlestick data."""
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float


class Ticker(BaseModel):
    """Current price ticker."""
    symbol: str
    price: float
    change_24h: float
    change_24h_pct: float
    volume_24h: float
    high_24h: float
    low_24h: float
    timestamp: datetime


# === Technical Indicators ===

class TechnicalIndicators(BaseModel):
    """Technical analysis indicators."""
    # Momentum
    rsi_14: float = Field(..., ge=0, le=100)
    rsi_divergence: int = Field(..., ge=-1, le=1)  # -1=bearish, 0=none, 1=bullish
    macd_line: float
    macd_signal: float
    macd_histogram: float
    macd_cross: int = Field(..., ge=-1, le=1)

    # Trend
    ema_50: float
    ema_200: float
    price_vs_ema50_pct: float
    price_vs_ema200_pct: float
    ema_alignment: int  # 1=bullish, -1=bearish

    # Volatility
    bb_upper: float
    bb_middle: float
    bb_lower: float
    bb_position: float = Field(..., ge=0, le=1)  # 0=lower, 0.5=middle, 1=upper
    bb_width: float
    atr_14: float

    # Volume
    volume_ratio: float  # vs 20-day avg
    volume_trend: float
    obv: float


# === Sentiment ===

class FearGreedIndex(BaseModel):
    """Fear & Greed Index data."""
    value: int = Field(..., ge=0, le=100)
    value_classification: str  # "Extreme Fear", "Fear", "Neutral", "Greed", "Extreme Greed"
    timestamp: datetime
    change_24h: Optional[int] = None
    change_7d: Optional[int] = None


class SentimentData(BaseModel):
    """Combined sentiment data."""
    fear_greed: FearGreedIndex
    news_sentiment: Optional[float] = None  # -1 to 1
    social_sentiment: Optional[float] = None  # -1 to 1
    social_volume_spike: Optional[float] = None


# === ML Signal ===

class SignalReason(BaseModel):
    """Individual reason contributing to signal."""
    factor: str
    value: float
    contribution: float  # Points added/subtracted
    description: str


class HistoricalPattern(BaseModel):
    """Similar historical pattern match."""
    date: datetime
    similarity_score: float
    pattern_description: str
    outcome_1d: float
    outcome_7d: float
    outcome_30d: float


class TradingSignal(BaseModel):
    """ML-generated trading signal with explanation."""
    symbol: str
    score: int = Field(..., ge=0, le=100)
    direction: SignalDirection
    confidence: float = Field(..., ge=0, le=1)

    # Explanation (WARUM)
    top_reasons: List[SignalReason]
    bullish_factors: List[SignalReason]
    bearish_factors: List[SignalReason]
    similar_patterns: List[HistoricalPattern]

    # Context
    current_price: float
    suggested_entry: Optional[float] = None
    suggested_stop_loss: Optional[float] = None
    suggested_take_profit: Optional[float] = None

    timestamp: datetime


# === Trading ===

class OrderRequest(BaseModel):
    """Request to place an order."""
    symbol: str
    side: OrderSide
    order_type: OrderType
    amount: float
    price: Optional[float] = None  # For limit orders
    stop_loss_pct: Optional[float] = None
    take_profit_pct: Optional[float] = None


class OrderResponse(BaseModel):
    """Response after placing an order."""
    order_id: str
    symbol: str
    side: OrderSide
    order_type: OrderType
    amount: float
    price: float
    status: str
    timestamp: datetime


class Position(BaseModel):
    """Current position in a trading pair."""
    symbol: str
    side: OrderSide
    amount: float
    entry_price: float
    current_price: float
    unrealized_pnl: float
    unrealized_pnl_pct: float
    stop_loss: Optional[float] = None
    take_profit: Optional[float] = None


class PortfolioSummary(BaseModel):
    """Portfolio overview."""
    total_value_usdt: float
    available_usdt: float
    positions: List[Position]
    total_pnl: float
    total_pnl_pct: float


# === Feature Vector (for ML) ===

class FeatureVector(BaseModel):
    """Complete feature vector for ML model."""
    timestamp: datetime
    symbol: str

    # Technical
    rsi_14: float
    rsi_divergence: int
    macd_histogram: float
    macd_cross: int
    price_vs_ema50: float
    price_vs_ema200: float
    ema_alignment: int
    bb_position: float
    bb_width: float
    atr_normalized: float
    volume_ratio: float
    volume_trend: float
    obv_divergence: int

    # Sentiment
    fear_greed_index: int
    fear_greed_change_24h: int
    fear_greed_change_7d: int
    fear_greed_extreme: int
    news_sentiment_score: Optional[float] = None
    social_sentiment: Optional[float] = None
    social_volume_spike: Optional[float] = None

    # On-Chain (optional for now)
    exchange_netflow: Optional[float] = None
    whale_transactions: Optional[int] = None
    whale_accumulation: Optional[float] = None

    # Context
    btc_dominance: Optional[float] = None
    days_since_ath: Optional[int] = None
    drawdown_from_ath: Optional[float] = None
    hour_of_day: int
    day_of_week: int
    is_weekend: bool


# === Auth ===

class UserCreate(BaseModel):
    """User registration."""
    username: str
    password: str


class UserLogin(BaseModel):
    """User login."""
    username: str
    password: str


class Token(BaseModel):
    """JWT token response."""
    access_token: str
    token_type: str = "bearer"


class User(BaseModel):
    """User model."""
    id: int
    username: str
    is_active: bool = True
