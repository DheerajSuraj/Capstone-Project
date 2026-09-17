-- ═══════════════════════════════════════════════════════════════════════
-- V5: Column types V4 got wrong.
--
-- Hibernate runs with ddl-auto=validate, so a String field mapped with
-- @Column(length = n) must be varchar(n) — not char(n), which Postgres
-- reports as bpchar, and not text.
--
-- varchar is the better choice regardless: char(n) pads every value to the
-- full width and buys nothing, which the Postgres manual says outright.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE refresh_tokens
    ALTER COLUMN token_hash TYPE VARCHAR(64);

ALTER TABLE users
    ALTER COLUMN avatar_url TYPE VARCHAR(512);