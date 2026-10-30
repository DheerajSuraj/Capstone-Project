-- ═══════════════════════════════════════════════════════════════════════
-- V11: Prop-firm style rules for competitions.
--
-- Three choices that bring a competition close to a real funding challenge:
--   daily_loss_action : HALT — hitting the daily loss limit stops new trades
--                       until the next UTC day (what TSB always did);
--                       FAIL — it ends the entry, as a prop firm closes the
--                       account.
--   drawdown_mode     : TRAILING — the max drawdown is measured from the best
--                       equity reached (what TSB always did);
--                       STATIC — from the starting capital: a fixed floor.
--   pass_on_target    : true — an entry passes at the first close where the
--                       target (after the exit fee) and the minimum trading
--                       days are met, instead of being judged at the end.
--
-- Existing competitions keep exactly the rules they were created with: the
-- defaults are the old behaviour. The new columns are part of the locked
-- rule set, so the trigger from V6 is replaced to cover them too.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE competitions
    ADD COLUMN daily_loss_action VARCHAR(4) NOT NULL DEFAULT 'HALT'
        CHECK (daily_loss_action IN ('HALT', 'FAIL')),
    ADD COLUMN drawdown_mode VARCHAR(8) NOT NULL DEFAULT 'TRAILING'
        CHECK (drawdown_mode IN ('TRAILING', 'STATIC')),
    ADD COLUMN pass_on_target BOOLEAN NOT NULL DEFAULT FALSE;

CREATE OR REPLACE FUNCTION forbid_rule_change() RETURNS trigger AS $$
BEGIN
    IF NEW.name IS DISTINCT FROM OLD.name
       OR NEW.symbol_id IS DISTINCT FROM OLD.symbol_id
       OR NEW.timeframe IS DISTINCT FROM OLD.timeframe
       OR NEW.starting_capital IS DISTINCT FROM OLD.starting_capital
       OR NEW.fee_fraction IS DISTINCT FROM OLD.fee_fraction
       OR NEW.profit_target_pct IS DISTINCT FROM OLD.profit_target_pct
       OR NEW.max_drawdown_pct IS DISTINCT FROM OLD.max_drawdown_pct
       OR NEW.daily_loss_limit_pct IS DISTINCT FROM OLD.daily_loss_limit_pct
       OR NEW.max_trades_per_day IS DISTINCT FROM OLD.max_trades_per_day
       OR NEW.min_trading_days IS DISTINCT FROM OLD.min_trading_days
       OR NEW.max_entries_per_user IS DISTINCT FROM OLD.max_entries_per_user
       OR NEW.starts_at IS DISTINCT FROM OLD.starts_at
       OR NEW.ends_at IS DISTINCT FROM OLD.ends_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.daily_loss_action IS DISTINCT FROM OLD.daily_loss_action
       OR NEW.drawdown_mode IS DISTINCT FROM OLD.drawdown_mode
       OR NEW.pass_on_target IS DISTINCT FROM OLD.pass_on_target THEN
        RAISE EXCEPTION 'competition % rule set is locked', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
