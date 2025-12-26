-- CoinTracker Pro - Supabase Database Schema
-- Run this in your Supabase SQL Editor

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==================== USER PROFILES ====================
-- Extends Supabase auth.users with app-specific data
CREATE TABLE IF NOT EXISTS profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT,
    display_name TEXT,
    avatar_url TEXT,
    fcm_token TEXT,
    notifications_enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Auto-create profile on user signup
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, email)
    VALUES (NEW.id, NEW.email);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- ==================== PORTFOLIO HOLDINGS ====================
CREATE TABLE IF NOT EXISTS portfolio_holdings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    amount DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_buy_price DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_invested DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, symbol)
);

CREATE INDEX idx_portfolio_user ON portfolio_holdings(user_id);

-- ==================== TRADES ====================
CREATE TABLE IF NOT EXISTS trades (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    amount DOUBLE PRECISION NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    total_value DOUBLE PRECISION NOT NULL,
    fee DOUBLE PRECISION DEFAULT 0,
    signal_score INTEGER,
    signal_reasons JSONB,
    status TEXT DEFAULT 'COMPLETED' CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    exchange_order_id TEXT,
    executed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_trades_user ON trades(user_id);
CREATE INDEX idx_trades_symbol ON trades(symbol);
CREATE INDEX idx_trades_created ON trades(created_at DESC);

-- ==================== PRICE ALERTS ====================
CREATE TABLE IF NOT EXISTS price_alerts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    target_price DOUBLE PRECISION NOT NULL,
    direction TEXT NOT NULL CHECK (direction IN ('ABOVE', 'BELOW')),
    is_active BOOLEAN DEFAULT true,
    triggered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_price_alerts_user ON price_alerts(user_id);
CREATE INDEX idx_price_alerts_active ON price_alerts(is_active, symbol);

-- ==================== SIGNAL HISTORY ====================
-- ML-generated trading signals stored for analysis
CREATE TABLE IF NOT EXISTS signal_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    symbol TEXT NOT NULL,
    signal TEXT NOT NULL CHECK (signal IN ('BUY', 'SELL', 'HOLD', 'STRONG_BUY', 'STRONG_SELL')),
    signal_score INTEGER NOT NULL CHECK (signal_score >= 0 AND signal_score <= 100),
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    reasons JSONB NOT NULL,
    risk_level TEXT NOT NULL,
    price_at_signal DOUBLE PRECISION NOT NULL,
    price_24h_later DOUBLE PRECISION,
    was_correct BOOLEAN,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_signals_symbol ON signal_history(symbol);
CREATE INDEX idx_signals_created ON signal_history(created_at DESC);

-- ==================== WHALE ALERTS ====================
CREATE TABLE IF NOT EXISTS whale_alerts (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    symbol TEXT NOT NULL,
    amount_usd DOUBLE PRECISION NOT NULL,
    from_address TEXT,
    to_address TEXT,
    from_label TEXT,
    to_label TEXT,
    is_exchange_inflow BOOLEAN DEFAULT false,
    is_exchange_outflow BOOLEAN DEFAULT false,
    tx_hash TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_whale_symbol ON whale_alerts(symbol);
CREATE INDEX idx_whale_created ON whale_alerts(created_at DESC);
CREATE INDEX idx_whale_amount ON whale_alerts(amount_usd DESC);

-- ==================== NOTIFICATION PREFERENCES ====================
CREATE TABLE IF NOT EXISTS notification_preferences (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE UNIQUE,
    trading_signals BOOLEAN DEFAULT true,
    whale_alerts BOOLEAN DEFAULT true,
    price_alerts BOOLEAN DEFAULT true,
    sentiment_shifts BOOLEAN DEFAULT true,
    min_signal_score INTEGER DEFAULT 70,
    min_whale_amount_usd DOUBLE PRECISION DEFAULT 1000000,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Auto-create notification prefs on profile creation
CREATE OR REPLACE FUNCTION public.handle_new_profile()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.notification_preferences (user_id)
    VALUES (NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_profile_created ON profiles;
CREATE TRIGGER on_profile_created
    AFTER INSERT ON profiles
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_profile();

-- ==================== ROW LEVEL SECURITY ====================

-- Enable RLS on all tables
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio_holdings ENABLE ROW LEVEL SECURITY;
ALTER TABLE trades ENABLE ROW LEVEL SECURITY;
ALTER TABLE price_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_preferences ENABLE ROW LEVEL SECURITY;

-- Profiles: Users can only see/edit their own profile
CREATE POLICY "Users can view own profile" ON profiles
    FOR SELECT USING (auth.uid() = id);

CREATE POLICY "Users can update own profile" ON profiles
    FOR UPDATE USING (auth.uid() = id);

-- Portfolio: Users can only see/edit their own holdings
CREATE POLICY "Users can view own portfolio" ON portfolio_holdings
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own holdings" ON portfolio_holdings
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own holdings" ON portfolio_holdings
    FOR UPDATE USING (auth.uid() = user_id);

CREATE POLICY "Users can delete own holdings" ON portfolio_holdings
    FOR DELETE USING (auth.uid() = user_id);

-- Trades: Users can only see their own trades
CREATE POLICY "Users can view own trades" ON trades
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own trades" ON trades
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Price Alerts: Users can manage their own alerts
CREATE POLICY "Users can view own price alerts" ON price_alerts
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own price alerts" ON price_alerts
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own price alerts" ON price_alerts
    FOR UPDATE USING (auth.uid() = user_id);

CREATE POLICY "Users can delete own price alerts" ON price_alerts
    FOR DELETE USING (auth.uid() = user_id);

-- Signal History: Everyone can read (public data)
CREATE POLICY "Anyone can view signals" ON signal_history
    FOR SELECT TO authenticated USING (true);

-- Whale Alerts: Everyone can read (public data)
CREATE POLICY "Anyone can view whale alerts" ON whale_alerts
    FOR SELECT TO authenticated USING (true);

-- Notification Preferences: Users can manage their own
CREATE POLICY "Users can view own notification prefs" ON notification_preferences
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can update own notification prefs" ON notification_preferences
    FOR UPDATE USING (auth.uid() = user_id);

-- ==================== REALTIME ====================

-- Enable realtime for relevant tables
ALTER PUBLICATION supabase_realtime ADD TABLE whale_alerts;
ALTER PUBLICATION supabase_realtime ADD TABLE signal_history;
ALTER PUBLICATION supabase_realtime ADD TABLE trades;
ALTER PUBLICATION supabase_realtime ADD TABLE price_alerts;

-- ==================== HELPER FUNCTIONS ====================

-- Function to update portfolio after a trade
CREATE OR REPLACE FUNCTION update_portfolio_after_trade()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.side = 'BUY' THEN
        INSERT INTO portfolio_holdings (user_id, symbol, amount, avg_buy_price, total_invested)
        VALUES (NEW.user_id, NEW.symbol, NEW.amount, NEW.price, NEW.total_value)
        ON CONFLICT (user_id, symbol) DO UPDATE SET
            amount = portfolio_holdings.amount + NEW.amount,
            total_invested = portfolio_holdings.total_invested + NEW.total_value,
            avg_buy_price = (portfolio_holdings.total_invested + NEW.total_value) /
                           (portfolio_holdings.amount + NEW.amount),
            updated_at = NOW();
    ELSIF NEW.side = 'SELL' THEN
        UPDATE portfolio_holdings SET
            amount = amount - NEW.amount,
            updated_at = NOW()
        WHERE user_id = NEW.user_id AND symbol = NEW.symbol;

        -- Remove if amount is 0 or negative
        DELETE FROM portfolio_holdings
        WHERE user_id = NEW.user_id AND symbol = NEW.symbol AND amount <= 0;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_trade_completed ON trades;
CREATE TRIGGER on_trade_completed
    AFTER INSERT ON trades
    FOR EACH ROW
    WHEN (NEW.status = 'COMPLETED')
    EXECUTE FUNCTION update_portfolio_after_trade();
