package com.tsb.forum;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All forum SQL, in one place, as plain JdbcTemplate — the same approach as
 * the candle store. The forum is mostly small rows and counters (likes,
 * reports), which read more clearly as SQL than as an object graph.
 *
 * <p>Every query that takes user input binds it as a parameter ({@code ?}),
 * never by string concatenation, so a search for {@code '; DROP TABLE} is
 * just a search.
 */
@Repository
public class ForumRepository {

    public static final int AUTO_HIDE_REPORTS = 3;

    private final JdbcTemplate jdbc;

    public ForumRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Rows ────────────────────────────────────────────────────────────

    public record PostRow(long id, long userId, String author, String category, String title,
                          String body, Long strategyVersionId, String strategyName,
                          Integer strategyVersion, String strategySymbol,
                          String strategyTimeframe, String strategyResults, Double strategyReturnPct, int likes,
                          int comments, int reports, boolean hidden, String hiddenReason,
                          Instant createdAt, boolean likedByViewer, Long firstImageId,
                          int imageCount) {
    }

    public record ImageMeta(long id, int width, int height) {
    }

    public record ImageData(byte[] bytes, String contentType, boolean postHidden,
                            boolean postDeleted) {
    }

    public record CommentRow(long id, long userId, String author, String body,
                             Instant createdAt) {
    }

    private static final String POST_COLUMNS = """
            p.id, p.user_id, u.username, p.category, p.title, p.body,
            p.strategy_version_id, s.name AS strategy_name, v.version_number,
            v.symbol, v.timeframe, p.strategy_results::text AS results,
            (p.strategy_results ->> 'returnPct')::float8 AS strategy_return,
            p.like_count, p.comment_count, p.report_count, p.hidden, p.hidden_reason,
            p.created_at,
            EXISTS (SELECT 1 FROM forum_likes l WHERE l.post_id = p.id AND l.user_id = ?) AS liked,
            (SELECT MIN(i.id) FROM forum_images i WHERE i.post_id = p.id) AS first_image,
            (SELECT COUNT(*) FROM forum_images i WHERE i.post_id = p.id) AS image_count
            FROM forum_posts p
            JOIN users u ON u.id = p.user_id
            LEFT JOIN strategy_versions v ON v.id = p.strategy_version_id
            LEFT JOIN strategies s ON s.id = v.strategy_id
            """;

    private static PostRow post(ResultSet rs) throws SQLException {
        long versionId = rs.getLong("strategy_version_id");
        boolean hasStrategy = !rs.wasNull();
        double ret = rs.getDouble("strategy_return");
        Double strategyReturn = rs.wasNull() ? null : ret;
        long firstImage = rs.getLong("first_image");
        boolean hasImage = !rs.wasNull();
        return new PostRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("username"),
                rs.getString("category"), rs.getString("title"), rs.getString("body"),
                hasStrategy ? versionId : null, rs.getString("strategy_name"),
                hasStrategy ? rs.getInt("version_number") : null, rs.getString("symbol"),
                rs.getString("timeframe"), rs.getString("results"), strategyReturn,
                rs.getInt("like_count"),
                rs.getInt("comment_count"), rs.getInt("report_count"), rs.getBoolean("hidden"),
                rs.getString("hidden_reason"), rs.getTimestamp("created_at").toInstant(),
                rs.getBoolean("liked"), hasImage ? firstImage : null, rs.getInt("image_count"));
    }

    // ── Posts ───────────────────────────────────────────────────────────

    public long insertPost(long userId, String category, String title, String body,
                           Long strategyVersionId, String resultsJson) {
        return jdbc.queryForObject("""
                INSERT INTO forum_posts (user_id, category, title, body,
                                         strategy_version_id, strategy_results)
                VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                """, Long.class, userId, category, title, body, strategyVersionId, resultsJson);
    }

    /**
     * A page of posts, newest or most-liked first.
     *
     * @param viewerId        for "liked by me"; 0 when signed out
     * @param includeHidden   moderators see hidden posts; everyone else does not
     */
    public List<PostRow> findPosts(String category, String query, boolean top, int limit,
                                   int offset, long viewerId, boolean includeHidden) {
        StringBuilder sql = new StringBuilder("SELECT ").append(POST_COLUMNS)
                .append(" WHERE p.deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(viewerId);
        if (!includeHidden) {
            sql.append(" AND (p.hidden = FALSE OR p.user_id = ?)");
            args.add(viewerId);
        }
        if (category != null) {
            sql.append(" AND p.category = ?");
            args.add(category);
        }
        if (query != null && !query.isBlank()) {
            // LIKE wildcards in the user's text are escaped: searching "50%"
            // looks for "50%", not "50" followed by anything.
            String like = "%" + query.trim().toLowerCase()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (LOWER(p.title) LIKE ? ESCAPE '\\' OR LOWER(p.body) LIKE ? ESCAPE '\\')");
            args.add(like);
            args.add(like);
        }
        sql.append(top ? " ORDER BY p.like_count DESC, p.created_at DESC"
                : " ORDER BY p.created_at DESC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> post(rs), args.toArray());
    }

    public Optional<PostRow> findPost(long id, long viewerId) {
        List<PostRow> rows = jdbc.query("SELECT " + POST_COLUMNS
                + " WHERE p.id = ? AND p.deleted_at IS NULL", (rs, i) -> post(rs), viewerId, id);
        return rows.stream().findFirst();
    }

    public void softDeletePost(long id) {
        jdbc.update("UPDATE forum_posts SET deleted_at = NOW() WHERE id = ?", id);
    }

    public void setHidden(long id, boolean hidden, String reason) {
        jdbc.update("UPDATE forum_posts SET hidden = ?, hidden_reason = ? WHERE id = ?",
                hidden, hidden ? reason : null, id);
    }

    public int postsSince(long userId, Instant since) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM forum_posts WHERE user_id = ? AND created_at >= ?",
                Integer.class, userId, Timestamp.from(since));
    }

    // ── Images ──────────────────────────────────────────────────────────

    public void insertImage(long postId, int position, ImageSanitizer.Clean img) {
        jdbc.update("""
                INSERT INTO forum_images (post_id, position, content_type, width, height, bytes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, postId, position, img.contentType(), img.width(), img.height(), img.bytes());
    }

    /** Metadata only — the bytes are never loaded to list a post. */
    public List<ImageMeta> images(long postId) {
        return jdbc.query("SELECT id, width, height FROM forum_images WHERE post_id = ? ORDER BY position",
                (rs, i) -> new ImageMeta(rs.getLong("id"), rs.getInt("width"), rs.getInt("height")),
                postId);
    }

    public Optional<ImageData> image(long id) {
        return jdbc.query("""
                SELECT i.bytes, i.content_type, p.hidden, p.deleted_at IS NOT NULL AS deleted
                FROM forum_images i JOIN forum_posts p ON p.id = i.post_id WHERE i.id = ?
                """, (rs, n) -> new ImageData(rs.getBytes("bytes"), rs.getString("content_type"),
                rs.getBoolean("hidden"), rs.getBoolean("deleted")), id).stream().findFirst();
    }

    // ── Comments ────────────────────────────────────────────────────────

    public List<CommentRow> comments(long postId) {
        return jdbc.query("""
                SELECT c.id, c.user_id, u.username, c.body, c.created_at
                FROM forum_comments c JOIN users u ON u.id = c.user_id
                WHERE c.post_id = ? AND c.deleted_at IS NULL ORDER BY c.created_at, c.id
                """, (rs, i) -> new CommentRow(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("username"), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant()), postId);
    }

    public long insertComment(long postId, long userId, String body) {
        long id = jdbc.queryForObject(
                "INSERT INTO forum_comments (post_id, user_id, body) VALUES (?, ?, ?) RETURNING id",
                Long.class, postId, userId, body);
        refreshCommentCount(postId);
        return id;
    }

    public Optional<long[]> commentOwnerAndPost(long commentId) {
        return jdbc.query("SELECT user_id, post_id FROM forum_comments WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)}, commentId).stream().findFirst();
    }

    public void softDeleteComment(long commentId, long postId) {
        jdbc.update("UPDATE forum_comments SET deleted_at = NOW() WHERE id = ?", commentId);
        refreshCommentCount(postId);
    }

    public int commentsSince(long userId, Instant since) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM forum_comments WHERE user_id = ? AND created_at >= ?",
                Integer.class, userId, Timestamp.from(since));
    }

    private void refreshCommentCount(long postId) {
        jdbc.update("""
                UPDATE forum_posts SET comment_count =
                  (SELECT COUNT(*) FROM forum_comments WHERE post_id = ? AND deleted_at IS NULL)
                WHERE id = ?
                """, postId, postId);
    }

    // ── Likes ───────────────────────────────────────────────────────────

    /** Like if not liked, unlike if liked. Returns {liked, count}. */
    public int[] toggleLike(long postId, long userId) {
        int removed = jdbc.update("DELETE FROM forum_likes WHERE post_id = ? AND user_id = ?",
                postId, userId);
        if (removed == 0) {
            jdbc.update("INSERT INTO forum_likes (post_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    postId, userId);
        }
        // Recount rather than +1/-1: the count can never drift from the rows.
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM forum_likes WHERE post_id = ?",
                Integer.class, postId);
        jdbc.update("UPDATE forum_posts SET like_count = ? WHERE id = ?", count, postId);
        return new int[]{removed == 0 ? 1 : 0, count};
    }

    // ── Reports ─────────────────────────────────────────────────────────

    /**
     * Records a report (once per person). When {@value #AUTO_HIDE_REPORTS}
     * different people have reported a post it is hidden until a moderator
     * looks at it. Returns false if this person had already reported it.
     */
    public boolean report(long postId, long userId, String reason) {
        int added = jdbc.update("""
                INSERT INTO forum_reports (post_id, user_id, reason) VALUES (?, ?, ?)
                ON CONFLICT (post_id, user_id) DO NOTHING
                """, postId, userId, reason);
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM forum_reports WHERE post_id = ?",
                Integer.class, postId);
        jdbc.update("UPDATE forum_posts SET report_count = ? WHERE id = ?", count, postId);
        if (count >= AUTO_HIDE_REPORTS) {
            jdbc.update("""
                    UPDATE forum_posts SET hidden = TRUE,
                      hidden_reason = 'Hidden after ' || ?::text || ' reports, waiting for a moderator.'
                    WHERE id = ? AND hidden = FALSE
                    """, count, postId);
        }
        return added > 0;
    }

    public boolean reportedBy(long postId, long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM forum_reports WHERE post_id = ? AND user_id = ?)",
                Boolean.class, postId, userId));
    }
}
