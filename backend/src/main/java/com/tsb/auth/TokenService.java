package com.tsb.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.tsb.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and rotates the three kinds of token this system uses.
 *
 * <ol>
 *   <li><b>Access token</b> — a short-lived signed JWT. Held in browser
 *       memory, sent as a Bearer header, never stored server side. It is
 *       self-describing, so validating one costs no database call.</li>
 *   <li><b>Refresh token</b> — a long-lived opaque random string. Stored
 *       here only as a SHA-256 hash, rotated on every use, grouped into a
 *       family so that reuse can be detected.</li>
 *   <li><b>Pending token</b> — a five-minute JWT that carries a verified
 *       Google identity between the two halves of the Google sign-up. It is
 *       stateless on purpose: nothing to store, nothing to clean up, and it
 *       survives a restart mid-signup.</li>
 * </ol>
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final String ISSUER = "tsb";
    private static final String PENDING_TYPE = "pending-google";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final RefreshTokenRepository refreshTokens;
    private final AuthProperties props;
    private final SecureRandom random = new SecureRandom();

    /**
     * Must be stated explicitly. {@code NimbusJwtEncoder} defaults its
     * header to RS256, which throws against an HMAC key — and the failure
     * message points at the key, not at the header, so it costs an
     * afternoon to find.
     */
    private static final JwsHeader HS256 = JwsHeader.with(MacAlgorithm.HS256).build();

    public TokenService(JwtEncoder encoder,
                        JwtDecoder decoder,
                        RefreshTokenRepository refreshTokens,
                        AuthProperties props) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.refreshTokens = refreshTokens;
        this.props = props;
    }

    /* ------------------------------------------------------ access tokens */

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(java.util.List.of(ISSUER))
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .build();
        return encoder.encode(JwtEncoderParameters.from(HS256, claims)).getTokenValue();
    }

    /* ----------------------------------------------------- refresh tokens */

    /** The raw token to put in the cookie, plus the family it belongs to. */
    public record IssuedRefresh(String rawToken, UUID familyId) {
    }

    /** Starts a brand new session — a fresh family with one token in it. */
    @Transactional
    public IssuedRefresh startSession(User user) {
        return mint(user.getId(), UUID.randomUUID());
    }

    /**
     * Exchanges a refresh token for a new one.
     *
     * <p>If the presented token has already been spent, every token in its
     * family is revoked and the caller is rejected. Either the token leaked
     * and someone else is using it, or the real client replayed one — and
     * there is no way to tell which from here, so the session ends. This is
     * the reuse-detection rule from the OAuth 2.0 browser-based-apps BCP.
     */
    @Transactional
    public IssuedRefresh rotate(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(AuthException::unauthenticated);

        Instant now = Instant.now();

        if (stored.getUsedAt() != null) {
            log.warn("Refresh token reuse detected for user {} — revoking family {}",
                    stored.getUserId(), stored.getFamilyId());
            refreshTokens.revokeFamily(stored.getFamilyId(), now);
            throw AuthException.unauthenticated();
        }
        if (!stored.isUsable(now)) {
            throw AuthException.unauthenticated();
        }

        stored.markUsed();
        refreshTokens.save(stored);
        return mint(stored.getUserId(), stored.getFamilyId());
    }

    /** Reads the owner of a refresh token without spending it. */
    @Transactional(readOnly = true)
    public Long ownerOf(String rawToken) {
        return refreshTokens.findByTokenHash(sha256(rawToken))
                .map(RefreshToken::getUserId)
                .orElseThrow(AuthException::unauthenticated);
    }

    @Transactional
    public void endSession(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        refreshTokens.findByTokenHash(sha256(rawToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional
    public void endAllSessions(Long userId) {
        refreshTokens.revokeAllForUser(userId, Instant.now());
    }

    private IssuedRefresh mint(Long userId, UUID familyId) {
        // 256 bits from SecureRandom. There is nothing to guess here — the
        // token carries no structure and no meaning, it is only a lookup key.
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        refreshTokens.save(new RefreshToken(
                userId, sha256(raw), familyId,
                Instant.now().plus(props.refreshTokenTtl())));

        return new IssuedRefresh(raw, familyId);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }

    /* ----------------------------------------------------- pending tokens */

    /**
     * Wraps a verified Google identity so the browser can hand it back on
     * the username step. The browser cannot alter it: any edit breaks the
     * signature, and the email inside is never read from the request body.
     */
    public String issuePendingToken(GoogleIdentity identity) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(java.util.List.of(ISSUER))
                .issuedAt(now)
                .expiresAt(now.plus(java.time.Duration.ofMinutes(5)))
                .subject(identity.subject())
                .claim("typ", PENDING_TYPE)
                .claim("email", identity.email())
                .claim("name", identity.displayName())
                .claim("picture", identity.avatarUrl())
                .build();
        return encoder.encode(JwtEncoderParameters.from(HS256, claims)).getTokenValue();
    }

    public GoogleIdentity readPendingToken(String token) {
        try {
            var jwt = decoder.decode(token);
            if (!PENDING_TYPE.equals(jwt.getClaimAsString("typ"))) {
                // An access token presented here would otherwise let someone
                // create an account for an identity they never proved.
                throw AuthException.pendingExpired();
            }
            return new GoogleIdentity(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("name"),
                    jwt.getClaimAsString("picture"));
        } catch (JwtException e) {
            throw AuthException.pendingExpired();
        }
    }
}
