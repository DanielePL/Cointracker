-- CoinTracker Pro - Paper Trading Schema
-- Run this in your Supabase SQL Editor

-- ==================== PAPER TRADING BALANCE ====================
-- Virtuelles Guthaben für Paper Trading ($10k Start)
CREATE TABLE IF NOT EXISTS paper_balance (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE UNIQUE,
    balance_usdt DOUBLE PRECISION NOT NULL DEFAULT 10000.0,
    initial_balance DOUBLE PRECISION NOT NULL DEFAULT 10000.0,
    total_pnl DOUBLE PRECISION DEFAULT 0,
    total_trades INTEGER DEFAULT 0,
    winning_trades INTEGER DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_paper_balance_user ON paper_balance(user_id);

-- ==================== PAPER TRADES ====================
-- Trade History für Paper Trading
CREATE TABLE IF NOT EXISTS paper_trades (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity DOUBLE PRECISION NOT NULL,
    entry_price DOUBLE PRECISION NOT NULL,
    total_value DOUBLE PRECISION NOT NULL,
    exit_price DOUBLE PRECISION,
    pnl DOUBLE PRECISION,
    pnl_percent DOUBLE PRECISION,
    status TEXT DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    closed_at TIMESTAMPTZ
);

CREATE INDEX idx_paper_trades_user ON paper_trades(user_id);
CREATE INDEX idx_paper_trades_status ON paper_trades(user_id, status);
CREATE INDEX idx_paper_trades_created ON paper_trades(created_at DESC);

-- ==================== PAPER HOLDINGS ====================
-- Aktuelle Paper Trading Positionen
CREATE TABLE IF NOT EXISTS paper_holdings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    quantity DOUBLE PRECISION NOT NULL,
    avg_entry_price DOUBLE PRECISION NOT NULL,
    total_invested DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, symbol)
);

CREATE INDEX idx_paper_holdings_user ON paper_holdings(user_id);

-- ==================== ROW LEVEL SECURITY ====================

-- Enable RLS
ALTER TABLE paper_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE paper_trades ENABLE ROW LEVEL SECURITY;
ALTER TABLE paper_holdings ENABLE ROW LEVEL SECURITY;

-- Paper Balance Policies
CREATE POLICY "Users can view own paper balance" ON paper_balance
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own paper balance" ON paper_balance
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own paper balance" ON paper_balance
    FOR UPDATE USING (auth.uid() = user_id);

-- Paper Trades Policies
CREATE POLICY "Users can view own paper trades" ON paper_trades
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own paper trades" ON paper_trades
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own paper trades" ON paper_trades
    FOR UPDATE USING (auth.uid() = user_id);

-- Paper Holdings Policies
CREATE POLICY "Users can view own paper holdings" ON paper_holdings
    FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own paper holdings" ON paper_holdings
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own paper holdings" ON paper_holdings
    FOR UPDATE USING (auth.uid() = user_id);

CREATE POLICY "Users can delete own paper holdings" ON paper_holdings
    FOR DELETE USING (auth.uid() = user_id);

-- ==================== AUTO-CREATE PAPER BALANCE ====================
-- Erstelle automatisch Paper Balance wenn neuer User

CREATE OR REPLACE FUNCTION public.handle_new_user_paper_balance()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.paper_balance (user_id, balance_usdt, initial_balance)
    VALUES (NEW.id, 10000.0, 10000.0)
    ON CONFLICT (user_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created_paper ON auth.users;
CREATE TRIGGER on_auth_user_created_paper
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user_paper_balance();

-- ==================== HELPER FUNCTIONS ====================

-- Function to update paper holdings after a trade
CREATE OR REPLACE FUNCTION update_paper_holdings_after_trade()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.side = 'BUY' THEN
        -- Insert or update holding
        INSERT INTO paper_holdings (user_id, symbol, quantity, avg_entry_price, total_invested)
        VALUES (NEW.user_id, NEW.symbol, NEW.quantity, NEW.entry_price, NEW.total_value)
        ON CONFLICT (user_id, symbol) DO UPDATE SET
            quantity = paper_holdings.quantity + NEW.quantity,
            total_invested = paper_holdings.total_invested + NEW.total_value,
            avg_entry_price = (paper_holdings.total_invested + NEW.total_value) /
                             (paper_holdings.quantity + NEW.quantity),
            updated_at = NOW();

        -- Deduct from balance
        UPDATE paper_balance SET
            balance_usdt = balance_usdt - NEW.total_value,
            total_trades = total_trades + 1,
            updated_at = NOW()
        WHERE user_id = NEW.user_id;

    ELSIF NEW.side = 'SELL' THEN
        -- Update holding (reduce quantity)
        UPDATE paper_holdings SET
            quantity = quantity - NEW.quantity,
            updated_at = NOW()
        WHERE user_id = NEW.user_id AND symbol = NEW.symbol;

        -- Remove holding if quantity is 0 or less
        DELETE FROM paper_holdings
        WHERE user_id = NEW.user_id AND symbol = NEW.symbol AND quantity <= 0;

        -- Add to balance and update stats
        UPDATE paper_balance SET
            balance_usdt = balance_usdt + NEW.total_value,
            total_trades = total_trades + 1,
            total_pnl = total_pnl + COALESCE(NEW.pnl, 0),
            winning_trades = winning_trades + CASE WHEN COALESCE(NEW.pnl, 0) > 0 THEN 1 ELSE 0 END,
            updated_at = NOW()
        WHERE user_id = NEW.user_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_paper_trade_inserted ON paper_trades;
CREATE TRIGGER on_paper_trade_inserted
    AFTER INSERT ON paper_trades
    FOR EACH ROW
    EXECUTE FUNCTION update_paper_holdings_after_trade();

-- ==================== REALTIME ====================

ALTER PUBLICATION supabase_realtime ADD TABLE paper_trades;
ALTER PUBLICATION supabase_realtime ADD TABLE paper_holdings;
