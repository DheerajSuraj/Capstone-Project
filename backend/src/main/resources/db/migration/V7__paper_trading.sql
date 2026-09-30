-- ═══════════════════════════════════════════════════════════════════════
-- V7: Paper trading — buy and sell on the live chart with fake money.
--
--  * One account per user, created on first use with 10,000 USDT.
--  * Spot only, like the engine: you can sell what you hold, never short.
--  * Every fill happens on the SERVER at the server's own live price
--    (LivePriceFeed). The browser never supplies a fill price.
--  * Money is NUMERIC: this is a ledger people read, not engine maths.
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE paper_accounts (
    user_id       BIGINT PRIMARY KEY REFERENCES users(id),
    starting_cash NUMERIC(20, 8) NOT NULL,
    cash          NUMERIC(20, 8) NOT NULL CHECK (cash >= 0),
    realized_pnl  NUMERIC(20, 8) NOT NULL DEFAULT 0,
    fees_paid     NUMERIC(20, 8) NOT NULL DEFAULT 0,
    resets        INTEGER        NOT NULL DEFAULT 0,
    reset_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE paper_positions (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT         NOT NULL REFERENCES users(id),
    symbol_id  BIGINT         NOT NULL REFERENCES symbols(id),
    qty        NUMERIC(30, 12) NOT NULL CHECK (qty > 0),
    -- What the position cost, buy fees included. Average cost = cost / qty,
    -- which makes realized PnL net of every fee.
    cost       NUMERIC(20, 8)  NOT NULL,
    opened_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_paper_position UNIQUE (user_id, symbol_id)
);

CREATE TABLE paper_orders (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT          NOT NULL REFERENCES users(id),
    symbol_id     BIGINT          NOT NULL REFERENCES symbols(id),
    side          VARCHAR(4)      NOT NULL CHECK (side IN ('BUY', 'SELL')),
    type          VARCHAR(6)      NOT NULL CHECK (type IN ('MARKET', 'LIMIT')),
    qty           NUMERIC(30, 12) NOT NULL CHECK (qty > 0),
    limit_price   NUMERIC(20, 8),
    status        VARCHAR(10)     NOT NULL
                  CHECK (status IN ('OPEN', 'FILLED', 'CANCELLED', 'REJECTED')),
    fill_price    NUMERIC(20, 8),
    fee           NUMERIC(20, 8),
    realized_pnl  NUMERIC(20, 8),
    reject_reason VARCHAR(200),
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    filled_at     TIMESTAMPTZ,
    cancelled_at  TIMESTAMPTZ,
    CONSTRAINT paper_limit_has_price CHECK (type = 'MARKET' OR limit_price > 0)
);

CREATE INDEX idx_paper_orders_user   ON paper_orders(user_id, created_at DESC);
CREATE INDEX idx_paper_orders_open   ON paper_orders(status) WHERE status = 'OPEN';
