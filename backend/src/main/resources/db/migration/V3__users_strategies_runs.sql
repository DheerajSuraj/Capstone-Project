-- ═══════════════════════════════════════════════════════════════════════
-- V3: Identity, strategies, immutable versions, persisted runs.
--
--  * strategy_versions is APPEND-ONLY, enforced by a trigger — history a
--    competition result points at can never be rewritten, not even by a
--    bug or a rogue psql session. The JPA entity is @Immutable on top.
--  * Every version stored here COMPILED CLEANLY at save time (the service
--    refuses otherwise), so "runnable" is an invariant of the table.
--  * Runs keep headline stats as COLUMNS (sortable/filterable — the
--    competition leaderboard will ORDER BY these) and bulk payloads
--    (trades, equity curve) as JSONB written once and read whole.
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE users (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(50)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Until the auth phase (roadmap Phase 4) every request acts as this user.
INSERT INTO users (username) VALUES ('dev');

CREATE TABLE strategies (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_strategies_user ON strategies(user_id);

CREATE TABLE strategy_versions (
    id             BIGSERIAL PRIMARY KEY,
    strategy_id    BIGINT      NOT NULL REFERENCES strategies(id),
    version_number INTEGER     NOT NULL,
    source         TEXT        NOT NULL,
    -- Denormalized compile facts, so lists render without recompiling.
    symbol         VARCHAR(20) NOT NULL,
    timeframe      VARCHAR(5)  NOT NULL,
    warmup_bars    INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_strategy_version UNIQUE (strategy_id, version_number)
);

-- The append-only lock. RAISE aborts the offending statement.
CREATE FUNCTION forbid_version_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'strategy_versions is append-only (attempted %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_versions_append_only
    BEFORE UPDATE OR DELETE ON strategy_versions
    FOR EACH ROW EXECUTE FUNCTION forbid_version_mutation();

CREATE TABLE backtest_runs (
    id                  BIGSERIAL PRIMARY KEY,
    strategy_version_id BIGINT      NOT NULL REFERENCES strategy_versions(id),
    from_time           TIMESTAMPTZ,
    to_time             TIMESTAMPTZ,
    -- Headline stats: real columns, because leaderboards sort on them.
    final_equity        NUMERIC(20, 8) NOT NULL,
    total_return_pct    DOUBLE PRECISION NOT NULL,
    max_drawdown_pct    DOUBLE PRECISION NOT NULL,
    win_rate            DOUBLE PRECISION NOT NULL,
    trade_count         INTEGER     NOT NULL,
    total_fees          NUMERIC(20, 8) NOT NULL,
    sharpe_ratio        DOUBLE PRECISION,   -- nullable: undefined is NULL, never 0
    sortino_ratio       DOUBLE PRECISION,
    profit_factor       DOUBLE PRECISION,
    -- Bulk payloads: written once, read whole, never queried relationally.
    trades              JSONB       NOT NULL,
    equity_curve        JSONB       NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_runs_version ON backtest_runs(strategy_version_id);
