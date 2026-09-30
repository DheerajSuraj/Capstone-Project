-- ═══════════════════════════════════════════════════════════════════════
-- V6: Competitions — rule-based challenges run candle by candle.
--
-- Follows the competition sequence diagram:
--   Setup  : an admin saves a rule set and opens entries.
--   Join   : traders submit strategies; each entry is locked with a hash of
--            its source and a timestamp.
--   Run    : on every new closed candle, each entry's state is loaded,
--            advanced one candle under the rules, and saved in one write.
--   End    : entries are marked PASSED / FAILED and ranked.
--
-- Fairness is enforced HERE, in the database, not just in Java — the same
-- approach as the append-only strategy_versions trigger in V3:
--   * the rule set cannot change once saved;
--   * an entry's identity (who, which strategy version, its hash, when)
--     can never change, and entries cannot be deleted;
--   * entries can only be inserted while the competition is OPEN and
--     before it starts.
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE competitions (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    description          VARCHAR(1000) NOT NULL DEFAULT '',
    created_by           BIGINT       NOT NULL REFERENCES users(id),
    symbol_id            BIGINT       NOT NULL REFERENCES symbols(id),
    timeframe            VARCHAR(5)   NOT NULL,

    -- ── The rule set (locked by trigger) ───────────────────────────────
    starting_capital     DOUBLE PRECISION NOT NULL CHECK (starting_capital > 0),
    fee_fraction         DOUBLE PRECISION NOT NULL CHECK (fee_fraction >= 0 AND fee_fraction <= 0.05),
    profit_target_pct    DOUBLE PRECISION NOT NULL CHECK (profit_target_pct > 0),
    max_drawdown_pct     DOUBLE PRECISION NOT NULL CHECK (max_drawdown_pct > 0 AND max_drawdown_pct <= 100),
    daily_loss_limit_pct DOUBLE PRECISION NOT NULL DEFAULT 0 CHECK (daily_loss_limit_pct >= 0),
    max_trades_per_day   INTEGER      NOT NULL DEFAULT 0 CHECK (max_trades_per_day >= 0),
    min_trading_days     INTEGER      NOT NULL DEFAULT 0 CHECK (min_trading_days >= 0),
    max_entries_per_user INTEGER      NOT NULL DEFAULT 3 CHECK (max_entries_per_user > 0),
    starts_at            TIMESTAMPTZ  NOT NULL,
    ends_at              TIMESTAMPTZ  NOT NULL,

    -- ── Lifecycle (moves; not part of the rule set) ────────────────────
    status               VARCHAR(12)  NOT NULL DEFAULT 'OPEN'
                         CHECK (status IN ('OPEN', 'RUNNING', 'FINISHED', 'CANCELLED')),
    -- First candle loaded for indicator warm-up. Fixed when the competition
    -- starts, so every candle is evaluated over the same history.
    data_start           TIMESTAMPTZ,
    last_candle          TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT competitions_window CHECK (ends_at > starts_at)
);

CREATE INDEX idx_competitions_status ON competitions(status);

-- The rule set is frozen. Only lifecycle columns may change.
CREATE FUNCTION forbid_rule_change() RETURNS trigger AS $$
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
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'competition % rule set is locked', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_competition_rules_locked
    BEFORE UPDATE ON competitions
    FOR EACH ROW EXECUTE FUNCTION forbid_rule_change();

CREATE FUNCTION forbid_competition_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'competitions are never deleted (cancel instead)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_competitions_no_delete
    BEFORE DELETE ON competitions
    FOR EACH ROW EXECUTE FUNCTION forbid_competition_delete();


