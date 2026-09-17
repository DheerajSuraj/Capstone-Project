package com.tsb.auth;

import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Turns the ID token the browser received from Google into a
 * {@link GoogleIdentity} we are willing to act on.
 *
 * <p>The token arrives from the browser, so none of it is trusted until:
 * <ul>
 *   <li>its signature verifies against Google's published JWK set (fetched
 *       and cached by Nimbus — no key material lives in this repository);</li>
 *   <li>{@code iss} is Google;</li>
 *   <li>{@code aud} is <em>our</em> client ID — this is the check that stops
 *       a valid Google token minted for a different application being
 *       replayed here, and it is the one people most often skip;</li>
 *   <li>it has not expired;</li>
 *   <li>{@code email_verified} is true, so we never key an account on an
 *       address the holder has not proved.</li>
 * </ul>
 *
 * <p>Deliberately no dependency beyond {@code spring-boot-starter-oauth2-resource-server},
 * which already brings Nimbus.
 */
@Component
public class GoogleTokenVerifier {

    private static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /** Google is inconsistent about the scheme; both forms are legitimate. */
    private static final Set<String> GOOGLE_ISSUERS =
            Set.of("accounts.google.com", "https://accounts.google.com");

    private final NimbusJwtDecoder decoder;
    private final boolean enabled;

    public GoogleTokenVerifier(AuthProperties props) {
        this.enabled = props.googleEnabled();

        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build();
        if (enabled) {
            d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(),
                    issuerIsGoogle(),
                    audienceIs(props.googleClientId())));
        }
        this.decoder = d;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public GoogleIdentity verify(String credential) {
        if (!enabled) {
            throw AuthException.googleRejected(
                    "Google sign-in is not configured on this server.");
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(credential);
        } catch (JwtException e) {
            // Never echo the exception text back to the browser: it is
            // internal detail and occasionally quotes the token.
            throw AuthException.googleRejected("Google sign-in could not be verified.");
        }

        if (!Boolean.TRUE.equals(jwt.getClaim("email_verified"))) {
            throw AuthException.googleRejected(
                    "That Google account has no verified email address.");
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw AuthException.googleRejected(
                    "Google did not return an email address.");
        }

        return new GoogleIdentity(
                jwt.getSubject(),
                email,
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("picture"));
    }

    private static OAuth2TokenValidator<Jwt> issuerIsGoogle() {
        return jwt -> GOOGLE_ISSUERS.contains(jwt.getIssuer() == null
                ? null : jwt.getIssuer().toString())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_issuer", "Not a Google token.", null));
    }

    private static OAuth2TokenValidator<Jwt> audienceIs(String clientId) {
        return jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            "invalid_audience",
                            "Token was not issued for this application.", null));
        };
    }
}
