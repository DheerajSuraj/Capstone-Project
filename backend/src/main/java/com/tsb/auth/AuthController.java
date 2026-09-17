package com.tsb.auth;

import com.tsb.auth.AuthDtos.ClaimUsernameRequest;
import com.tsb.auth.AuthDtos.GoogleRequest;
import com.tsb.auth.AuthDtos.NeedsUsername;
import com.tsb.auth.AuthDtos.SignInRequest;
import com.tsb.auth.AuthDtos.SignUpRequest;
import com.tsb.auth.AuthDtos.UserView;
import com.tsb.auth.AuthDtos.UsernameCheckResponse;
import com.tsb.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CookieValue;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * Path is scoped to /api/auth so the refresh cookie is not attached to
     * every backtest and candle request. It is only ever needed by the two
     * endpoints below, and a cookie that travels everywhere is a cookie
     * that ends up somewhere it should not.
     */
    static final String COOKIE_NAME = "tsb_refresh";
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthService auth;
    private final UsernameService usernames;
    private final UserRepository users;
    private final AuthProperties props;

    public AuthController(AuthService auth, UsernameService usernames,
                          UserRepository users, AuthProperties props) {
        this.auth = auth;
        this.usernames = usernames;
        this.users = users;
        this.props = props;
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signUp(@Valid @RequestBody SignUpRequest req) {
        var session = auth.signUp(req.username(), req.email(), req.password());
        return withRefreshCookie(session);
    }

    @PostMapping("/login")
    public ResponseEntity<?> signIn(@Valid @RequestBody SignInRequest req) {
        var session = auth.signIn(req.identifier(), req.password());
        return withRefreshCookie(session);
    }

    @PostMapping("/google")
    public ResponseEntity<?> google(@Valid @RequestBody GoogleRequest req) {
        Object outcome = auth.signInWithGoogle(req.credential());
        if (outcome instanceof AuthService.Session session) {
            return withRefreshCookie(session);
        }
        // A first-time Google user. No session yet, no cookie yet — they do
        // not have an account until they have a username.
        return ResponseEntity.ok((NeedsUsername) outcome);
    }

    @PostMapping("/google/username")
    public ResponseEntity<?> claimUsername(@Valid @RequestBody ClaimUsernameRequest req) {
        var session = auth.claimUsername(req.pendingToken(), req.username());
        return withRefreshCookie(session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = COOKIE_NAME, required = false) String refreshToken) {
        var session = auth.refresh(refreshToken);
        return withRefreshCookie(session);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> signOut(
            @CookieValue(name = COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        auth.signOut(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, clearedCookie().toString());
        return ResponseEntity.noContent().build();
    }

    /**
     * Public on purpose: it answers only about a name the caller already
     * typed, and a sign-up form that reports "taken" after submit is worse
     * than the small amount this reveals. Usernames are shown on public
     * leaderboards anyway.
     */
    @GetMapping("/username-available")
    public UsernameCheckResponse usernameAvailable(@RequestParam("u") String username) {
        if (usernames.isAvailable(username)) {
            return new UsernameCheckResponse(true, List.of());
        }
        return new UsernameCheckResponse(false, usernames.suggest(username));
    }

    /** Who the current access token belongs to. Requires authentication. */
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) throw AuthException.unauthenticated();
        Long id = Long.valueOf(jwt.getSubject());
        return users.findById(id).map(UserView::of)
                .orElseThrow(AuthException::unauthenticated);
    }

    /* ----------------------------------------------------------- cookies */

    private ResponseEntity<?> withRefreshCookie(AuthService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(session.body());
    }

    private ResponseCookie refreshCookie(String value) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(COOKIE_NAME, value)
                // JavaScript cannot read this. That is the entire point: an
                // XSS bug can call the API as the user while the page is
                // open, but it cannot walk away with a 30-day session.
                .httpOnly(true)
                // Lax blocks the cookie on cross-site POSTs, which is what
                // makes CSRF on /refresh a non-issue. Strict would break
                // returning from the Google redirect.
                .sameSite("Lax")
                .secure(props.cookieSecure())
                .path(COOKIE_PATH)
                .maxAge(props.refreshTokenTtl());
        if (props.cookieDomain() != null && !props.cookieDomain().isBlank()) {
            b.domain(props.cookieDomain());
        }
        return b.build();
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .sameSite("Lax")
                .secure(props.cookieSecure())
                .path(COOKIE_PATH)
                .maxAge(Duration.ZERO)
                .build();
    }
}
