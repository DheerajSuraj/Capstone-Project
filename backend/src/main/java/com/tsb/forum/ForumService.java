package com.tsb.forum;

import com.tsb.competition.CompetitionAccess;
import com.tsb.compiler.CompiledStrategy;
import com.tsb.execution.BacktestResult;
import com.tsb.execution.EquityCurves;
import com.tsb.execution.Metrics;
import com.tsb.marketdata.CandleSeries;
import com.tsb.strategy.BacktestService;
import com.tsb.strategy.StrategyService;
import com.tsb.strategy.StrategyVersion;
import com.tsb.user.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The forum: posts (text, pictures, a shared strategy), comments, likes,
 * reports and moderation.
 *
 * <p><b>Sharing a strategy</b> pins ONE version. Versions never change (the
 * V3 trigger), so readers always see exactly what was shared, while the
 * author's later edits stay private. Its backtest is run once, at posting
 * time, and the headline numbers are saved with the post — every reader sees
 * the numbers the author saw. Anyone can then copy that source into their
 * own strategies, where it becomes theirs to edit.
 *
 * <p><b>Limits</b> keep one account from flooding the board: 10 posts an
 * hour, 30 comments every 10 minutes, 4 pictures a post. Each check and the
 * insert it guards run under a per-user lock, so requests sent at the same
 * moment cannot all slip under the limit.
 */
@Service
public class ForumService {

    public static final Set<String> CATEGORIES = Set.of("STRATEGIES", "MARKET", "HELP", "COMPETITIONS");
    public static final int MAX_IMAGES = 4;
    static final int PAGE_SIZE = 20;
    /** Deepest page served (10,000 posts): a page number like 999999999
     *  overflowed the offset into a negative number and a database error. */
    static final int MAX_PAGE = 500;
    static final int POSTS_PER_HOUR = 10;
    static final int COMMENTS_PER_10_MIN = 30;

