-- ═══════════════════════════════════════════════════════════════════════
-- V4: Authentication.
--
-- V3 already created `users` (id, username, created_at) and seeded one row,
-- 'dev', that every strategy made during development points at. This
-- migration ALTERs onto that table rather than replacing it, so nothing
-- created so far is orphaned.
--
-- After this runs, 'dev' has no email, no password and no Google subject,
-- which means it can no longer sign in — it survives only as the owner of
-- old rows. That is deliberate: a development account that can still
-- authenticate in production is a back door.
-- ═══════════════════════════════════════════════════════════════════════

ALTER TABLE users
    ADD COLUMN email         VARCHAR(255),
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN google_sub    VARCHAR(255),
    ADD COLUMN display_name  VARCHAR(100),
    ADD COLUMN avatar_url    TEXT,
    ADD COLUMN updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Every one of those is nullable on purpose:
--   * a Google user never has a password_hash;
--   * a password user never has a google_sub;
--   * the seeded 'dev' user has none of them.
-- Postgres permits many NULLs inside a UNIQUE index, so the indexes below
-- still do their job.

-- ── Case-insensitive identity ──────────────────────────────────────────
-- V3's plain UNIQUE on username is case-SENSITIVE, so 'juan' and 'Juan'
-- could both exist and sit next to each other on a competition
-- leaderboard. That is not a naming quirk, it is impersonation. The same
-- argument applies to email: nobody expects Juan@x.com and juan@x.com to
-- be two accounts.
CREATE UNIQUE INDEX uq_users_username_lower ON users (LOWER(username));
CREATE UNIQUE INDEX uq_users_email_lower    ON users (LOWER(email));
CREATE UNIQUE INDEX uq_users_google_sub     ON users (google_sub);

-- The username rule stated once more where it cannot be bypassed — not by
-- a bad client, not by a future endpoint that forgets to validate.
-- ('dev' is three characters, so the existing row passes.)
ALTER TABLE users
    ADD CONSTRAINT ck_users_username_shape
    CHECK (username ~ '^[A-Za-z0-9_]{3,20}$');

COMMENT ON COLUMN users.password_hash IS
    'BCrypt. NULL means this account signs in with Google only.';
COMMENT ON COLUMN users.google_sub IS
    'Google''s stable subject claim. Never the email — Google addresses can be reassigned, subjects cannot.';


-- ── Refresh tokens ─────────────────────────────────────────────────────
-- Access tokens are short-lived JWTs held in browser memory and are not
-- stored anywhere. Refresh tokens are opaque, long-lived, and live in an
-- httpOnly cookie, so they need a home here.
CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- SHA-256 of the token, hex. The token itself is never written down,
    -- so a dump of this table does not let the reader sign in as anyone.
    token_hash CHAR(64)    NOT NULL UNIQUE,

    -- Every token descended from one sign-in shares a family id. Tokens
    -- rotate on each use: presenting one that has already been spent means
    -- either it leaked or the real client is replaying, and there is no way
    -- to tell which from here. So the whole family is revoked and that
    -- session ends. This is the standard reuse-detection rule from the
    -- OAuth 2.0 browser-based-apps BCP.
    family_id  UUID        NOT NULL,

    issued_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_refresh_user    ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_family  ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_expires ON refresh_tokens(expires_at);
