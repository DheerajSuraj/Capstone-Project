package com.tsb.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about authentication, bound from {@code tsb.auth.*}.
 *
 * @param jwtSecret        HMAC key for access tokens. Must be at least 32
 *                         bytes — HS256 refuses anything shorter, and the
 *                         application will not start with a short one.
 * @param accessTokenTtl   Short by design. A stolen access token is only
 *                         useful for this long, and the cost of expiry is
 *                         one silent refresh call.
 * @param refreshTokenTtl  How long a session survives without any activity.
 * @param cookieSecure     Must be true in production. False only so the
 *                         cookie survives plain http://localhost in dev.
 * @param cookieDomain     Leave null for host-only, which is what you want
 *                         unless the API sits on a different subdomain.
 * @param googleClientId   The same Web client ID the browser uses. Checked
 *                         as the audience of every Google ID token, which
 *                         is what stops a token minted for somebody else's
 *                         app being replayed at this one.
 * @param maxLoginAttempts Failures allowed per identifier per window.
 */
@ConfigurationProperties(prefix = "tsb.auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        boolean cookieSecure,
        String cookieDomain,
        String googleClientId,
        int maxLoginAttempts,
        Duration loginAttemptWindow) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "tsb.auth.jwt-secret must be set and at least 32 bytes. "
                            + "Generate one with: openssl rand -base64 48");
        }
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
        if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(30);
        if (maxLoginAttempts <= 0) maxLoginAttempts = 5;
        if (loginAttemptWindow == null) loginAttemptWindow = Duration.ofMinutes(15);
    }

    public boolean googleEnabled() {
        return googleClientId != null && !googleClientId.isBlank();
    }
}
