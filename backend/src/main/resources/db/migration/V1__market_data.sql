-- ═══════════════════════════════════════════════════════════════════════
-- V1: Market data foundation — symbols, candles, frozen datasets.
--
-- Design notes (see docs/adr/0002 for the full reasoning):
--
--  * CANDLES ARE NOT A JPA ENTITY. This table is written by bulk COPY and
--    read by plain JdbcTemplate into primitive double[] columns. Hibernate
--    would allocate ~900k row objects for data the engine wants as arrays.
--    Use the ORM where it helps; bypass it where it hurts.
--
--  * PARTITIONED BY symbol_id. Every query filters by symbol, so LIST
--    partitioning lets Postgres touch one partition instead of scanning the
--    whole table. Partitions are created per symbol at ingestion time.
--
--  * DATASETS ARE FROZEN AND CHECKSUMMED. A competition pins a dataset_id;
--    the checksum proves the candles underneath a published leaderboard
--    were never altered. Without this, fairness claims are unverifiable.
-- ═══════════════════════════════════════════════════════════════════════


-- ── Symbols ────────────────────────────────────────────────────────────
-- The tradeable instruments we hold data for. Small, stable, read-mostly:
-- this one IS a normal JPA entity.
CREATE TABLE symbols (
    id           BIGSERIAL PRIMARY KEY,
    ticker       VARCHAR(20)  NOT NULL UNIQUE,   -- 'BTCUSDT'
    base_asset   VARCHAR(10)  NOT NULL,          -- 'BTC'
    quote_asset  VARCHAR(10)  NOT NULL,          -- 'USDT'
    exchange     VARCHAR(20)  NOT NULL DEFAULT 'BINANCE',
    -- Exchange lot/tick rules; the fill model rounds order sizes to these
    -- so backtested trades are actually placeable in reality.
    tick_size    NUMERIC(20, 10) NOT NULL,
    step_size    NUMERIC(20, 10) NOT NULL,
    min_notional NUMERIC(20, 10) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE symbols IS
    'Tradeable instruments. Mapped as a JPA entity (small, rich, read-mostly).';
COMMENT ON COLUMN symbols.step_size IS
    'Minimum order quantity increment; the fill model rounds down to this.';


-- ── Candles ────────────────────────────────────────────────────────────
-- OHLCV bars. The hot path: bulk-loaded, read as columns, never ORM-mapped.
--
-- open_time is the bar's OPEN timestamp, always UTC, always the exact
-- multiple of the timeframe (Binance guarantees this). Storing open_time
-- rather than close_time avoids an entire class of off-by-one-bar bugs:
-- "the bar at 14:00" is unambiguous.
CREATE TABLE candles (
    symbol_id   BIGINT       NOT NULL REFERENCES symbols(id),
    timeframe   VARCHAR(5)   NOT NULL,           -- '1h', '4h'
    open_time   TIMESTAMPTZ  NOT NULL,
    open        DOUBLE PRECISION NOT NULL,
    high        DOUBLE PRECISION NOT NULL,
    low         DOUBLE PRECISION NOT NULL,
    close       DOUBLE PRECISION NOT NULL,
    volume      DOUBLE PRECISION NOT NULL,
    -- The PK is also the natural read order: one symbol+timeframe's bars in
    -- chronological order is exactly what the engine streams.
    PRIMARY KEY (symbol_id, timeframe, open_time)
) PARTITION BY LIST (symbol_id);

COMMENT ON TABLE candles IS
    'OHLCV bars. Deliberately NOT a JPA entity: bulk COPY in, JdbcTemplate '
    'out into primitive arrays. Partitioned by symbol_id.';
COMMENT ON COLUMN candles.open_time IS
    'Bar OPEN time, UTC. Using open_time (not close_time) makes "the 14:00 '
    'bar" unambiguous and avoids off-by-one-bar errors.';

-- DOUBLE PRECISION, not NUMERIC: the execution engine computes in double[]
-- anyway (roadmap §8), so storing NUMERIC would just add a conversion per
-- value. Crypto prices are far inside double's exact-integer range and
-- indicator maths is inherently approximate; NUMERIC's exactness would be
-- a false promise here. Money columns elsewhere (equity, PnL) use NUMERIC.

-- Default partition: catches rows for any symbol whose partition wasn't
-- created yet. It should stay EMPTY — the ingestion tool creates a proper
-- partition per symbol. A non-empty default partition means a bug.
CREATE TABLE candles_default PARTITION OF candles DEFAULT;


-- ── Datasets ───────────────────────────────────────────────────────────
-- A frozen, checksummed slice of candles. Competitions pin one of these so
-- every submission is evaluated on provably identical data.
CREATE TABLE datasets (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    symbol_id   BIGINT       NOT NULL REFERENCES symbols(id),
    timeframe   VARCHAR(5)   NOT NULL,
    start_time  TIMESTAMPTZ  NOT NULL,
    end_time    TIMESTAMPTZ  NOT NULL,
    bar_count   INTEGER      NOT NULL,
    -- SHA-256 over the candle bytes in canonical order. Recomputed at
    -- evaluation time and compared; a mismatch aborts the evaluation.
    checksum    CHAR(64)     NOT NULL,
    frozen_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT datasets_range_valid CHECK (end_time > start_time),
    CONSTRAINT datasets_bars_positive CHECK (bar_count > 0)
);

COMMENT ON TABLE datasets IS
    'Frozen candle slices for reproducible backtests and fair competitions. '
    'The checksum is verified before every competition evaluation.';
COMMENT ON COLUMN datasets.checksum IS
    'SHA-256 over canonical candle bytes. Proves the data behind a '
    'published leaderboard was never altered.';

CREATE INDEX idx_datasets_symbol_tf ON datasets(symbol_id, timeframe);


-- ── Ingestion audit ────────────────────────────────────────────────────
-- What we loaded, from where, when. Makes ingestion idempotent (skip files
-- already loaded) and reproducible (the report can state exactly which
-- source archives produced the evaluation data).
CREATE TABLE ingestion_log (
    id            BIGSERIAL PRIMARY KEY,
    symbol_id     BIGINT      NOT NULL REFERENCES symbols(id),
    timeframe     VARCHAR(5)  NOT NULL,
    source_file   VARCHAR(200) NOT NULL,   -- 'BTCUSDT-1h-2024-01.zip'
    source_url    VARCHAR(500),
    rows_inserted INTEGER     NOT NULL,
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT ingestion_log_unique_file UNIQUE (symbol_id, timeframe, source_file)
);

COMMENT ON TABLE ingestion_log IS
    'Idempotency + provenance: which archive produced which rows, and when.';
