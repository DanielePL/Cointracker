-- Migration: Add SHORT trading support to execute_bot_trade function
-- This allows the bot to open SHORT positions (profit when price falls)

-- First, update bot_trades to accept SHORT and COVER sides
ALTER TABLE bot_trades DROP CONSTRAINT IF EXISTS bot_trades_side_check;
ALTER TABLE bot_trades ADD CONSTRAINT bot_trades_side_check
    CHECK (side IN ('BUY', 'SELL', 'SHORT', 'COVER'));

-- Update execute_bot_trade function to handle SHORT trades
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
        -- LONG: Check if we have enough balance
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

        -- Update or insert position (LONG)
        INSERT INTO bot_positions (
            coin, side, quantity, entry_price, current_price, total_invested,
            stop_loss, take_profit, signal_score, entry_signal
        ) VALUES (
            p_coin, 'LONG', p_quantity, p_price, p_price, v_total_value,
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
            'direction', 'LONG',
            'coin', p_coin,
            'quantity', p_quantity,
            'price', p_price,
            'total_value', v_total_value,
            'new_balance', v_balance - v_total_value
        );

    ELSIF p_side = 'SHORT' THEN
        -- SHORT: Open a short position (profit when price falls)
        -- Check if we have enough balance for margin (same as long for paper trading)
        IF v_balance < v_total_value THEN
            RETURN json_build_object('success', false, 'error', 'Insufficient balance for short margin');
        END IF;

        -- Insert trade
        INSERT INTO bot_trades (
            coin, side, quantity, entry_price, total_value,
            signal_type, signal_score, signal_reasons, rsi, macd,
            status, balance_before
        ) VALUES (
            p_coin, 'SHORT', p_quantity, p_price, v_total_value,
            p_signal_type, p_signal_score, p_signal_reasons, p_rsi, p_macd,
            'OPEN', v_balance
        ) RETURNING id INTO v_trade_id;

        -- Insert SHORT position
        INSERT INTO bot_positions (
            coin, side, quantity, entry_price, current_price, total_invested,
            stop_loss, take_profit, signal_score, entry_signal
        ) VALUES (
            p_coin, 'SHORT', p_quantity, p_price, p_price, v_total_value,
            p_stop_loss, p_take_profit, p_signal_score, p_signal_type
        )
        ON CONFLICT (coin) DO UPDATE SET
            quantity = bot_positions.quantity + p_quantity,
            total_invested = bot_positions.total_invested + v_total_value,
            entry_price = (bot_positions.total_invested + v_total_value) /
                         (bot_positions.quantity + p_quantity),
            side = 'SHORT',  -- Ensure direction is SHORT
            updated_at = NOW();

        -- Deduct margin from balance (for paper trading, treat like a buy)
        UPDATE bot_balance SET
            balance_usdt = balance_usdt - v_total_value,
            total_trades = total_trades + 1,
            updated_at = NOW();

        RETURN json_build_object(
            'success', true,
            'trade_id', v_trade_id,
            'action', 'SHORT',
            'direction', 'SHORT',
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

        -- Calculate PnL based on position direction
        IF v_position.side = 'SHORT' THEN
            -- SHORT: Profit when price falls (entry - exit)
            v_pnl := (v_position.entry_price - p_price) * p_quantity;
            v_pnl_percent := ((v_position.entry_price / p_price) - 1) * 100;
        ELSE
            -- LONG: Profit when price rises (exit - entry)
            v_pnl := (p_price - v_position.entry_price) * p_quantity;
            v_pnl_percent := ((p_price / v_position.entry_price) - 1) * 100;
        END IF;

        -- Insert trade
        INSERT INTO bot_trades (
            coin, side, quantity, entry_price, exit_price, total_value,
            pnl, pnl_percent, signal_type, signal_score, signal_reasons,
            rsi, macd, status, close_reason, balance_before, closed_at
        ) VALUES (
            p_coin,
            CASE WHEN v_position.side = 'SHORT' THEN 'COVER' ELSE 'SELL' END,
            p_quantity, v_position.entry_price, p_price, v_total_value,
            v_pnl, v_pnl_percent, p_signal_type, p_signal_score, p_signal_reasons,
            p_rsi, p_macd, 'CLOSED', 'SIGNAL', v_balance, NOW()
        ) RETURNING id INTO v_trade_id;

        -- Update trade with balance after
        UPDATE bot_trades SET balance_after = v_balance + v_total_value + v_pnl WHERE id = v_trade_id;

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
        -- For shorts: return margin + pnl, for longs: return sale value
        UPDATE bot_balance SET
            balance_usdt = balance_usdt + v_total_value + v_pnl,
            total_trades = total_trades + 1,
            total_pnl = total_pnl + v_pnl,
            total_pnl_percent = ((balance_usdt + v_total_value + v_pnl - initial_balance) / initial_balance) * 100,
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
            json_build_object('rsi', p_rsi, 'macd', p_macd, 'direction', v_position.side)
        );

        RETURN json_build_object(
            'success', true,
            'trade_id', v_trade_id,
            'action', CASE WHEN v_position.side = 'SHORT' THEN 'COVER' ELSE 'SELL' END,
            'direction', v_position.side,
            'coin', p_coin,
            'quantity', p_quantity,
            'entry_price', v_position.entry_price,
            'exit_price', p_price,
            'pnl', v_pnl,
            'pnl_percent', v_pnl_percent,
            'new_balance', v_balance + v_total_value + v_pnl
        );
    END IF;

    RETURN json_build_object('success', false, 'error', 'Invalid side: ' || p_side);
END;
$$ LANGUAGE plpgsql;

-- Add comment
COMMENT ON FUNCTION execute_bot_trade IS 'Execute a bot trade - supports BUY (long), SHORT, and SELL (close position)';