    private final ForumRepository repo;
    private final StrategyService strategies;
    private final BacktestService backtests;
    private final CompetitionAccess access;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ForumService(ForumRepository repo, StrategyService strategies,
                        BacktestService backtests, CompetitionAccess access, ObjectMapper json,
                        PlatformTransactionManager transactions) {
        this.repo = repo;
        this.strategies = strategies;
        this.backtests = backtests;
        this.access = access;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    public boolean isModerator(User u) {
        return u != null && access.isModerator(u.getUsername());
    }

    // ── Reading ─────────────────────────────────────────────────────────

    public record Page(List<ForumRepository.PostRow> posts, boolean hasMore) {
    }

    public Page list(User viewer, String category, String query, String sort, int page) {
        String cat = category == null || category.isBlank() ? null : checkCategory(category);
        int p = Math.min(MAX_PAGE, Math.max(0, page));
        List<ForumRepository.PostRow> rows = repo.findPosts(cat, query, "top".equals(sort),
                PAGE_SIZE + 1, (long) p * PAGE_SIZE, viewer == null ? 0 : viewer.getId(),
                isModerator(viewer));
        boolean more = rows.size() > PAGE_SIZE;
        return new Page(more ? rows.subList(0, PAGE_SIZE) : rows, more);
    }

    public record Detail(ForumRepository.PostRow post, List<ForumRepository.ImageMeta> images,
                         List<ForumRepository.CommentRow> comments, String strategySource,
                         Map<String, Object> results, boolean reportedByViewer) {
    }

    public Detail detail(User viewer, long id) {
        ForumRepository.PostRow p = visiblePost(viewer, id);
        String source = p.strategyVersionId() == null ? null
                : strategies.versionById(p.strategyVersionId()).map(StrategyVersion::getSource)
                .orElse(null);
        return new Detail(p, repo.images(id), repo.comments(id), source, parse(p.strategyResults()),
                viewer != null && repo.reportedBy(id, viewer.getId()));
    }

    /**
     * Pictures of deleted posts are served to no one. Pictures of hidden
     * posts go only to the people who can see the post — its author and the
     * moderators — the same rule as the post itself (before, the post opened
     * for them with its pictures broken).
     */
    public ForumRepository.ImageData image(User viewer, long imageId) {
        ForumRepository.ImageData img = repo.image(imageId).orElseThrow(this::notFound);
        if (img.postDeleted()) {
            throw notFound();
        }
        if (img.postHidden() && !isModerator(viewer)
                && (viewer == null || img.postAuthorId() != viewer.getId())) {
            throw notFound();
        }
        return img;
    }

    // ── Posting ─────────────────────────────────────────────────────────

    /**
     * Not one transaction: re-drawing pictures and backtesting the shared
     * strategy take seconds, and a transaction held open that long kept a
     * database connection from everyone else (ten slow posts at once used
     * the whole pool). The slow work happens first, outside; then one short
     * transaction checks the limit again and writes the post and its
     * pictures together.
     */
    public long create(User author, String category, String title, String body,
                       Long strategyId, Integer versionNumber, List<byte[]> images) {
        String cat = checkCategory(category);
        String t = clean(title, 150);
        String b = clean(body, 20_000);
        if (t.length() < 3) {
            throw bad("Give your post a title of at least 3 characters.");
        }
        if (b.isEmpty() && (images == null || images.isEmpty()) && strategyId == null) {
            throw bad("Write something, add a picture, or attach a strategy.");
        }
        if (images != null && images.size() > MAX_IMAGES) {
            throw bad("A post can have at most " + MAX_IMAGES + " pictures.");
        }
        checkPostLimit(author);

        // The cheap checks first: a bad strategy choice is refused before
        // any picture is decoded.
        StrategyVersion shared = null;
        if (strategyId != null) {
            if (versionNumber == null) {
                throw bad("Choose which version of the strategy to share.");
            }
            shared = strategies.ownedVersion(author.getId(), strategyId, versionNumber)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No such strategy version."));
        }

        // Sanitize every picture BEFORE writing anything, so one bad file
        // fails the whole post cleanly instead of leaving half of it saved.
        List<ImageSanitizer.Clean> clean = images == null ? List.of()
                : images.stream().map(bytes -> {
                    try {
                        return ImageSanitizer.clean(bytes);
                    } catch (IllegalArgumentException e) {
                        throw bad(e.getMessage());
                    }
                }).toList();

        Long versionId = shared == null ? null : shared.getId();
        String results = shared == null ? null : snapshot(shared);

        Long id = tx.execute(status -> {
            repo.lockUser(author.getId());
            checkPostLimit(author); // again, now that no other post can slip in
            long postId = repo.insertPost(author.getId(), cat, t, b, versionId, results);
            for (int i = 0; i < clean.size(); i++) {
                repo.insertImage(postId, i, clean.get(i));
            }
            return postId;
        });
        return id == null ? 0 : id;
    }

    private void checkPostLimit(User author) {
        if (repo.postsSince(author.getId(), Instant.now().minus(Duration.ofHours(1))) >= POSTS_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You've posted a lot in the last hour. Please wait a little.");
        }
    }

    /** Backtest the shared version once and keep the headline numbers. */
    private String snapshot(StrategyVersion v) {
        BacktestService.Outcome o = backtests.run(v.getSource(), null, null);
        Map<String, Object> m = new LinkedHashMap<>();
        if (!o.ok()) {
            m.put("error", o.runError().orElse("could not be backtested"));
            return json.writeValueAsString(m);
        }
        BacktestResult r = o.result().orElseThrow();
        CompiledStrategy s = o.strategy().orElseThrow();
        CandleSeries c = o.series().orElseThrow();
        Metrics metrics = Metrics.compute(r.equityCurve(), r.warmupBars(), r.trades(),
                Metrics.barsPerYear(s.timeframe()));
        m.put("returnPct", r.totalReturnPct());
        m.put("maxDrawdownPct", r.maxDrawdownPct());
        m.put("winRate", r.winRate());
        m.put("trades", r.trades().size());
        m.put("sharpe", finite(metrics.sharpeRatio()));
        m.put("profitFactor", finite(metrics.profitFactor()));
        m.put("from", Instant.ofEpochMilli(c.openTimeMillis()[0]).toString());
        m.put("to", Instant.ofEpochMilli(c.openTimeMillis()[c.size() - 1]).toString());
        m.put("curve", EquityCurves.downsample(c.openTimeMillis(), r.equityCurve(), 120));
        return json.writeValueAsString(m);
    }

    @Transactional
    public void delete(User user, long id) {
        ForumRepository.PostRow p = repo.findPost(id, user.getId()).orElseThrow(this::notFound);
        if (p.userId() != user.getId() && !isModerator(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own posts.");
        }
        repo.softDeletePost(id);
    }

    // ── Comments, likes, reports ────────────────────────────────────────

    @Transactional
    public ForumRepository.CommentRow comment(User user, long postId, String body) {
        String b = clean(body, 5000);
        if (b.isEmpty()) {
            throw bad("Write a comment first.");
        }
        repo.lockUser(user.getId());
        repo.lockPost(postId);
        visiblePost(user, postId);
        if (repo.commentsSince(user.getId(), Instant.now().minus(Duration.ofMinutes(10)))
                >= COMMENTS_PER_10_MIN) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You're commenting very fast. Please wait a few minutes.");
        }
        long id = repo.insertComment(postId, user.getId(), b);
        return new ForumRepository.CommentRow(id, user.getId(), user.getUsername(), b, Instant.now());
    }

    @Transactional
    public void deleteComment(User user, long commentId) {
        long[] ownerAndPost = repo.commentOwnerAndPost(commentId).orElseThrow(this::notFound);
        if (ownerAndPost[0] != user.getId() && !isModerator(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only delete your own comments.");
        }
        repo.lockPost(ownerAndPost[1]);
        repo.softDeleteComment(commentId, ownerAndPost[1]);
    }

    @Transactional
    public int[] toggleLike(User user, long postId) {
        repo.lockPost(postId);
        visiblePost(user, postId);
        return repo.toggleLike(postId, user.getId());
    }

    @Transactional
    public void report(User user, long postId, String reason) {
        repo.lockPost(postId);
        ForumRepository.PostRow p = visiblePost(user, postId);
        if (p.userId() == user.getId()) {
            throw bad("You can't report your own post — delete it instead.");
        }
        String r = clean(reason, 300);
        if (r.length() < 3) {
            throw bad("Say briefly what's wrong with the post.");
        }
        repo.report(postId, user.getId(), r, access.hasModerators());
    }

    @Transactional
    public void setHidden(User moderator, long postId, boolean hidden) {
        if (!isModerator(moderator)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only moderators can do that.");
        }
        repo.findPost(postId, moderator.getId()).orElseThrow(this::notFound);
        repo.setHidden(postId, hidden, "Hidden by a moderator.");
    }

    /** Copy a shared strategy into the reader's own strategies, to edit freely. */
    @Transactional
    public StrategyService.SaveOutcome copyStrategy(User user, long postId) {
        ForumRepository.PostRow p = visiblePost(user, postId);
        if (p.strategyVersionId() == null) {
            throw bad("This post doesn't share a strategy.");
        }
        StrategyVersion v = strategies.versionById(p.strategyVersionId()).orElseThrow(this::notFound);
        String name = (p.strategyName() == null ? "Strategy" : p.strategyName())
                + " (from @" + p.author() + ")";
        return strategies.create(user.getId(), name.length() > 100 ? name.substring(0, 100) : name,
                v.getSource());
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Hidden posts are visible only to their author and to moderators. */
    private ForumRepository.PostRow visiblePost(User viewer, long id) {
        ForumRepository.PostRow p = repo.findPost(id, viewer == null ? 0 : viewer.getId())
                .orElseThrow(this::notFound);
        if (p.hidden() && !isModerator(viewer) && (viewer == null || p.userId() != viewer.getId())) {
            throw notFound();
        }
        return p;
    }

    static String checkCategory(String c) {
        String up = c == null ? "" : c.trim().toUpperCase();
        if (!CATEGORIES.contains(up)) {
            throw bad("Pick a category: strategies, market, help or competitions.");
        }
        return up;
    }

    /**
     * Trims and drops control characters (except newlines and tabs). Text is
     * stored as the author typed it and only ever shown as TEXT — the
     * frontend never renders it as HTML — so there is nothing to escape here.
     */
    static String clean(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().filter(cp -> cp == '\n' || cp == '\t' || !Character.isISOControl(cp))
                .forEach(b::appendCodePoint);
        String out = b.toString().replace("\r", "").trim();
        if (out.length() > max) {
            throw bad("That's too long (" + out.length() + " characters, the limit is " + max + ").");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String text) {
        if (text == null) {
            return null;
        }
        return json.readValue(text, Map.class);
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "That post doesn't exist.");
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
