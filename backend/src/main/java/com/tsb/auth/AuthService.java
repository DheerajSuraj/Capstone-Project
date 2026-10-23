package com.tsb.auth;

import com.tsb.auth.AuthDtos.AuthSuccess;
import com.tsb.auth.AuthDtos.NeedsUsername;
import java.util.Locale;

import com.tsb.user.User;
import com.tsb.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The decisions. Everything HTTP-shaped lives in {@link AuthController};
 * everything token-shaped lives in {@link TokenService}.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final PasswordPolicy policy;
    private final UsernameService usernames;
    private final TokenService tokens;
    private final GoogleTokenVerifier google;
    private final LoginRateLimiter rateLimiter;

    public AuthService(UserRepository users,
                       PasswordEncoder passwords,
                       PasswordPolicy policy,
                       UsernameService usernames,
                       TokenService tokens,
                       GoogleTokenVerifier google,
                       LoginRateLimiter rateLimiter) {
        this.users = users;
        this.passwords = passwords;
        this.policy = policy;
        this.usernames = usernames;
        this.tokens = tokens;
        this.google = google;
        this.rateLimiter = rateLimiter;
    }

    /** What a successful sign-in produces: a body, and a cookie to set. */
    public record Session(AuthSuccess body, String refreshToken) {
    }

    /* --------------------------------------------------------- sign up */

    @Transactional
    public Session signUp(String username, String email, String rawPassword) {
        String cleanUser = username.trim();
        String cleanEmail = email.trim().toLowerCase(Locale.ROOT);

        usernames.requireAvailable(cleanUser);
        policy.check(rawPassword, cleanUser, cleanEmail);

        if (users.findByEmailIgnoringCase(cleanEmail).isPresent()) {
            throw AuthException.emailTaken();
        }

        User user = User.withPassword(cleanUser, cleanEmail, passwords.encode(rawPassword));
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two people submitted the same username or email in the gap
            // between the check above and this insert. The unique indexes
            // are what actually decide it; this just turns the loser's
            // constraint violation into something readable.
            throw resolveConflict(e);
        }

        return openSession(user);
    }

    /* --------------------------------------------------------- sign in */

    @Transactional
    public Session signIn(String identifier, String rawPassword) {
        String clean = identifier.trim();
        // The attempt is counted BEFORE the slow check, so parallel guesses
        // can't all slip past the limit.
        rateLimiter.acquire(clean);

        User user = users.findByEmailOrUsername(clean).orElse(null);
        // Username and email of one account share a second counter, so
        // switching between them doesn't double the guesses.
        String accountKey = user == null ? null : "account:" + user.getId();
        if (accountKey != null) {
            rateLimiter.acquire(accountKey);
        }

        // Run the hash comparison even when no such user exists, against a
        // fixed dummy hash. Skipping it would make "unknown email" return
        // measurably faster than "wrong password", and that timing
        // difference is enough to enumerate accounts. A password BCrypt
        // can't take (over 72 bytes) can't be anyone's: spend the same time
        // on the dummy and fail.
        boolean tooLong = PasswordPolicy.tooLongForBcrypt(rawPassword);
        String hash = (user != null && user.hasPassword() && !tooLong)
                ? user.getPasswordHash()
                : DUMMY_HASH;
        boolean ok = passwords.matches(tooLong ? "x" : rawPassword, hash);

        if (user == null || !user.hasPassword() || tooLong || !ok) {
            throw AuthException.invalidCredentials();
        }

        rateLimiter.recordSuccess(clean);
        rateLimiter.recordSuccess(accountKey);
        return openSession(user);
    }

    /**
     * BCrypt of a value nobody knows, at the same cost factor as real
     * hashes. Only ever used to spend the same time on a miss as on a hit.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /* ---------------------------------------------------------- Google */

    /**
     * Returns either a finished session or a request for a username.
     *
     * <p>The one case worth reading twice is an existing password account
     * with the same email. We do <em>not</em> merge them: whoever controls
     * that mailbox would inherit a TSB account they never created. The
     * owner proves the password once, and {@link #linkGoogleToCurrentUser}
     * does the linking.
     */
    @Transactional
    public Object signInWithGoogle(String credential) {
        GoogleIdentity id = google.verify(credential);

        var bySubject = users.findByGoogleSub(id.subject());
        if (bySubject.isPresent()) {
            return openSession(bySubject.get());
        }

        var byEmail = users.findByEmailIgnoringCase(id.email());
        if (byEmail.isPresent()) {
            User existing = byEmail.get();
            if (existing.hasPassword()) {
                throw AuthException.accountHasPassword();
            }
            if (existing.getGoogleSub() != null && !existing.getGoogleSub().equals(id.subject())) {
                // Same email, DIFFERENT Google account: the address changed
                // hands. Never take the account over by email alone.
                throw AuthException.googleAccountMismatch();
            }
            // No password and no subject: an account that cannot currently
            // sign in at all. Adopting it is safe and is the only way its
            // strategies stay reachable.
            existing.linkGoogle(id.subject(), id.displayName(), id.avatarUrl());
            return openSession(users.save(existing));
        }

        return new NeedsUsername(
                "needs_username",
                tokens.issuePendingToken(id),
                id.email(),
                id.displayName(),
                id.avatarUrl(),
                usernames.suggestForGoogle(id.displayName(), id.email()));
    }

    /** Second half of the Google sign-up. */
    @Transactional
    public Session claimUsername(String pendingToken, String username) {
        GoogleIdentity id = tokens.readPendingToken(pendingToken);
        String cleanUser = username.trim();
        usernames.requireAvailable(cleanUser);

        // Re-check both, because five minutes passed since the first look.
        if (users.findByGoogleSub(id.subject()).isPresent()
                || users.findByEmailIgnoringCase(id.email()).isPresent()) {
            throw AuthException.emailTaken();
        }

        User user = User.withGoogle(cleanUser, id.email().toLowerCase(Locale.ROOT),
                id.subject(), id.displayName(), id.avatarUrl());
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw resolveConflict(e);
        }
        return openSession(user);
    }

    /**
     * Attaches Google to the account of somebody already signed in with
     * their password — the resolution for {@code ACCOUNT_HAS_PASSWORD}.
     */
    @Transactional
    public void linkGoogleToCurrentUser(Long userId, String credential) {
        GoogleIdentity id = google.verify(credential);
        User user = users.findById(userId).orElseThrow(AuthException::unauthenticated);

        var owner = users.findByGoogleSub(id.subject());
        if (owner.isPresent() && !owner.get().getId().equals(userId)) {
            throw AuthException.accountHasPassword();
        }
        user.linkGoogle(id.subject(), id.displayName(), id.avatarUrl());
        users.save(user);
    }

    /* --------------------------------------------------- session moves */

    /** noRollbackFor: when a reused token gets its whole family revoked,
     *  that revocation must be committed even though the request fails —
     *  rolled back, it never happened and the thief kept the session. */
    @Transactional(noRollbackFor = AuthException.class)
    public Session refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw AuthException.unauthenticated();
        }
        Long userId = tokens.ownerOf(rawRefreshToken);
        var rotated = tokens.rotate(rawRefreshToken);
        User user = users.findById(userId).orElseThrow(AuthException::unauthenticated);
        return new Session(
                AuthSuccess.of(tokens.issueAccessToken(user), user),
                rotated.rawToken());
    }

    @Transactional
    public void signOut(String rawRefreshToken) {
        tokens.endSession(rawRefreshToken);
    }

    private Session openSession(User user) {
        var issued = tokens.startSession(user);
        return new Session(
                AuthSuccess.of(tokens.issueAccessToken(user), user),
                issued.rawToken());
    }

    /**
     * Which unique index refused the insert, read from the error itself.
     * (Asking the database again doesn't work: after a failed statement
     * Postgres refuses every further query in the same transaction, and the
     * loser of a sign-up race got a 500 instead of "taken".)
     */
    private static AuthException resolveConflict(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String what = cause == null || cause.getMessage() == null ? ""
                : cause.getMessage().toLowerCase(Locale.ROOT);
        if (what.contains("email")) return AuthException.emailTaken();
        if (what.contains("google_sub")) return AuthException.emailTaken();
        return AuthException.usernameTaken();
    }
}
