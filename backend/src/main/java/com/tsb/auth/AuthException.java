package com.tsb.auth;

import org.springframework.http.HttpStatus;

/**
 * Every failure the auth endpoints can report, each carrying the machine
 * code the frontend switches on.
 *
 * <p>The codes here are the same strings listed in {@code ApiErrorCode} in
 * {@code src/auth/api.ts}.
 */
public class AuthException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Long retryAfterSeconds;

    private AuthException(String code, String message, HttpStatus status,
                          Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public Long getRetryAfterSeconds() { return retryAfterSeconds; }

    /**
     * Wrong password, unknown email, unknown username — all of them, on
     * purpose. Distinguishing them turns the sign-in form into a tool for
     * discovering which people have accounts here.
     */
    public static AuthException invalidCredentials() {
        return new AuthException("INVALID_CREDENTIALS",
                "Email or password is incorrect.", HttpStatus.UNAUTHORIZED, null);
    }

    public static AuthException rateLimited(long retryAfterSeconds) {
        return new AuthException("RATE_LIMITED",
                "Too many attempts. Please wait before trying again.",
                HttpStatus.TOO_MANY_REQUESTS, retryAfterSeconds);
    }

    /**
     * Sign-up cannot silently succeed, so this one has to be specific even
     * though it leaks existence. The asymmetry with
     * {@link #invalidCredentials()} is deliberate and well understood.
     */
    public static AuthException emailTaken() {
        return new AuthException("EMAIL_TAKEN",
                "That email already has an account.", HttpStatus.CONFLICT, null);
    }

    public static AuthException usernameTaken() {
        return new AuthException("USERNAME_TAKEN",
                "That username is taken.", HttpStatus.CONFLICT, null);
    }

    public static AuthException usernameInvalid(String why) {
        return new AuthException("USERNAME_INVALID", why, HttpStatus.BAD_REQUEST, null);
    }

    public static AuthException weakPassword(String why) {
        return new AuthException("WEAK_PASSWORD", why, HttpStatus.BAD_REQUEST, null);
    }

    /**
     * A Google sign-in whose email already belongs to a password account.
     * We do not merge them on a matching address: anyone who controls that
     * mailbox would inherit the TSB account. The owner proves the password
     * once, and only then are the two linked.
     */
    public static AuthException accountHasPassword() {
        return new AuthException("ACCOUNT_HAS_PASSWORD",
                "That email already signs in with a password. "
                        + "Sign in with your password once to link Google.",
                HttpStatus.CONFLICT, null);
    }

    public static AuthException pendingExpired() {
        return new AuthException("PENDING_EXPIRED",
                "That sign-in attempt expired. Please start again.",
                HttpStatus.GONE, null);
    }

    public static AuthException googleRejected(String why) {
        return new AuthException("GOOGLE_REJECTED", why, HttpStatus.UNAUTHORIZED, null);
    }

    public static AuthException unauthenticated() {
        return new AuthException("UNAUTHENTICATED", "Sign in to continue.",
                HttpStatus.UNAUTHORIZED, null);
    }
}
