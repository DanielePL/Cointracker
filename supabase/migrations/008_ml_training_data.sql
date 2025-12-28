-- ML Training Data Schema
-- Speichert gelabelte Daten für LSTM + XGBoost Training

-- ==================== TRAINING LABELS ====================
-- Jeder Eintrag ist ein gelabelter Datenpunkt für ML Training
CREATE TABLE IF NOT EXISTS ml_training_data (
    id SERIAL PRIMARY KEY,

    -- Referenz zum Original-Log
    analysis_log_id INTEGER,
    coin TEXT NOT NULL,

    -- Timestamp des Snapshots
    snapshot_at TIMESTAMPTZ NOT NULL,

    -- Preis zum Zeitpunkt des Snapshots
    price_at_snapshot DOUBLE PRECISION NOT NULL,

    -- Features (zum Zeitpunkt des Snapshots)
    rsi DOUBLE PRECISION,
    macd DOUBLE PRECISION,
    macd_signal DOUBLE PRECISION,
    ema_12 DOUBLE PRECISION,
    ema_26 DOUBLE PRECISION,
    bb_upper DOUBLE PRECISION,
    bb_lower DOUBLE PRECISION,
    bb_middle DOUBLE PRECISION,
    bb_position DOUBLE PRECISION,  -- 0-1, wo im Band
    volume_24h DOUBLE PRECISION,
    price_change_24h DOUBLE PRECISION,

    -- Outcome (was passierte danach)
    price_after_4h DOUBLE PRECISION,
    price_after_12h DOUBLE PRECISION,
    price_after_24h DOUBLE PRECISION,

    change_4h_percent DOUBLE PRECISION,
    change_12h_percent DOUBLE PRECISION,
    change_24h_percent DOUBLE PRECISION,

    -- Labels für verschiedene Zeitfenster
    label_4h TEXT CHECK (label_4h IN ('STRONG_BUY', 'BUY', 'HOLD', 'SELL', 'STRONG_SELL')),
    label_12h TEXT CHECK (label_12h IN ('STRONG_BUY', 'BUY', 'HOLD', 'SELL', 'STRONG_SELL')),
    label_24h TEXT CHECK (label_24h IN ('STRONG_BUY', 'BUY', 'HOLD', 'SELL', 'STRONG_SELL')),

    -- Numerische Labels für Training (0=STRONG_SELL, 0.25=SELL, 0.5=HOLD, 0.75=BUY, 1=STRONG_BUY)
    label_4h_numeric DOUBLE PRECISION,
    label_12h_numeric DOUBLE PRECISION,
    label_24h_numeric DOUBLE PRECISION,

    -- Meta
    labeled_at TIMESTAMPTZ DEFAULT NOW(),
    is_valid BOOLEAN DEFAULT true,  -- False wenn Daten fehlen

    UNIQUE(coin, snapshot_at)
);

CREATE INDEX idx_ml_training_coin ON ml_training_data(coin);
CREATE INDEX idx_ml_training_snapshot ON ml_training_data(snapshot_at);
CREATE INDEX idx_ml_training_label ON ml_training_data(label_24h);

-- ==================== TRAINING STATS ====================
-- Übersicht über verfügbare Trainingsdaten
CREATE OR REPLACE VIEW ml_training_stats AS
SELECT
    COUNT(*) as total_samples,
    COUNT(*) FILTER (WHERE label_24h IS NOT NULL) as labeled_samples,
    COUNT(*) FILTER (WHERE label_24h = 'STRONG_BUY') as strong_buy_count,
    COUNT(*) FILTER (WHERE label_24h = 'BUY') as buy_count,
    COUNT(*) FILTER (WHERE label_24h = 'HOLD') as hold_count,
    COUNT(*) FILTER (WHERE label_24h = 'SELL') as sell_count,
    COUNT(*) FILTER (WHERE label_24h = 'STRONG_SELL') as strong_sell_count,
    MIN(snapshot_at) as earliest_sample,
    MAX(snapshot_at) as latest_sample,
    COUNT(DISTINCT coin) as unique_coins,
    COUNT(DISTINCT DATE(snapshot_at)) as days_of_data
