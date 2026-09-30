package com.tsb.forum;

import com.tsb.auth.CurrentUser;
import com.tsb.strategy.CompileController;
import com.tsb.strategy.StrategyService;
import com.tsb.user.User;
import com.tsb.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /api/forum. Reading (lists, posts, pictures) is public, like the market
 * chart; everything that writes needs a signed-in user. A reader who IS
 * signed in also gets "liked by me" and the buttons that are theirs.
 */
@RestController
@RequestMapping("/api/forum")
public class ForumController {

    private final ForumService service;
    private final CurrentUser currentUser;
    private final UserRepository users;

    public ForumController(ForumService service, CurrentUser currentUser, UserRepository users) {
        this.service = service;
        this.currentUser = currentUser;
        this.users = users;
    }

    /** The viewer, or null when signed out. */
    private User viewer() {
        return currentUser.id().flatMap(users::findById).orElse(null);
    }

    // ── Reading ─────────────────────────────────────────────────────────

    @GetMapping("/posts")
    public ListResponse list(@RequestParam(required = false) String category,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "new") String sort,
                             @RequestParam(defaultValue = "0") int page) {
        User me = viewer();
        ForumService.Page p = service.list(me, category, q, sort, page);
        return new ListResponse(p.posts().stream().map(r -> Summary.of(r, me)).toList(),
                p.hasMore(), me != null, service.isModerator(me));
    }

    @GetMapping("/posts/{id}")
    public PostDetail detail(@PathVariable long id) {
        User me = viewer();
        ForumService.Detail d = service.detail(me, id);
        return new PostDetail(Summary.of(d.post(), me), d.post().body(),
                d.images().stream().map(i -> new Image(i.id(), "/api/forum/images/" + i.id(),
                        i.width(), i.height())).toList(),
                d.post().strategyVersionId() == null ? null : new SharedStrategy(
                        d.post().strategyName(), d.post().strategyVersion(),
                        d.post().strategySymbol(), d.post().strategyTimeframe(),
                        d.strategySource(), d.results()),
                d.comments().stream().map(c -> new Comment(c.id(), c.author(), c.body(),
                        c.createdAt().toString(), me != null && c.userId() == me.getId())).toList(),
                d.reportedByViewer(), service.isModerator(me), d.post().hidden(),
                d.post().hiddenReason());
    }

    /**
     * A picture. Only ever re-drawn PNG or JPEG bytes are stored, and they go
     * out with headers that stop a browser from treating them as anything
     * else ({@code nosniff}) or running anything inside them (a CSP of
     * {@code default-src 'none'} if the URL is opened directly).
     */
    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable long id) {
        ForumRepository.ImageData img = service.image(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(img.bytes());
    }

    // ── Writing ─────────────────────────────────────────────────────────

    /** Multipart, because pictures ride along as files. */
    @PostMapping(path = "/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Created create(@RequestParam String category,
                          @RequestParam String title,
                          @RequestParam(defaultValue = "") String body,
                          @RequestParam(required = false) Long strategyId,
                          @RequestParam(required = false) Integer versionNumber,
                          @RequestParam(value = "images", required = false) List<MultipartFile> images) {
        User me = currentUser.require();
        List<byte[]> bytes = new ArrayList<>();
        if (images != null) {
            for (MultipartFile f : images) {
                if (f.isEmpty()) {
                    continue;
                }
                if (f.getSize() > ImageSanitizer.MAX_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Images must be 5 MB or smaller.");
                }
                try {
                    bytes.add(f.getBytes());
                } catch (IOException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read an upload.");
                }
            }
        }
        return new Created(service.create(me, category, title, body, strategyId, versionNumber, bytes));
    }

    @DeleteMapping("/posts/{id}")
    public Map<String, Boolean> delete(@PathVariable long id) {
        service.delete(currentUser.require(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/posts/{id}/like")
    public Like like(@PathVariable long id) {
        int[] r = service.toggleLike(currentUser.require(), id);
        return new Like(r[0] == 1, r[1]);
    }

    @PostMapping("/posts/{id}/comments")
    public Comment comment(@PathVariable long id, @RequestBody @Valid TextRequest r) {
        ForumRepository.CommentRow c = service.comment(currentUser.require(), id, r.text());
        return new Comment(c.id(), c.author(), c.body(), c.createdAt().toString(), true);
    }

    @DeleteMapping("/comments/{id}")
    public Map<String, Boolean> deleteComment(@PathVariable long id) {
        service.deleteComment(currentUser.require(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/posts/{id}/report")
    public Map<String, Boolean> report(@PathVariable long id, @RequestBody @Valid TextRequest r) {
        service.report(currentUser.require(), id, r.text());
        return Map.of("ok", true);
    }

    @PostMapping("/posts/{id}/hide")
    public Map<String, Boolean> hide(@PathVariable long id, @RequestBody HideRequest r) {
        service.setHidden(currentUser.require(), id, r.hidden());
        return Map.of("hidden", r.hidden());
    }

    @PostMapping("/posts/{id}/copy-strategy")
    public CopyResult copy(@PathVariable long id) {
        StrategyService.SaveOutcome o = service.copyStrategy(currentUser.require(), id);
        return o.ok()
                ? new CopyResult(true, o.version().orElseThrow().getStrategyId(),
                o.version().orElseThrow().getVersionNumber(), List.of())
                : new CopyResult(false, null, null, o.diagnostics().stream()
                .map(CompileController.DiagnosticDto::from).toList());
    }

    // ── Shapes ──────────────────────────────────────────────────────────

    public record ListResponse(List<Summary> posts, boolean hasMore, boolean signedIn,
                               boolean moderator) {
    }

    public record Summary(long id, String author, String category, String title, String excerpt,
                          String createdAt, int likes, int comments, boolean liked, boolean mine,
                          String thumbnail, int imageCount, String strategyName, Double strategyReturnPct,
                          boolean hidden) {
        static Summary of(ForumRepository.PostRow r, User me) {
            String body = r.body() == null ? "" : r.body();
            String excerpt = body.length() > 220 ? body.substring(0, 220).trim() + "…" : body;
            return new Summary(r.id(), r.author(), r.category(), r.title(), excerpt,
                    r.createdAt().toString(), r.likes(), r.comments(), r.likedByViewer(),
                    me != null && r.userId() == me.getId(),
                    r.firstImageId() == null ? null : "/api/forum/images/" + r.firstImageId(),
                    r.imageCount(), r.strategyName(), r.strategyReturnPct(), r.hidden());
        }
    }

    public record Image(long id, String url, int width, int height) {
    }

    public record SharedStrategy(String name, Integer version, String symbol, String timeframe,
                                 String source, Map<String, Object> results) {
    }

    public record Comment(long id, String author, String body, String createdAt, boolean mine) {
    }

    public record PostDetail(Summary post, String body, List<Image> images, SharedStrategy strategy,
                             List<Comment> comments, boolean reportedByMe, boolean moderator,
                             boolean hidden, String hiddenReason) {
    }

    public record Created(long id) {
    }

    public record Like(boolean liked, int likes) {
    }

    public record TextRequest(@Size(max = 5000) String text) {
    }

    public record HideRequest(boolean hidden) {
    }

    public record CopyResult(boolean ok, Long strategyId, Integer versionNumber,
                             List<CompileController.DiagnosticDto> diagnostics) {
    }
}
