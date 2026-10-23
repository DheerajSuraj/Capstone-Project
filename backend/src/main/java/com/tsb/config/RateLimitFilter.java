package com.tsb.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how often the expensive endpoints can be called — the ones that run
 * the engine over years of candles, or create accounts.
 *
 * <p>Generous for a person (the strategy lab's 42 backtests in a row fit in
 * one minute's allowance), but a script hammering {@code POST /api/indicators}
 * — open to anyone, and minutes of CPU per crafted request before the period
 * cap — now gets HTTP 429 instead of tying the server up.
 *
 * <p>Counted per signed-in user, or per address for anonymous callers, in
 * memory: like the login limiter, it resets on restart and is not shared
 * between instances, which suits a single-server deployment.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Limit(String method, String pathPrefix, int perUser, int perAnonymous,
                         Duration window) {
    }

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private static final List<Limit> LIMITS = List.of(
            new Limit("POST", "/api/indicators", 240, 60, MINUTE),
            new Limit("POST", "/api/backtest", 120, 0, MINUTE),
            new Limit("POST", "/api/debug/explain", 300, 0, MINUTE),
            new Limit("POST", "/api/signals", 60, 0, MINUTE),
            new Limit("POST", "/api/compile", 300, 0, MINUTE),
            new Limit("POST", "/api/strategies/", 120, 0, MINUTE), // /{id}/versions/{v}/run
            // Every attempt counts, not only posts that went through: each
            // one may decode four pictures before it fails.
            new Limit("POST", "/api/forum/posts", 30, 0, HOUR),
            new Limit("POST", "/api/auth/signup", 0, 10, HOUR),
            new Limit("POST", "/api/auth/google/username", 0, 20, HOUR));

    private record Window(long startedMs, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Limit limit = match(request);
        if (limit != null) {
            String user = userId();
            int max = user != null && limit.perUser() > 0 ? limit.perUser() : limit.perAnonymous();
            // perAnonymous 0 on a signed-in-only endpoint: security answers
            // those anonymous calls with 401 anyway.
            if (max > 0) {
                String who = user != null && limit.perUser() > 0 ? "u:" + user
                        : "ip:" + request.getRemoteAddr();
                long retryAfter = take(limit.pathPrefix() + "|" + who, max, limit.window());
                if (retryAfter > 0) {
                    response.setStatus(429);
                    response.setHeader("Retry-After", String.valueOf(retryAfter));
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":"
                            + "\"Too many requests in a short time. Please wait "
                            + retryAfter + " seconds and try again.\"}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static Limit match(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        for (Limit l : LIMITS) {
            if (!l.method().equalsIgnoreCase(request.getMethod())) {
                continue;
            }
            if (l.pathPrefix().endsWith("/")
                    ? path.startsWith(l.pathPrefix()) && path.endsWith("/run")
                    : path.equals(l.pathPrefix())) {
                return l;
            }
        }
        return null;
    }

    private static String userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return null;
    }

    /** Counts one request; 0 if allowed, else seconds until the window resets. */
    private long take(String key, int max, Duration window) {
        long now = System.currentTimeMillis();
        long span = window.toMillis();
        Window w = windows.compute(key, (k, e) -> {
            if (e == null || now - e.startedMs() >= span) {
                return new Window(now, new AtomicInteger(1));
            }
            e.count().incrementAndGet();
            return e;
        });
        if (w.count().get() <= max) {
            return 0;
        }
        return Math.max(1, (w.startedMs() + span - now) / 1000 + 1);
    }

    /** Old windows go, so the map can't grow without bound. */
    @Scheduled(fixedDelay = 300_000)
    void evict() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().startedMs() > HOUR.toMillis());
    }
}
