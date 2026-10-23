-- ═══════════════════════════════════════════════════════════════════════
-- V10: database fixes from the October review.
--
--   1. Pending competition orders no longer overflow a 200-character
--      column (a strategy with many rules froze its whole competition).
--   2. A strategy version can be entered only once per competition, and a
--      trader cannot enter the same source twice — backing up the checks in
--      CompetitionService for two submits racing each other.
--   3. The entry window is checked against the real clock, with the
--      competition row locked, so an insert cannot slip in after the start.
--   4. The append-only tables refuse TRUNCATE as well as UPDATE/DELETE.
--   5. Forum reports can be marked reviewed, so a post a moderator restored
--      is not hidden again by the reports it already survived.
--   6. An index for the forum's comments-per-person rate limit.
--
-- Earlier migrations are never edited: Flyway checksums them, and they
-- have already run on every database.
-- ═══════════════════════════════════════════════════════════════════════

-- ── 1. Pending orders ───────────────────────────────────────────────────
ALTER TABLE competition_entries ALTER COLUMN pending_orders TYPE TEXT;


-- ── 2. One entry per version, one per source per trader ─────────────────
-- Entries can never be deleted (V6 trigger), so duplicates made before
-- this migration must stay. Where they exist, the unique index covers only
-- entries submitted from now on; the service's own checks still compare
-- new entries against the old ones.
DO $$
DECLARE
    last_id BIGINT := (SELECT COALESCE(MAX(id), 0) FROM competition_entries);
BEGIN
    IF EXISTS (SELECT 1 FROM competition_entries
               GROUP BY competition_id, strategy_version_id HAVING COUNT(*) > 1) THEN
        RAISE NOTICE 'duplicate entries found: uq_entries_version covers ids above %', last_id;
        EXECUTE format('CREATE UNIQUE INDEX uq_entries_version ON competition_entries '
                       '(competition_id, strategy_version_id) WHERE id > %s', last_id);
    ELSE
        CREATE UNIQUE INDEX uq_entries_version
            ON competition_entries (competition_id, strategy_version_id);
    END IF;

    IF EXISTS (SELECT 1 FROM competition_entries
               GROUP BY competition_id, user_id, source_hash HAVING COUNT(*) > 1) THEN
        RAISE NOTICE 'duplicate sources found: uq_entries_source covers ids above %', last_id;
        EXECUTE format('CREATE UNIQUE INDEX uq_entries_source ON competition_entries '
                       '(competition_id, user_id, source_hash) WHERE id > %s', last_id);
    ELSE
        CREATE UNIQUE INDEX uq_entries_source
            ON competition_entries (competition_id, user_id, source_hash);
    END IF;
END;
$$;


-- ── 3. The entry window, against the real clock ─────────────────────────
-- NOW() is when the inserting TRANSACTION began: one that started a second
-- before the competition did could still insert after it. clock_timestamp()
-- is the moment of the insert. FOR SHARE holds the competition row until
-- the insert commits, so it cannot start (OPEN -> RUNNING) in between.
CREATE OR REPLACE FUNCTION guard_competition_entry() RETURNS trigger AS $$
DECLARE
    c_status TEXT;
    c_start  TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'competition entries are locked and cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT status, starts_at INTO c_status, c_start
          FROM competitions WHERE id = NEW.competition_id
           FOR SHARE;
        IF c_status IS DISTINCT FROM 'OPEN' OR clock_timestamp() >= c_start THEN
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


-- ── 4. No TRUNCATE on the append-only tables ────────────────────────────
-- Row triggers do not fire for TRUNCATE, so it emptied these tables past
-- every lock above. (A database owner can still drop a trigger; in
-- production the app should connect as a role that does not own them.)
CREATE FUNCTION forbid_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% cannot be truncated: its rows are permanent', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_versions_no_truncate
    BEFORE TRUNCATE ON strategy_versions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_truncate();

CREATE TRIGGER trg_competitions_no_truncate
    BEFORE TRUNCATE ON competitions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_truncate();

CREATE TRIGGER trg_competition_entries_no_truncate
    BEFORE TRUNCATE ON competition_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_truncate();

CREATE TRIGGER trg_competition_trades_no_truncate
    BEFORE TRUNCATE ON competition_trades
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_truncate();


-- ── 5. Reviewed reports ─────────────────────────────────────────────────
ALTER TABLE forum_reports ADD COLUMN reviewed BOOLEAN NOT NULL DEFAULT FALSE;

-- A visible post with 3 or more reports (the auto-hide threshold) can only
-- be one a moderator restored: those reports count as reviewed. Reports on
-- posts that never reached the threshold stay open.
UPDATE forum_reports r SET reviewed = TRUE
  FROM forum_posts p
 WHERE p.id = r.post_id AND p.hidden = FALSE
   AND (SELECT COUNT(*) FROM forum_reports x WHERE x.post_id = p.id) >= 3;

UPDATE forum_posts p SET report_count =
       (SELECT COUNT(*) FROM forum_reports r WHERE r.post_id = p.id AND NOT r.reviewed);


-- ── 6. Comments per person ──────────────────────────────────────────────
CREATE INDEX idx_forum_comments_user ON forum_comments (user_id, created_at);