FROM ml_training_data
WHERE is_valid = true;

-- ==================== LABELING FUNCTION ====================
-- Generiert Labels basierend auf Preisänderung
CREATE OR REPLACE FUNCTION generate_label(price_change DOUBLE PRECISION)
RETURNS TEXT AS $$
BEGIN
    IF price_change >= 5.0 THEN
        RETURN 'STRONG_BUY';
    ELSIF price_change >= 2.0 THEN
        RETURN 'BUY';
    ELSIF price_change <= -5.0 THEN
        RETURN 'STRONG_SELL';
    ELSIF price_change <= -2.0 THEN
        RETURN 'SELL';
    ELSE
        RETURN 'HOLD';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Numerischer Label-Wert
CREATE OR REPLACE FUNCTION label_to_numeric(label TEXT)
RETURNS DOUBLE PRECISION AS $$
BEGIN
    CASE label
        WHEN 'STRONG_BUY' THEN RETURN 1.0;
        WHEN 'BUY' THEN RETURN 0.75;
        WHEN 'HOLD' THEN RETURN 0.5;
        WHEN 'SELL' THEN RETURN 0.25;
        WHEN 'STRONG_SELL' THEN RETURN 0.0;
        ELSE RETURN 0.5;
    END CASE;
END;
$$ LANGUAGE plpgsql;

-- ==================== AUTO-LABELING FUNCTION ====================
-- Läuft periodisch und labelt alte Datenpunkte
CREATE OR REPLACE FUNCTION auto_label_training_data()
RETURNS JSON AS $$
DECLARE
    v_labeled_count INTEGER := 0;
    v_skipped_count INTEGER := 0;
    v_record RECORD;
    v_price_4h DOUBLE PRECISION;
    v_price_12h DOUBLE PRECISION;
    v_price_24h DOUBLE PRECISION;
    v_change_4h DOUBLE PRECISION;
    v_change_12h DOUBLE PRECISION;
    v_change_24h DOUBLE PRECISION;
