-- CoinTracker Pro - Analysis Logging Schema
-- Comprehensive logging for ML training and performance tracking

-- ==================== ANALYSIS LOGS ====================
-- Every coin analysis gets logged here
CREATE TABLE IF NOT EXISTS analysis_logs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    coin TEXT NOT NULL,
    timestamp TIMESTAMPTZ DEFAULT NOW(),

    -- Market data at time of analysis
    price DOUBLE PRECISION NOT NULL,
    volume_24h DOUBLE PRECISION,
    price_change_24h DOUBLE PRECISION,

    -- Technical indicators
    rsi DOUBLE PRECISION,
    macd DOUBLE PRECISION,
    macd_signal DOUBLE PRECISION,
    ema_12 DOUBLE PRECISION,
    ema_26 DOUBLE PRECISION,
    bb_upper DOUBLE PRECISION,
    bb_lower DOUBLE PRECISION,
    atr DOUBLE PRECISION,

    -- ML Decision
    ml_signal TEXT NOT NULL,           -- STRONG_BUY, BUY, HOLD, SELL, STRONG_SELL
    ml_score INTEGER NOT NULL,         -- 0-100
    ml_confidence DOUBLE PRECISION,    -- 0-1

    -- Technical (rule-based) Decision
    tech_signal TEXT,
    tech_score INTEGER,

    -- Reasoning
    top_reasons TEXT[],

    -- Retrospective data (filled later)
    price_after_1h DOUBLE PRECISION,
    price_after_4h DOUBLE PRECISION,
    price_after_24h DOUBLE PRECISION,
    actual_change_1h DOUBLE PRECISION,
    actual_change_4h DOUBLE PRECISION,
    actual_change_24h DOUBLE PRECISION,
    was_correct_1h BOOLEAN,
    was_correct_4h BOOLEAN,
    was_correct_24h BOOLEAN,
    retrospective_filled_at TIMESTAMPTZ
);

CREATE INDEX idx_analysis_logs_coin ON analysis_logs(coin);
CREATE INDEX idx_analysis_logs_timestamp ON analysis_logs(timestamp DESC);
CREATE INDEX idx_analysis_logs_signal ON analysis_logs(ml_signal);
CREATE INDEX idx_analysis_logs_score ON analysis_logs(ml_score DESC);
CREATE INDEX idx_analysis_logs_retrospective ON analysis_logs(retrospective_filled_at)
    WHERE retrospective_filled_at IS NULL;

-- ==================== ANALYSIS RUNS ====================
-- Summary of each analysis run
CREATE TABLE IF NOT EXISTS analysis_runs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    executed_at TIMESTAMPTZ DEFAULT NOW(),
    coins_analyzed INTEGER NOT NULL,
    duration_ms INTEGER,
    strong_buys INTEGER DEFAULT 0,
    strong_sells INTEGER DEFAULT 0,
    avg_confidence DOUBLE PRECISION,
    error TEXT
);

CREATE INDEX idx_analysis_runs_executed ON analysis_runs(executed_at DESC);

