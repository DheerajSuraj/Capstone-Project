package com.tsb.auth;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.tsb.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Username rules, availability and suggestions.
 *
 * <p>The pattern is stated in three places — here, the {@code ck_users_username_shape}
 * constraint in V4, and {@code USERNAME_PATTERN} in {@code api.ts}. That is
 * duplication on purpose: the client one is a courtesy so the user is told
 * before a round trip, this one is the rule, and the database one is what
 * holds when a future endpoint forgets to call this.
 */
@Service
public class UsernameService {

    private static final Pattern SHAPE = Pattern.compile("^[A-Za-z0-9_]{3,20}$");

    /**
     * Names that would let someone pose as the platform on a leaderboard, or
     * that collide with routes. Small on purpose — a long blocklist mostly
     * annoys real people.
     */
    private static final Set<String> RESERVED = Set.of(
            "admin", "administrator", "root", "system", "tsb", "support",
            "help", "moderator", "mod", "official", "staff", "api", "auth",
            "login", "signup", "settings", "competition", "leaderboard",
            "null", "undefined", "me", "you", "anonymous", "deleted");

    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public UsernameService(UserRepository users) {
        this.users = users;
    }

    /** @return null when acceptable, otherwise the reason it is not. */
    public String validate(String username) {
        if (username == null || username.isBlank()) return "Choose a username.";
        String u = username.trim();
        if (u.length() < 3) return "At least 3 characters.";
        if (u.length() > 20) return "At most 20 characters.";
        if (!SHAPE.matcher(u).matches()) return "Letters, numbers and underscores only.";
        if (RESERVED.contains(u.toLowerCase(Locale.ROOT))) return "That username is reserved.";
        return null;
    }

    @Transactional(readOnly = true)
    public boolean isAvailable(String username) {
        return validate(username) == null && !users.usernameTaken(username.trim());
    }

    /** Throws unless the name is both well formed and free. */
    @Transactional(readOnly = true)
    public void requireAvailable(String username) {
        String problem = validate(username);
        if (problem != null) throw AuthException.usernameInvalid(problem);
        if (users.usernameTaken(username.trim())) throw AuthException.usernameTaken();
    }

    /**
     * Three free names near the one they wanted.
     *
     * <p>Never derived from the email local part when the caller did not
     * already reveal it — a suggestion like {@code juan_colaco} beside a
     * leaderboard tells everyone more than the user chose to share.
     */
    @Transactional(readOnly = true)
    public List<String> suggest(String desired) {
        String base = normalizeBase(desired);
        List<String> out = new ArrayList<>(3);

        // Short numeric suffixes first: they read as a deliberate choice
        // rather than as a system-generated account.
        for (int i = 1; i <= 9 && out.size() < 2; i++) {
            String candidate = truncate(base, 19) + i;
            if (isFree(candidate)) out.add(candidate);
        }
        // Then a couple of random ones, so two people racing for the same
        // name are not handed the same suggestion.
        for (int attempt = 0; attempt < 12 && out.size() < 3; attempt++) {
            String candidate = truncate(base, 17) + "_" + (10 + random.nextInt(90));
            if (!out.contains(candidate) && isFree(candidate)) out.add(candidate);
        }
        return out;
    }

    /**
     * The first username offered to somebody arriving through Google. Built
     * from their display name, falling back to the email local part only
     * because there is nothing else — and the user can always replace it
     * before anything is saved.
     */
    @Transactional(readOnly = true)
    public List<String> suggestForGoogle(String displayName, String email) {
        String source = (displayName != null && !displayName.isBlank())
                ? displayName
                : (email == null ? "trader" : email.split("@")[0]);
        String base = normalizeBase(source);
        List<String> out = new ArrayList<>(3);
        if (isFree(base)) out.add(base);
        out.addAll(suggest(base).stream().filter(s -> !out.contains(s)).limit(3 - out.size()).toList());
        return out;
    }

    private boolean isFree(String candidate) {
        return validate(candidate) == null && !users.usernameTaken(candidate);
    }

    private static String normalizeBase(String raw) {
        String cleaned = (raw == null ? "" : raw)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_+|_+$", "");
        if (cleaned.length() < 3) cleaned = "trader";
        return truncate(cleaned, 20);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
