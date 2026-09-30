-- ═══════════════════════════════════════════════════════════════════════
-- V8: Forum — posts with pictures, chart snapshots and shared strategies,
-- comments, likes, and reports.
--
--  * Reading is public; writing needs a signed-in user.
--  * Sharing a strategy points at ONE strategy version. Versions are
--    append-only (V3 trigger), so what a post shares can never change
--    underneath it; later edits the author makes stay private.
--  * Its backtest results are snapshotted when the post is made, so every
--    reader sees the same numbers the author did.
--  * Pictures are stored only after ImageSanitizer has re-drawn them.
--  * Deleting is soft (deleted_at): a thread keeps its shape, a moderator
--    can still see what was removed.
-- ═══════════════════════════════════════════════════════════════════════

CREATE TABLE forum_posts (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT        NOT NULL REFERENCES users(id),
    category             VARCHAR(20)   NOT NULL
                         CHECK (category IN ('STRATEGIES', 'MARKET', 'HELP', 'COMPETITIONS')),
    title                VARCHAR(150)  NOT NULL,
    body                 VARCHAR(20000) NOT NULL,
    strategy_version_id  BIGINT        REFERENCES strategy_versions(id),
    strategy_results     JSONB,
    like_count           INTEGER       NOT NULL DEFAULT 0,
    comment_count        INTEGER       NOT NULL DEFAULT 0,
    report_count         INTEGER       NOT NULL DEFAULT 0,
    hidden               BOOLEAN       NOT NULL DEFAULT FALSE,
    hidden_reason        VARCHAR(300),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMPTZ
);

CREATE INDEX idx_forum_posts_new ON forum_posts(created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_forum_posts_user ON forum_posts(user_id, created_at DESC);

CREATE TABLE forum_images (
    id           BIGSERIAL PRIMARY KEY,
    post_id      BIGINT      NOT NULL REFERENCES forum_posts(id),
    position     INTEGER     NOT NULL,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
    width        INTEGER     NOT NULL,
    height       INTEGER     NOT NULL,
    bytes        BYTEA       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_forum_image_position UNIQUE (post_id, position)
);

CREATE TABLE forum_comments (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT        NOT NULL REFERENCES forum_posts(id),
    user_id    BIGINT        NOT NULL REFERENCES users(id),
    body       VARCHAR(5000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_forum_comments_post ON forum_comments(post_id, created_at);

-- One like per person per post, enforced by the key itself.
CREATE TABLE forum_likes (
    post_id    BIGINT      NOT NULL REFERENCES forum_posts(id),
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (post_id, user_id)
);

-- One report per person per post, so one angry user cannot hide a post alone.
CREATE TABLE forum_reports (
    id         BIGSERIAL PRIMARY KEY,
    post_id    BIGINT       NOT NULL REFERENCES forum_posts(id),
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    reason     VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_forum_report UNIQUE (post_id, user_id)
);
