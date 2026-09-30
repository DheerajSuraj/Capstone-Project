-- ═══════════════════════════════════════════════════════════════════════
-- V9: Paper trading can go SHORT, TradingView-style.
--
-- A position now has a side (LONG / SHORT), an average price (fees NOT
-- included) and the fees paid to open it, which are charged when it closes.
-- The old "cost" column (price and fee mixed together) is converted and
-- dropped.
--
-- Cash may now dip below zero in an extreme loss: buying back a short that
-- went badly wrong must always be possible, so the old CHECK is removed.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE paper_positions
    ADD COLUMN side       VARCHAR(5)     NOT NULL DEFAULT 'LONG' CHECK (side IN ('LONG', 'SHORT')),
    ADD COLUMN avg_price  NUMERIC(20, 8),
    ADD COLUMN entry_fees NUMERIC(20, 8) NOT NULL DEFAULT 0;

-- Existing rows are longs whose cost = qty × price × 1.001.
UPDATE paper_positions
   SET avg_price  = ROUND(cost / qty / 1.001, 8),
       entry_fees = cost - ROUND(cost / qty / 1.001, 8) * qty;

ALTER TABLE paper_positions ALTER COLUMN avg_price SET NOT NULL;
ALTER TABLE paper_positions DROP COLUMN cost;

ALTER TABLE paper_accounts DROP CONSTRAINT IF EXISTS paper_accounts_cash_check;
