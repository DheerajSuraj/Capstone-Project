package com.tsb.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Caps failed sign-in attempts per identifier.
 *
 * <p>Without this, the sign-in endpoint is an offline password cracker with
 * a network hop. It matters more here than on a typical project because the
 * competition leaderboard publishes usernames, which hands an attacker the
 * left-hand side of every guess for free.
 *
 * <p><b>Known limitation, worth stating in the report rather than hiding:</b>
 * this counter lives in memory. It resets when the application restarts and
 * it is not shared between instances. For a single-instance deployment that
 * is acceptable; for anything horizontally scaled the same logic belongs in
 * Redis or a table. The interface is small enough to swap.
 *
 * <p>Attempts are counted per identifier rather than per IP because a home
 * or campus connection shares one address between many legitimate users, and
 * locking that out is worse than the attack.
 */
@Component
public class LoginRateLimiter {

    private record Window(Instant startedAt, AtomicInteger failures) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration window;

    public LoginRateLimiter(AuthProperties props) {
        this.maxAttempts = props.maxLoginAttempts();
        this.window = props.loginAttemptWindow();
    }

    /**
     * Reserves one attempt for this identifier, atomically, BEFORE the
     * password is checked — and throws if that goes over the limit. Counting
     * only finished failures let 200 guesses sent at once all pass the check
     * before any of them was recorded. A correct password then clears the
     * slate ({@link #recordSuccess}), so a reserved attempt that succeeds
     * costs nothing.
     */
    public void acquire(String identifier) {
        Instant now = Instant.now();
        Window w = windows.compute(key(identifier), (k, existing) -> {
            if (existing == null || now.isAfter(existing.startedAt().plus(window))) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.failures().incrementAndGet();
            return existing;
        });
        if (w.failures().get() > maxAttempts) {
            Instant expiresAt = w.startedAt().plus(window);
            throw AuthException.rateLimited(
                    Math.max(1, Duration.between(now, expiresAt).toSeconds() + 1));
        }
    }

    /** A correct password clears the slate for that identifier. */
    public void recordSuccess(String identifier) {
        windows.remove(key(identifier));
    }

    private static String key(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase();
    }

    /**
     * Stops the map growing without bound under a distributed guessing
     * attempt, where every attempt uses a different identifier.
     */
    @Scheduled(fixedDelay = 300_000)
    void evictExpired() {
        Instant cutoff = Instant.now().minus(window);
        windows.entrySet().removeIf(e -> e.getValue().startedAt().isBefore(cutoff));
    }
}
