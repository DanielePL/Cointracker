-- CoinTracker Pro - Auto Trading Schema
-- Run this in your Supabase SQL Editor

-- ==================== AUTO TRADING SETTINGS ====================
-- User settings for auto trading
CREATE TABLE IF NOT EXISTS auto_trading_settings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE UNIQUE,
    enabled BOOLEAN DEFAULT false,
    min_signal_score INTEGER DEFAULT 70,
    trade_percentage DOUBLE PRECISION DEFAULT 0.10,
    max_positions INTEGER DEFAULT 3,
    stop_loss_percent DOUBLE PRECISION DEFAULT -5.0,
    take_profit_percent DOUBLE PRECISION DEFAULT 10.0,
    symbols TEXT[] DEFAULT ARRAY['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'XRPUSDT', 'ADAUSDT'],
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_auto_trading_settings_user ON auto_trading_settings(user_id);
CREATE INDEX idx_auto_trading_settings_enabled ON auto_trading_settings(enabled) WHERE enabled = true;

-- ==================== AUTO TRADING LOG ====================
-- Log of auto trading executions
CREATE TABLE IF NOT EXISTS auto_trading_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    executed_at TIMESTAMPTZ DEFAULT NOW(),
    users_processed INTEGER DEFAULT 0,
    signals JSONB,
    results JSONB,
    error TEXT,
    duration_ms INTEGER
);

CREATE INDEX idx_auto_trading_log_executed ON auto_trading_log(executed_at DESC);

-- ==================== EXTEND PAPER TRADES ====================
-- Add columns for auto trading tracking (only if table exists)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'paper_trades') THEN
        ALTER TABLE paper_trades ADD COLUMN IF NOT EXISTS source TEXT DEFAULT 'MANUAL';
        ALTER TABLE paper_trades ADD COLUMN IF NOT EXISTS close_reason TEXT;
    END IF;
END $$;

-- ==================== AUTO TRADING TRADES LOG ====================
-- Detailed log of individual auto trades per user
CREATE TABLE IF NOT EXISTS auto_trades (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity DOUBLE PRECISION NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    total_value DOUBLE PRECISION NOT NULL,
    signal_score INTEGER,
    reason TEXT,
    pnl DOUBLE PRECISION,
    pnl_percent DOUBLE PRECISION,
    executed_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_auto_trades_user ON auto_trades(user_id);
CREATE INDEX idx_auto_trades_executed ON auto_trades(executed_at DESC);

-- ==================== RLS POLICIES ====================
-- Enable RLS
ALTER TABLE auto_trading_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE auto_trades ENABLE ROW LEVEL SECURITY;

-- Users can only see/modify their own settings
CREATE POLICY "Users can view own auto trading settings"
    ON auto_trading_settings FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own auto trading settings"
    ON auto_trading_settings FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own auto trading settings"
    ON auto_trading_settings FOR UPDATE
    USING (auth.uid() = user_id);

-- Users can view their own auto trades
CREATE POLICY "Users can view own auto trades"
    ON auto_trades FOR SELECT
    USING (auth.uid() = user_id);

-- Service role can insert auto trades (for edge function)
CREATE POLICY "Service can insert auto trades"
    ON auto_trades FOR INSERT
    WITH CHECK (true);

-- ==================== HELPER FUNCTION ====================
-- Function to get or create auto trading settings
CREATE OR REPLACE FUNCTION get_or_create_auto_trading_settings(p_user_id UUID)
RETURNS auto_trading_settings AS $$
DECLARE
    result auto_trading_settings;
BEGIN
    SELECT * INTO result FROM auto_trading_settings WHERE user_id = p_user_id;

    IF NOT FOUND THEN
        INSERT INTO auto_trading_settings (user_id)
        VALUES (p_user_id)
        RETURNING * INTO result;
    END IF;

    RETURN result;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ==================== CRON JOB SETUP ====================
-- Enable pg_cron extension (run as superuser)
-- CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Schedule auto-trader to run every 5 minutes
-- Note: Replace YOUR_SUPABASE_URL and YOUR_ANON_KEY with actual values
-- SELECT cron.schedule(
--     'auto-trader-job',
--     '*/5 * * * *',
--     $$
--     SELECT net.http_post(
--         url := 'https://YOUR_PROJECT_REF.supabase.co/functions/v1/auto-trader',
--         headers := '{"Authorization": "Bearer YOUR_ANON_KEY"}'::jsonb,
--         body := '{}'::jsonb
--     ) AS request_id;
--     $$
-- );

-- To view scheduled jobs: SELECT * FROM cron.job;
-- To unschedule: SELECT cron.unschedule('auto-trader-job');