BEGIN
    -- Finde analysis_logs die alt genug sind (>24h) aber noch nicht gelabelt
    FOR v_record IN
        SELECT
            a.id,
            a.coin,
            a.timestamp,
            a.price,
            a.rsi,
            a.macd,
            a.macd_signal,
            a.ema_12,
            a.ema_26,
            a.bb_upper,
            a.bb_lower,
            a.volume_24h,
            a.price_change_24h
        FROM analysis_logs a
        LEFT JOIN ml_training_data t ON t.analysis_log_id = a.id
        WHERE t.id IS NULL
        AND a.timestamp < NOW() - INTERVAL '25 hours'
        AND a.price > 0
        ORDER BY a.timestamp
        LIMIT 1000  -- Batch-Größe
    LOOP
        -- Finde Preis 4h später
        SELECT price INTO v_price_4h
        FROM analysis_logs
        WHERE coin = v_record.coin
        AND timestamp > v_record.timestamp + INTERVAL '3 hours 30 minutes'
        AND timestamp < v_record.timestamp + INTERVAL '4 hours 30 minutes'
        ORDER BY timestamp
        LIMIT 1;

        -- Finde Preis 12h später
        SELECT price INTO v_price_12h
        FROM analysis_logs
        WHERE coin = v_record.coin
        AND timestamp > v_record.timestamp + INTERVAL '11 hours 30 minutes'
        AND timestamp < v_record.timestamp + INTERVAL '12 hours 30 minutes'
        ORDER BY timestamp
        LIMIT 1;

        -- Finde Preis 24h später
        SELECT price INTO v_price_24h
        FROM analysis_logs
        WHERE coin = v_record.coin
        AND timestamp > v_record.timestamp + INTERVAL '23 hours 30 minutes'
        AND timestamp < v_record.timestamp + INTERVAL '24 hours 30 minutes'
        ORDER BY timestamp
        LIMIT 1;

        -- Berechne Änderungen
        v_change_4h := CASE WHEN v_price_4h IS NOT NULL
            THEN ((v_price_4h / v_record.price) - 1) * 100
            ELSE NULL END;
        v_change_12h := CASE WHEN v_price_12h IS NOT NULL
            THEN ((v_price_12h / v_record.price) - 1) * 100
            ELSE NULL END;
        v_change_24h := CASE WHEN v_price_24h IS NOT NULL
            THEN ((v_price_24h / v_record.price) - 1) * 100
            ELSE NULL END;

        -- Insert Training Data
        INSERT INTO ml_training_data (
            analysis_log_id,
            coin,
            snapshot_at,
            price_at_snapshot,
            rsi, macd, macd_signal, ema_12, ema_26,
            bb_upper, bb_lower,
            volume_24h, price_change_24h,
            price_after_4h, price_after_12h, price_after_24h,
            change_4h_percent, change_12h_percent, change_24h_percent,
            label_4h, label_12h, label_24h,
            label_4h_numeric, label_12h_numeric, label_24h_numeric,
            is_valid
        ) VALUES (
            v_record.id,
            v_record.coin,
            v_record.timestamp,
            v_record.price,
            v_record.rsi, v_record.macd, v_record.macd_signal,
            v_record.ema_12, v_record.ema_26,
            v_record.bb_upper, v_record.bb_lower,
            v_record.volume_24h, v_record.price_change_24h,
            v_price_4h, v_price_12h, v_price_24h,
            v_change_4h, v_change_12h, v_change_24h,
            generate_label(v_change_4h),
            generate_label(v_change_12h),
            generate_label(v_change_24h),
            label_to_numeric(generate_label(v_change_4h)),
            label_to_numeric(generate_label(v_change_12h)),
            label_to_numeric(generate_label(v_change_24h)),
            (v_price_24h IS NOT NULL)  -- valid nur wenn 24h Preis vorhanden
        )
        ON CONFLICT (coin, snapshot_at) DO NOTHING;

        IF v_price_24h IS NOT NULL THEN
            v_labeled_count := v_labeled_count + 1;
        ELSE
            v_skipped_count := v_skipped_count + 1;
        END IF;
    END LOOP;

    RETURN json_build_object(
        'labeled', v_labeled_count,
        'skipped', v_skipped_count,
        'timestamp', NOW()
    );
END;
$$ LANGUAGE plpgsql;

-- ==================== EXPORT FOR TRAINING ====================
-- Gibt Trainingsdaten im Format für Python zurück
CREATE OR REPLACE FUNCTION export_training_data(p_limit INTEGER DEFAULT 10000)
RETURNS TABLE (
    coin TEXT,
    features JSONB,
    label_numeric DOUBLE PRECISION,
    label_text TEXT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        t.coin,
        jsonb_build_object(
            'rsi', t.rsi,
            'macd', t.macd,
            'macd_signal', t.macd_signal,
            'ema_12', t.ema_12,
            'ema_26', t.ema_26,
            'bb_upper', t.bb_upper,
            'bb_lower', t.bb_lower,
            'bb_position', CASE
                WHEN t.bb_upper IS NOT NULL AND t.bb_lower IS NOT NULL
                    AND t.bb_upper != t.bb_lower
                THEN (t.price_at_snapshot - t.bb_lower) / (t.bb_upper - t.bb_lower)
                ELSE 0.5
            END,
            'volume_24h', t.volume_24h,
            'price_change_24h', t.price_change_24h
        ) as features,
        t.label_24h_numeric,
        t.label_24h
    FROM ml_training_data t
    WHERE t.is_valid = true
    AND t.label_24h IS NOT NULL
    ORDER BY t.snapshot_at DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql;

-- ==================== PERMISSIONS ====================
GRANT ALL ON ml_training_data TO service_role;
GRANT SELECT ON ml_training_data TO anon;
GRANT SELECT ON ml_training_stats TO anon;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO service_role;
