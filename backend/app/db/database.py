"""
CoinTracker Pro - Database Configuration
"""
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase
from sqlalchemy import Column, Integer, String, Float, DateTime, Boolean, Text, ForeignKey
from sqlalchemy.orm import relationship
from datetime import datetime

from app.config import get_settings

settings = get_settings()


# Create async engine
engine = create_async_engine(
    settings.database_url,
    echo=settings.debug,
    future=True
)

# Create async session factory
async_session = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False
)


class Base(DeclarativeBase):
    """Base class for all models."""
    pass


# === Models ===

class User(Base):
    """User account."""
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(50), unique=True, index=True, nullable=False)
    hashed_password = Column(String(255), nullable=False)
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    # Relationships
    trades = relationship("Trade", back_populates="user")
    signals = relationship("Signal", back_populates="user")


class Trade(Base):
    """Executed trades."""
    __tablename__ = "trades"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"))
    symbol = Column(String(20), nullable=False, index=True)
    side = Column(String(10), nullable=False)  # BUY or SELL
    order_type = Column(String(20), nullable=False)
    amount = Column(Float, nullable=False)
    price = Column(Float, nullable=False)
    total_value = Column(Float, nullable=False)
    fee = Column(Float, default=0)
    order_id = Column(String(100))
    status = Column(String(20), default="COMPLETED")
    signal_id = Column(Integer, ForeignKey("signals.id"), nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    # Relationships
    user = relationship("User", back_populates="trades")
    signal = relationship("Signal", back_populates="trades")


class Signal(Base):
    """ML-generated trading signals."""
    __tablename__ = "signals"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"))
    symbol = Column(String(20), nullable=False, index=True)
    direction = Column(String(10), nullable=False)  # BUY, SELL, HOLD
    score = Column(Integer, nullable=False)
    confidence = Column(Float, nullable=False)
    explanation = Column(Text)  # JSON string of reasons
    price_at_signal = Column(Float, nullable=False)
    was_executed = Column(Boolean, default=False)
    outcome_1d = Column(Float, nullable=True)  # % change after 1 day
    outcome_7d = Column(Float, nullable=True)  # % change after 7 days
    created_at = Column(DateTime, default=datetime.utcnow)

    # Relationships
    user = relationship("User", back_populates="signals")
    trades = relationship("Trade", back_populates="signal")


class OHLCV(Base):
    """Historical OHLCV data cache."""
    __tablename__ = "ohlcv"

    id = Column(Integer, primary_key=True, index=True)
    symbol = Column(String(20), nullable=False, index=True)
    timeframe = Column(String(10), nullable=False)
    timestamp = Column(DateTime, nullable=False, index=True)
    open = Column(Float, nullable=False)
    high = Column(Float, nullable=False)
    low = Column(Float, nullable=False)
    close = Column(Float, nullable=False)
    volume = Column(Float, nullable=False)

    class Config:
        unique_together = ('symbol', 'timeframe', 'timestamp')


class FeatureSnapshot(Base):
    """Stored feature vectors for ML training."""
    __tablename__ = "feature_snapshots"

    id = Column(Integer, primary_key=True, index=True)
    symbol = Column(String(20), nullable=False, index=True)
    timestamp = Column(DateTime, nullable=False, index=True)

    # Technical features
    rsi_14 = Column(Float)
    macd_histogram = Column(Float)
    bb_position = Column(Float)
    ema_alignment = Column(Integer)
    volume_ratio = Column(Float)

    # Sentiment features
    fear_greed_index = Column(Integer)
    news_sentiment = Column(Float)
    social_sentiment = Column(Float)

    # Price outcome (for training)
    price_change_1h = Column(Float)
    price_change_24h = Column(Float)
    price_change_7d = Column(Float)


class FearGreedHistory(Base):
    """Fear & Greed Index history."""
    __tablename__ = "fear_greed_history"

    id = Column(Integer, primary_key=True, index=True)
    value = Column(Integer, nullable=False)
    classification = Column(String(50))
    timestamp = Column(DateTime, nullable=False, unique=True, index=True)


# === Database Functions ===

async def init_db():
    """Initialize database tables."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


async def get_session() -> AsyncSession:
    """Get database session."""
    async with async_session() as session:
        yield session
