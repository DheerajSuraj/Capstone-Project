-- ═══════════════════════════════════════════════════════════════════════
-- V2: Seed the MVP symbols.
--
-- Reference data belongs in a migration: every environment (dev machines,
-- CI, the VM) gets identical symbols with identical ids, and the change is
-- versioned like code. tick/step/min_notional are Binance's spot filters
-- as commonly published for these pairs — good enough for a simulated fill
-- model; a production system would sync them from exchangeInfo.
-- ═══════════════════════════════════════════════════════════════════════

INSERT INTO symbols (ticker, base_asset, quote_asset, tick_size, step_size, min_notional)
VALUES
    ('BTCUSDT', 'BTC', 'USDT', 0.01, 0.00001, 5.0),
    ('ETHUSDT', 'ETH', 'USDT', 0.01, 0.0001,  5.0),
    ('SOLUSDT', 'SOL', 'USDT', 0.01, 0.001,   5.0);