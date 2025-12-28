-- CoinTracker Pro - Autonomous Trading Bot Schema
-- Der Bot handelt autonom basierend auf ML-Signalen

-- ==================== BOT BALANCE ====================
-- Virtuelles Guthaben des Bots ($10k Start)
CREATE TABLE IF NOT EXISTS bot_balance (
    id SERIAL PRIMARY KEY,
    balance_usdt DOUBLE PRECISION NOT NULL DEFAULT 10000.0,
    initial_balance DOUBLE PRECISION NOT NULL DEFAULT 10000.0,
    total_pnl DOUBLE PRECISION DEFAULT 0,
    total_pnl_percent DOUBLE PRECISION DEFAULT 0,
    total_trades INTEGER DEFAULT 0,
    winning_trades INTEGER DEFAULT 0,
    losing_trades INTEGER DEFAULT 0,
    largest_win DOUBLE PRECISION DEFAULT 0,
    largest_loss DOUBLE PRECISION DEFAULT 0,
    current_drawdown DOUBLE PRECISION DEFAULT 0,
    max_drawdown DOUBLE PRECISION DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Insert initial balance if not exists
INSERT INTO bot_balance (balance_usdt, initial_balance)
SELECT 10000.0, 10000.0
WHERE NOT EXISTS (SELECT 1 FROM bot_balance);

-- ==================== BOT POSITIONS ====================
-- Aktuelle offene Positionen des Bots
CREATE TABLE IF NOT EXISTS bot_positions (
    id SERIAL PRIMARY KEY,
    coin TEXT NOT NULL UNIQUE,
    side TEXT NOT NULL DEFAULT 'LONG' CHECK (side IN ('LONG', 'SHORT')),
    quantity DOUBLE PRECISION NOT NULL,
    entry_price DOUBLE PRECISION NOT NULL,
    current_price DOUBLE PRECISION,
    total_invested DOUBLE PRECISION NOT NULL,
    unrealized_pnl DOUBLE PRECISION DEFAULT 0,
    unrealized_pnl_percent DOUBLE PRECISION DEFAULT 0,
    stop_loss DOUBLE PRECISION,
    take_profit DOUBLE PRECISION,
    signal_score INTEGER,
    entry_signal TEXT,
    opened_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_bot_positions_coin ON bot_positions(coin);

-- ==================== BOT TRADES ====================
-- Komplette Trade-Historie des Bots
CREATE TABLE IF NOT EXISTS bot_trades (
    id SERIAL PRIMARY KEY,
    coin TEXT NOT NULL,
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity DOUBLE PRECISION NOT NULL,
    entry_price DOUBLE PRECISION NOT NULL,
    exit_price DOUBLE PRECISION,
    total_value DOUBLE PRECISION NOT NULL,
    pnl DOUBLE PRECISION,
    pnl_percent DOUBLE PRECISION,
    fee DOUBLE PRECISION DEFAULT 0,

    -- Signal info at trade time
    signal_type TEXT,  -- STRONG_BUY, BUY, SELL, STRONG_SELL
    signal_score INTEGER,
    signal_reasons TEXT[],

    -- Technical indicators at trade time
    rsi DOUBLE PRECISION,
    macd DOUBLE PRECISION,

    -- Status
    status TEXT DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED', 'STOPPED_OUT', 'TAKE_PROFIT')),
    close_reason TEXT,

    -- Timestamps
    opened_at TIMESTAMPTZ DEFAULT NOW(),
    closed_at TIMESTAMPTZ,

    -- Balance snapshot
    balance_before DOUBLE PRECISION,
    balance_after DOUBLE PRECISION
);

CREATE INDEX idx_bot_trades_coin ON bot_trades(coin);
CREATE INDEX idx_bot_trades_status ON bot_trades(status);
CREATE INDEX idx_bot_trades_opened ON bot_trades(opened_at DESC);

-- ==================== BOT PERFORMANCE DAILY ====================
-- Tägliche Performance-Statistik
CREATE TABLE IF NOT EXISTS bot_performance_daily (
    id SERIAL PRIMARY KEY,
    date DATE NOT NULL UNIQUE,
    starting_balance DOUBLE PRECISION NOT NULL,
    ending_balance DOUBLE PRECISION NOT NULL,
    daily_pnl DOUBLE PRECISION DEFAULT 0,
    daily_pnl_percent DOUBLE PRECISION DEFAULT 0,
    trades_count INTEGER DEFAULT 0,
    winning_trades INTEGER DEFAULT 0,
    losing_trades INTEGER DEFAULT 0,
    best_trade_pnl DOUBLE PRECISION,
    worst_trade_pnl DOUBLE PRECISION,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_bot_performance_date ON bot_performance_daily(date DESC);

-- ==================== BOT SETTINGS ====================
-- Bot Konfiguration
CREATE TABLE IF NOT EXISTS bot_settings (
    id SERIAL PRIMARY KEY,
    -- Trading parameters
    min_signal_score INTEGER DEFAULT 65,
    max_position_size_percent DOUBLE PRECISION DEFAULT 20.0,  -- Max 20% per Position
    max_positions INTEGER DEFAULT 5,

    -- Risk management
    stop_loss_percent DOUBLE PRECISION DEFAULT -5.0,
    take_profit_percent DOUBLE PRECISION DEFAULT 15.0,
    trailing_stop_percent DOUBLE PRECISION,

    -- Signal filters
    required_confidence DOUBLE PRECISION DEFAULT 0.6,
    min_volume_24h DOUBLE PRECISION DEFAULT 1000000,  -- Min $1M volume

    -- Trading rules
    allow_strong_buy BOOLEAN DEFAULT true,
    allow_buy BOOLEAN DEFAULT true,
    allow_sell BOOLEAN DEFAULT true,
    allow_strong_sell BOOLEAN DEFAULT true,

    -- Coins to trade
    enabled_coins TEXT[] DEFAULT ARRAY['BTC', 'ETH', 'SOL', 'XRP', 'ADA'],

    -- Bot state
    is_active BOOLEAN DEFAULT true,
    last_run_at TIMESTAMPTZ,

    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Insert default settings if not exists
INSERT INTO bot_settings (min_signal_score, max_position_size_percent, max_positions)
SELECT 65, 20.0, 5
WHERE NOT EXISTS (SELECT 1 FROM bot_settings);

-- ==================== BOT LEARNING LOG ====================
-- Was der Bot aus vergangenen Trades gelernt hat
CREATE TABLE IF NOT EXISTS bot_learning_log (
    id SERIAL PRIMARY KEY,
    trade_id INTEGER REFERENCES bot_trades(id),
    lesson_type TEXT,  -- 'WIN_PATTERN', 'LOSS_PATTERN', 'FALSE_SIGNAL', 'GOOD_SIGNAL'
    signal_type TEXT,
    signal_score INTEGER,
    outcome TEXT,  -- 'PROFIT', 'LOSS', 'BREAKEVEN'
    pnl_percent DOUBLE PRECISION,
    indicators_snapshot JSONB,
    lesson_learned TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== BOT LEARNING ANALYSIS ====================
-- Analysiert welche Signale gut/schlecht performen

-- View: Signal Performance Analysis
CREATE OR REPLACE VIEW bot_signal_analysis AS
SELECT
    signal_type,
    COUNT(*) as total_trades,
    COUNT(*) FILTER (WHERE pnl > 0) as winning_trades,
    COUNT(*) FILTER (WHERE pnl < 0) as losing_trades,
    ROUND(AVG(pnl)::numeric, 2) as avg_pnl,
    ROUND(AVG(pnl_percent)::numeric, 2) as avg_pnl_percent,
    ROUND((COUNT(*) FILTER (WHERE pnl > 0)::numeric / NULLIF(COUNT(*), 0) * 100), 1) as win_rate,
    ROUND(AVG(signal_score)::numeric, 0) as avg_signal_score,
    MAX(pnl) as best_trade,
    MIN(pnl) as worst_trade
FROM bot_trades
WHERE status != 'OPEN' AND pnl IS NOT NULL
GROUP BY signal_type
ORDER BY win_rate DESC;

-- View: Coin Performance Analysis
CREATE OR REPLACE VIEW bot_coin_analysis AS
SELECT
    coin,
    COUNT(*) as total_trades,
    ROUND(SUM(pnl)::numeric, 2) as total_pnl,
    ROUND(AVG(pnl_percent)::numeric, 2) as avg_pnl_percent,
    ROUND((COUNT(*) FILTER (WHERE pnl > 0)::numeric / NULLIF(COUNT(*), 0) * 100), 1) as win_rate,
    COUNT(*) FILTER (WHERE pnl > 0) as wins,
    COUNT(*) FILTER (WHERE pnl < 0) as losses
FROM bot_trades
WHERE status != 'OPEN' AND pnl IS NOT NULL
GROUP BY coin
ORDER BY total_pnl DESC;

-- View: Hourly Performance (beste Trading-Zeiten)
CREATE OR REPLACE VIEW bot_hourly_analysis AS
SELECT
    EXTRACT(HOUR FROM opened_at) as hour_utc,
    COUNT(*) as trades,
    ROUND(AVG(pnl_percent)::numeric, 2) as avg_pnl_percent,
    ROUND((COUNT(*) FILTER (WHERE pnl > 0)::numeric / NULLIF(COUNT(*), 0) * 100), 1) as win_rate
FROM bot_trades
WHERE status != 'OPEN' AND pnl IS NOT NULL
GROUP BY EXTRACT(HOUR FROM opened_at)
ORDER BY avg_pnl_percent DESC;

-- View: Signal Score Brackets (welche Scores performen am besten)
CREATE OR REPLACE VIEW bot_score_analysis AS
SELECT
    CASE
        WHEN signal_score >= 80 THEN '80-100 (Very Strong)'
        WHEN signal_score >= 70 THEN '70-79 (Strong)'
        WHEN signal_score >= 60 THEN '60-69 (Moderate)'
        ELSE 'Below 60 (Weak)'
    END as score_bracket,
    COUNT(*) as trades,
    ROUND(AVG(pnl_percent)::numeric, 2) as avg_pnl_percent,
    ROUND((COUNT(*) FILTER (WHERE pnl > 0)::numeric / NULLIF(COUNT(*), 0) * 100), 1) as win_rate,
    ROUND(SUM(pnl)::numeric, 2) as total_pnl
FROM bot_trades
WHERE status != 'OPEN' AND pnl IS NOT NULL AND signal_score IS NOT NULL
GROUP BY score_bracket
ORDER BY avg_pnl_percent DESC;

-- Function: Get comprehensive learning insights
CREATE OR REPLACE FUNCTION get_bot_learning_insights()
RETURNS JSON AS $$
DECLARE
    result JSON;
BEGIN
    SELECT json_build_object(
        'signal_performance', (SELECT json_agg(row_to_json(s)) FROM bot_signal_analysis s),
        'coin_performance', (SELECT json_agg(row_to_json(c)) FROM bot_coin_analysis c),
        'score_analysis', (SELECT json_agg(row_to_json(sc)) FROM bot_score_analysis sc),
        'best_performing_signal', (
            SELECT signal_type FROM bot_signal_analysis
            WHERE total_trades >= 3
            ORDER BY win_rate DESC, avg_pnl_percent DESC
            LIMIT 1
        ),
        'worst_performing_signal', (
            SELECT signal_type FROM bot_signal_analysis
            WHERE total_trades >= 3
            ORDER BY win_rate ASC, avg_pnl_percent ASC
            LIMIT 1
        ),
        'best_coin', (
            SELECT coin FROM bot_coin_analysis
            WHERE total_trades >= 2
            ORDER BY win_rate DESC, total_pnl DESC
            LIMIT 1
        ),
        'worst_coin', (
            SELECT coin FROM bot_coin_analysis
            WHERE total_trades >= 2
            ORDER BY win_rate ASC, total_pnl ASC
            LIMIT 1
        ),
        'optimal_score_threshold', (
            SELECT
                CASE score_bracket
                    WHEN '80-100 (Very Strong)' THEN 80
                    WHEN '70-79 (Strong)' THEN 70
                    WHEN '60-69 (Moderate)' THEN 60
                    ELSE 65
                END
            FROM bot_score_analysis
            WHERE trades >= 3
            ORDER BY win_rate DESC, avg_pnl_percent DESC
            LIMIT 1
        ),
        'recommendations', (
            SELECT json_agg(recommendation) FROM (
                -- Empfehlung basierend auf Score-Analyse
                SELECT 'Consider raising min_signal_score to ' ||
                    CASE
                        WHEN (SELECT avg_pnl_percent FROM bot_score_analysis WHERE score_bracket = '80-100 (Very Strong)') >
                             (SELECT avg_pnl_percent FROM bot_score_analysis WHERE score_bracket = '70-79 (Strong)')
                        THEN '80'
                        ELSE '70'
                    END as recommendation
                WHERE EXISTS (SELECT 1 FROM bot_score_analysis WHERE trades >= 5)

                UNION ALL

                -- Empfehlung für schlechte Coins
                SELECT 'Consider removing ' || coin || ' from enabled_coins (win rate: ' || win_rate || '%)' as recommendation
                FROM bot_coin_analysis
                WHERE win_rate < 40 AND total_trades >= 3
                LIMIT 2
            ) recs
        ),
        'total_trades_analyzed', (SELECT COUNT(*) FROM bot_trades WHERE status != 'OPEN'),
        'analysis_timestamp', NOW()
    ) INTO result;

    RETURN result;
END;
$$ LANGUAGE plpgsql;

-- Function: Auto-adjust bot settings based on learning
CREATE OR REPLACE FUNCTION bot_auto_optimize()
RETURNS JSON AS $$
DECLARE
    v_optimal_score INTEGER;
    v_bad_coins TEXT[];
    v_current_coins TEXT[];
    v_new_coins TEXT[];
    v_changes JSON;
BEGIN
    -- Nur optimieren wenn genug Trades vorhanden
    IF (SELECT COUNT(*) FROM bot_trades WHERE status != 'OPEN') < 10 THEN
        RETURN json_build_object(
            'optimized', false,
            'reason', 'Not enough trades for optimization (need at least 10 closed trades)',
            'current_trades', (SELECT COUNT(*) FROM bot_trades WHERE status != 'OPEN')
        );
    END IF;

    -- Optimalen Score-Threshold finden
    SELECT
        CASE
            WHEN score_bracket = '80-100 (Very Strong)' AND win_rate >= 60 THEN 80
            WHEN score_bracket = '70-79 (Strong)' AND win_rate >= 55 THEN 70
            ELSE 65
        END INTO v_optimal_score
    FROM bot_score_analysis
    WHERE trades >= 3
    ORDER BY (win_rate * avg_pnl_percent) DESC
    LIMIT 1;

    -- Schlecht performende Coins finden
    SELECT ARRAY_AGG(coin) INTO v_bad_coins
    FROM bot_coin_analysis
    WHERE win_rate < 35 AND total_trades >= 3;

    -- Aktuelle Coins holen
    SELECT enabled_coins INTO v_current_coins FROM bot_settings LIMIT 1;

    -- Schlechte Coins entfernen
    IF v_bad_coins IS NOT NULL THEN
        SELECT ARRAY(
            SELECT unnest(v_current_coins)
            EXCEPT
            SELECT unnest(v_bad_coins)
        ) INTO v_new_coins;
    ELSE
        v_new_coins := v_current_coins;
    END IF;

    -- Settings aktualisieren
    UPDATE bot_settings SET
        min_signal_score = COALESCE(v_optimal_score, min_signal_score),
        enabled_coins = CASE WHEN array_length(v_new_coins, 1) >= 3 THEN v_new_coins ELSE enabled_coins END,
        updated_at = NOW();

    -- Änderungen dokumentieren
    INSERT INTO bot_learning_log (lesson_type, lesson_learned, indicators_snapshot)
    VALUES (
        'AUTO_OPTIMIZE',
        'Bot auto-optimized settings based on ' || (SELECT COUNT(*) FROM bot_trades WHERE status != 'OPEN') || ' trades',
        json_build_object(
            'new_min_score', v_optimal_score,
            'removed_coins', v_bad_coins,
            'timestamp', NOW()
        )
    );

    RETURN json_build_object(
        'optimized', true,
        'new_min_signal_score', v_optimal_score,
        'removed_coins', v_bad_coins,
        'remaining_coins', v_new_coins,
        'trades_analyzed', (SELECT COUNT(*) FROM bot_trades WHERE status != 'OPEN')
    );
END;
$$ LANGUAGE plpgsql;

-- ==================== FUNCTIONS ====================

-- Function to get current bot stats
CREATE OR REPLACE FUNCTION get_bot_stats()
RETURNS JSON AS $$
DECLARE
    result JSON;
BEGIN
    SELECT json_build_object(
        'balance', b.balance_usdt,
        'initial_balance', b.initial_balance,
        'total_pnl', b.total_pnl,
        'total_pnl_percent', b.total_pnl_percent,
        'total_trades', b.total_trades,
        'winning_trades', b.winning_trades,
        'losing_trades', b.losing_trades,
        'win_rate', CASE WHEN b.total_trades > 0
            THEN ROUND((b.winning_trades::numeric / b.total_trades * 100), 2)
            ELSE 0 END,
        'open_positions', (SELECT COUNT(*) FROM bot_positions),
        'largest_win', b.largest_win,
        'largest_loss', b.largest_loss,
        'max_drawdown', b.max_drawdown
    ) INTO result
    FROM bot_balance b
    LIMIT 1;

    RETURN result;
END;
$$ LANGUAGE plpgsql;

-- Function to record a trade and update balance
CREATE OR REPLACE FUNCTION execute_bot_trade(
    p_coin TEXT,
    p_side TEXT,
    p_quantity DOUBLE PRECISION,
    p_price DOUBLE PRECISION,
    p_signal_type TEXT,
    p_signal_score INTEGER,
    p_signal_reasons TEXT[],
    p_rsi DOUBLE PRECISION DEFAULT NULL,
    p_macd DOUBLE PRECISION DEFAULT NULL,
    p_stop_loss DOUBLE PRECISION DEFAULT NULL,
    p_take_profit DOUBLE PRECISION DEFAULT NULL
)
RETURNS JSON AS $$
DECLARE
    v_balance DOUBLE PRECISION;
    v_total_value DOUBLE PRECISION;
    v_trade_id INTEGER;
    v_position bot_positions%ROWTYPE;
    v_pnl DOUBLE PRECISION;
    v_pnl_percent DOUBLE PRECISION;
BEGIN
    -- Get current balance
    SELECT balance_usdt INTO v_balance FROM bot_balance LIMIT 1;

    v_total_value := p_quantity * p_price;

    IF p_side = 'BUY' THEN
        -- Check if we have enough balance
        IF v_balance < v_total_value THEN
            RETURN json_build_object('success', false, 'error', 'Insufficient balance');
        END IF;

        -- Insert trade
        INSERT INTO bot_trades (
            coin, side, quantity, entry_price, total_value,
            signal_type, signal_score, signal_reasons, rsi, macd,
            status, balance_before
        ) VALUES (
            p_coin, 'BUY', p_quantity, p_price, v_total_value,
            p_signal_type, p_signal_score, p_signal_reasons, p_rsi, p_macd,
            'OPEN', v_balance
        ) RETURNING id INTO v_trade_id;

        -- Update or insert position
        INSERT INTO bot_positions (
            coin, quantity, entry_price, current_price, total_invested,
            stop_loss, take_profit, signal_score, entry_signal
        ) VALUES (
            p_coin, p_quantity, p_price, p_price, v_total_value,
            p_stop_loss, p_take_profit, p_signal_score, p_signal_type
        )
        ON CONFLICT (coin) DO UPDATE SET
            quantity = bot_positions.quantity + p_quantity,
            total_invested = bot_positions.total_invested + v_total_value,
            entry_price = (bot_positions.total_invested + v_total_value) /
                         (bot_positions.quantity + p_quantity),
            updated_at = NOW();

        -- Deduct from balance
        UPDATE bot_balance SET
            balance_usdt = balance_usdt - v_total_value,
            total_trades = total_trades + 1,
            updated_at = NOW();

        RETURN json_build_object(
            'success', true,
            'trade_id', v_trade_id,
            'action', 'BUY',
            'coin', p_coin,
            'quantity', p_quantity,
            'price', p_price,
            'total_value', v_total_value,
            'new_balance', v_balance - v_total_value
        );

    ELSIF p_side = 'SELL' THEN
        -- Get position
        SELECT * INTO v_position FROM bot_positions WHERE coin = p_coin;

        IF v_position IS NULL THEN
            RETURN json_build_object('success', false, 'error', 'No position to sell');
        END IF;

        -- Calculate PnL
        v_pnl := (p_price - v_position.entry_price) * p_quantity;
        v_pnl_percent := ((p_price / v_position.entry_price) - 1) * 100;

        -- Insert trade
        INSERT INTO bot_trades (
            coin, side, quantity, entry_price, exit_price, total_value,
            pnl, pnl_percent, signal_type, signal_score, signal_reasons,
            rsi, macd, status, close_reason, balance_before, closed_at
        ) VALUES (
            p_coin, 'SELL', p_quantity, v_position.entry_price, p_price, v_total_value,
            v_pnl, v_pnl_percent, p_signal_type, p_signal_score, p_signal_reasons,
            p_rsi, p_macd, 'CLOSED', 'SIGNAL', v_balance, NOW()
        ) RETURNING id INTO v_trade_id;

        -- Update trade with balance after
        UPDATE bot_trades SET balance_after = v_balance + v_total_value WHERE id = v_trade_id;

        -- Remove or reduce position
        IF p_quantity >= v_position.quantity THEN
            DELETE FROM bot_positions WHERE coin = p_coin;
        ELSE
            UPDATE bot_positions SET
                quantity = quantity - p_quantity,
                total_invested = total_invested - (v_position.entry_price * p_quantity),
                updated_at = NOW()
            WHERE coin = p_coin;
        END IF;

        -- Update balance and stats
        UPDATE bot_balance SET
            balance_usdt = balance_usdt + v_total_value,
            total_trades = total_trades + 1,
            total_pnl = total_pnl + v_pnl,
            total_pnl_percent = ((balance_usdt + v_total_value - initial_balance) / initial_balance) * 100,
            winning_trades = winning_trades + CASE WHEN v_pnl > 0 THEN 1 ELSE 0 END,
            losing_trades = losing_trades + CASE WHEN v_pnl < 0 THEN 1 ELSE 0 END,
            largest_win = GREATEST(largest_win, CASE WHEN v_pnl > 0 THEN v_pnl ELSE 0 END),
            largest_loss = LEAST(largest_loss, CASE WHEN v_pnl < 0 THEN v_pnl ELSE 0 END),
            updated_at = NOW();

        -- Log learning
        INSERT INTO bot_learning_log (
            trade_id, lesson_type, signal_type, signal_score, outcome, pnl_percent,
            indicators_snapshot
        ) VALUES (
            v_trade_id,
            CASE WHEN v_pnl > 0 THEN 'WIN_PATTERN' ELSE 'LOSS_PATTERN' END,
            p_signal_type, p_signal_score,
            CASE WHEN v_pnl > 0 THEN 'PROFIT' WHEN v_pnl < 0 THEN 'LOSS' ELSE 'BREAKEVEN' END,
            v_pnl_percent,
            json_build_object('rsi', p_rsi, 'macd', p_macd)
        );

        RETURN json_build_object(
            'success', true,
            'trade_id', v_trade_id,
            'action', 'SELL',
            'coin', p_coin,
            'quantity', p_quantity,
            'entry_price', v_position.entry_price,
            'exit_price', p_price,
            'pnl', v_pnl,
            'pnl_percent', v_pnl_percent,
            'new_balance', v_balance + v_total_value
        );
    END IF;

    RETURN json_build_object('success', false, 'error', 'Invalid side');
END;
$$ LANGUAGE plpgsql;

-- ==================== REALTIME ====================
ALTER PUBLICATION supabase_realtime ADD TABLE bot_trades;
ALTER PUBLICATION supabase_realtime ADD TABLE bot_positions;
ALTER PUBLICATION supabase_realtime ADD TABLE bot_balance;

-- ==================== GRANT ACCESS ====================
-- Allow service role full access (for the ML backend)
GRANT ALL ON bot_balance TO service_role;
GRANT ALL ON bot_positions TO service_role;
GRANT ALL ON bot_trades TO service_role;
GRANT ALL ON bot_settings TO service_role;
GRANT ALL ON bot_learning_log TO service_role;
GRANT ALL ON bot_performance_daily TO service_role;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO service_role;

-- Allow anon role to read (for the app)
GRANT SELECT ON bot_balance TO anon;
GRANT SELECT ON bot_positions TO anon;
GRANT SELECT ON bot_trades TO anon;
GRANT SELECT ON bot_settings TO anon;
GRANT SELECT ON bot_performance_daily TO anon;