-- ==================== ML PERFORMANCE ====================
-- Daily/weekly performance metrics
CREATE TABLE IF NOT EXISTS ml_performance (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    date DATE NOT NULL UNIQUE,

    -- Prediction counts
    total_predictions INTEGER DEFAULT 0,
    strong_buy_count INTEGER DEFAULT 0,
    buy_count INTEGER DEFAULT 0,
    hold_count INTEGER DEFAULT 0,
    sell_count INTEGER DEFAULT 0,
    strong_sell_count INTEGER DEFAULT 0,

    -- Accuracy (based on retrospective)
    correct_predictions_1h INTEGER DEFAULT 0,
    correct_predictions_4h INTEGER DEFAULT 0,
    correct_predictions_24h INTEGER DEFAULT 0,
    accuracy_1h DOUBLE PRECISION,
    accuracy_4h DOUBLE PRECISION,
    accuracy_24h DOUBLE PRECISION,

    -- Best/Worst
    avg_confidence DOUBLE PRECISION,
    best_coin TEXT,
    best_coin_gain DOUBLE PRECISION,
    worst_coin TEXT,
    worst_coin_loss DOUBLE PRECISION,

    -- Hypothetical P&L (if we traded all signals)
    hypothetical_pnl DOUBLE PRECISION DEFAULT 0,

    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_ml_performance_date ON ml_performance(date DESC);

-- ==================== MARKET SNAPSHOTS ====================
-- Store price snapshots for retrospective analysis
CREATE TABLE IF NOT EXISTS market_snapshots (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    timestamp TIMESTAMPTZ DEFAULT NOW(),
    coin TEXT NOT NULL,
    price DOUBLE PRECISION NOT NULL,
    volume DOUBLE PRECISION,

    -- Link to original analysis
    analysis_log_id UUID REFERENCES analysis_logs(id) ON DELETE SET NULL
);

CREATE INDEX idx_market_snapshots_coin_time ON market_snapshots(coin, timestamp DESC);
CREATE INDEX idx_market_snapshots_analysis ON market_snapshots(analysis_log_id);

-- ==================== FUNCTIONS ====================

-- Function to calculate if prediction was correct
CREATE OR REPLACE FUNCTION calculate_prediction_correctness(
    signal TEXT,
    price_change DOUBLE PRECISION
) RETURNS BOOLEAN AS $$
BEGIN
    -- STRONG_BUY/BUY correct if price went up
    IF signal IN ('STRONG_BUY', 'BUY') THEN
        RETURN price_change > 0;
    -- STRONG_SELL/SELL correct if price went down
    ELSIF signal IN ('STRONG_SELL', 'SELL') THEN
        RETURN price_change < 0;
    -- HOLD correct if price didn't move much (within 2%)
    ELSE
        RETURN ABS(price_change) < 2;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Function to fill retrospective data
CREATE OR REPLACE FUNCTION fill_retrospective_data()
RETURNS INTEGER AS $$
DECLARE
    updated_count INTEGER := 0;
    log_record RECORD;
    current_price DOUBLE PRECISION;
BEGIN
    -- Find logs that need retrospective data (older than 24h, not yet filled)
    FOR log_record IN
        SELECT id, coin, price, ml_signal, timestamp
        FROM analysis_logs
        WHERE retrospective_filled_at IS NULL
        AND timestamp < NOW() - INTERVAL '24 hours'
        LIMIT 1000
    LOOP
        -- Get prices at different intervals
        -- Note: This assumes we have market_snapshots data

        -- Update the log with retrospective data
        UPDATE analysis_logs SET
            retrospective_filled_at = NOW()
        WHERE id = log_record.id;

        updated_count := updated_count + 1;
    END LOOP;

    RETURN updated_count;
END;
$$ LANGUAGE plpgsql;

-- ==================== DAILY PERFORMANCE AGGREGATION ====================
CREATE OR REPLACE FUNCTION aggregate_daily_performance(target_date DATE)
RETURNS VOID AS $$
BEGIN
    INSERT INTO ml_performance (
        date,
        total_predictions,
        strong_buy_count,
        buy_count,
        hold_count,
        sell_count,
        strong_sell_count,
        correct_predictions_1h,
        correct_predictions_4h,
        correct_predictions_24h,
        accuracy_1h,
        accuracy_4h,
        accuracy_24h,
        avg_confidence
    )
    SELECT
        target_date,
        COUNT(*),
        COUNT(*) FILTER (WHERE ml_signal = 'STRONG_BUY'),
        COUNT(*) FILTER (WHERE ml_signal = 'BUY'),
        COUNT(*) FILTER (WHERE ml_signal = 'HOLD'),
        COUNT(*) FILTER (WHERE ml_signal = 'SELL'),
        COUNT(*) FILTER (WHERE ml_signal = 'STRONG_SELL'),
        COUNT(*) FILTER (WHERE was_correct_1h = true),
        COUNT(*) FILTER (WHERE was_correct_4h = true),
        COUNT(*) FILTER (WHERE was_correct_24h = true),
        ROUND(100.0 * COUNT(*) FILTER (WHERE was_correct_1h = true) / NULLIF(COUNT(*), 0), 2),
        ROUND(100.0 * COUNT(*) FILTER (WHERE was_correct_4h = true) / NULLIF(COUNT(*), 0), 2),
        ROUND(100.0 * COUNT(*) FILTER (WHERE was_correct_24h = true) / NULLIF(COUNT(*), 0), 2),
        ROUND(AVG(ml_confidence)::numeric, 4)
    FROM analysis_logs
    WHERE DATE(timestamp) = target_date
    ON CONFLICT (date) DO UPDATE SET
        total_predictions = EXCLUDED.total_predictions,
        strong_buy_count = EXCLUDED.strong_buy_count,
        buy_count = EXCLUDED.buy_count,
        hold_count = EXCLUDED.hold_count,
        sell_count = EXCLUDED.sell_count,
        strong_sell_count = EXCLUDED.strong_sell_count,
        correct_predictions_1h = EXCLUDED.correct_predictions_1h,
        correct_predictions_4h = EXCLUDED.correct_predictions_4h,
        correct_predictions_24h = EXCLUDED.correct_predictions_24h,
        accuracy_1h = EXCLUDED.accuracy_1h,
        accuracy_4h = EXCLUDED.accuracy_4h,
        accuracy_24h = EXCLUDED.accuracy_24h,
        avg_confidence = EXCLUDED.avg_confidence,
        updated_at = NOW();
END;
$$ LANGUAGE plpgsql;

-- ==================== RLS POLICIES ====================
-- Analysis logs are public read for transparency
ALTER TABLE analysis_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE analysis_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ml_performance ENABLE ROW LEVEL SECURITY;
ALTER TABLE market_snapshots ENABLE ROW LEVEL SECURITY;

-- Everyone can read analysis data
CREATE POLICY "Public read access for analysis_logs"
    ON analysis_logs FOR SELECT USING (true);

CREATE POLICY "Public read access for analysis_runs"
    ON analysis_runs FOR SELECT USING (true);

CREATE POLICY "Public read access for ml_performance"
    ON ml_performance FOR SELECT USING (true);

CREATE POLICY "Public read access for market_snapshots"
    ON market_snapshots FOR SELECT USING (true);

-- Only service role can insert/update
CREATE POLICY "Service insert for analysis_logs"
    ON analysis_logs FOR INSERT WITH CHECK (true);

CREATE POLICY "Service update for analysis_logs"
    ON analysis_logs FOR UPDATE USING (true);

CREATE POLICY "Service insert for analysis_runs"
    ON analysis_runs FOR INSERT WITH CHECK (true);

CREATE POLICY "Service insert for ml_performance"
    ON ml_performance FOR INSERT WITH CHECK (true);

CREATE POLICY "Service update for ml_performance"
    ON ml_performance FOR UPDATE USING (true);

CREATE POLICY "Service insert for market_snapshots"
    ON market_snapshots FOR INSERT WITH CHECK (true);

-- ==================== REALTIME ====================
ALTER PUBLICATION supabase_realtime ADD TABLE analysis_logs;
ALTER PUBLICATION supabase_realtime ADD TABLE ml_performance;