-- ── Entries ─────────────────────────────────────────────────────────────
-- One row per submitted strategy. Identity columns are locked forever; the
-- state columns are the engine's memory between candles.
CREATE TABLE competition_entries (
    id                  BIGSERIAL PRIMARY KEY,
    competition_id      BIGINT      NOT NULL REFERENCES competitions(id),
    user_id             BIGINT      NOT NULL REFERENCES users(id),
    strategy_version_id BIGINT      NOT NULL REFERENCES strategy_versions(id),
    -- SHA-256 of the version's source at submission. Checked before every
    -- candle: the strategy that trades is provably the one submitted.
    source_hash         VARCHAR(64) NOT NULL,
    submitted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- ── Engine state (double, like the engine) ────────────────────────
    status              VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'ELIMINATED', 'PASSED', 'FAILED')),
    status_reason       VARCHAR(300),
    status_candle       TIMESTAMPTZ,
    cash                DOUBLE PRECISION NOT NULL,
    qty                 DOUBLE PRECISION NOT NULL DEFAULT 0,
    entry_price         DOUBLE PRECISION NOT NULL DEFAULT 0,
    entry_time          TIMESTAMPTZ,
    entry_fees          DOUBLE PRECISION NOT NULL DEFAULT 0,
    peak_since_entry    DOUBLE PRECISION NOT NULL DEFAULT 0,
    stop_pct            DOUBLE PRECISION NOT NULL DEFAULT 0,
    take_profit_pct     DOUBLE PRECISION NOT NULL DEFAULT 0,
    trailing_pct        DOUBLE PRECISION NOT NULL DEFAULT 0,
    pending_orders      VARCHAR(200) NOT NULL DEFAULT '',
    equity              DOUBLE PRECISION NOT NULL,
    peak_equity         DOUBLE PRECISION NOT NULL,
    max_drawdown_seen   DOUBLE PRECISION NOT NULL DEFAULT 0,
    current_day         BIGINT,          -- epoch day (UTC) of the last candle
    day_start_equity    DOUBLE PRECISION NOT NULL,
    trades_today        INTEGER     NOT NULL DEFAULT 0,
    traded_today        BOOLEAN     NOT NULL DEFAULT FALSE,
    halted_today        BOOLEAN     NOT NULL DEFAULT FALSE,
    halt_reason         VARCHAR(200),
    trading_days        INTEGER     NOT NULL DEFAULT 0,
    trade_count         INTEGER     NOT NULL DEFAULT 0,
    last_candle         TIMESTAMPTZ,
    -- ── Standing ───────────────────────────────────────────────────────
    rank_position       INTEGER,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_entries_competition ON competition_entries(competition_id);
CREATE INDEX idx_entries_user        ON competition_entries(user_id);

CREATE FUNCTION guard_competition_entry() RETURNS trigger AS $$
DECLARE
    c_status TEXT;
    c_start  TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'competition entries are locked and cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT status, starts_at INTO c_status, c_start
          FROM competitions WHERE id = NEW.competition_id;
        IF c_status <> 'OPEN' OR NOW() >= c_start THEN
            RAISE EXCEPTION 'the entry window for competition % is closed', NEW.competition_id;
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: the engine may move state, never identity.
    IF NEW.competition_id IS DISTINCT FROM OLD.competition_id
       OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.strategy_version_id IS DISTINCT FROM OLD.strategy_version_id
       OR NEW.source_hash IS DISTINCT FROM OLD.source_hash
       OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at THEN
        RAISE EXCEPTION 'entry % is locked', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_competition_entries_guard
    BEFORE INSERT OR UPDATE OR DELETE ON competition_entries
    FOR EACH ROW EXECUTE FUNCTION guard_competition_entry();


-- ── Trades ──────────────────────────────────────────────────────────────
-- "save the open trade" inserts a row with no exit; "save the closed
-- trade" fills the exit in. Nothing else ever changes a trade.
CREATE TABLE competition_trades (
    id           BIGSERIAL PRIMARY KEY,
    entry_id     BIGINT      NOT NULL REFERENCES competition_entries(id),
    entry_time   TIMESTAMPTZ NOT NULL,
    entry_price  DOUBLE PRECISION NOT NULL,
    qty          DOUBLE PRECISION NOT NULL,
    exit_time    TIMESTAMPTZ,
    exit_price   DOUBLE PRECISION,
    fees         DOUBLE PRECISION,
    pnl          DOUBLE PRECISION,
    exit_reason  VARCHAR(20)
);

CREATE INDEX idx_competition_trades_entry ON competition_trades(entry_id);
