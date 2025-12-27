"""
CoinTracker Pro - Configuration
"""
from pydantic_settings import BaseSettings
from typing import Optional
from functools import lru_cache


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    # App
    app_name: str = "CoinTracker Pro"
    debug: bool = True
    api_version: str = "v1"

    # Auth
    secret_key: str = "your-secret-key-change-in-production"
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30

    # Binance API
    binance_api_key: Optional[str] = None
    binance_api_secret: Optional[str] = None
    binance_testnet: bool = True  # Use testnet by default for safety

    # Database
    database_url: str = "sqlite+aiosqlite:///./cointracker.db"

    # External APIs
    fear_greed_api_url: str = "https://api.alternative.me/fng/"
    lunarcrush_api_key: Optional[str] = None

    # Trading Settings
    default_trading_pair: str = "BTC/USDT"
    supported_pairs: list = ["BTC/USDT", "ETH/USDT", "SOL/USDT", "XRP/USDT"]

    # ML Settings
    signal_threshold: int = 70  # Minimum score to trigger signal
    confidence_threshold: float = 0.7

    # Risk Management
    max_position_size_pct: float = 10.0  # Max 10% of portfolio per trade
    default_stop_loss_pct: float = 5.0
    default_take_profit_pct: float = 15.0

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


@lru_cache()
def get_settings() -> Settings:
    """Cached settings instance."""
    return Settings()


# Module-level settings instance for direct imports
settings = get_settings()
