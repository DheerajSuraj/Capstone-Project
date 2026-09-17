package com.tsb.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tsb.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Wire shapes for /api/auth. These mirror the TypeScript types in
 * {@code src/auth/api.ts} exactly — if you change one, change the other.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /* ----------------------------------------------------------- requests */

    public record SignUpRequest(
            @NotBlank @Size(min = 3, max = 20) String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 200) String password) {
    }

    /** {@code identifier} is an email or a username; the server decides which. */
    public record SignInRequest(
            @NotBlank @Size(max = 255) String identifier,
            @NotBlank @Size(max = 200) String password) {
    }

    /** {@code credential} is the Google ID token from the browser. */
    public record GoogleRequest(@NotBlank String credential) {
    }

    public record ClaimUsernameRequest(
            @NotBlank String pendingToken,
            @NotBlank @Size(min = 3, max = 20) String username) {
    }

    /* ---------------------------------------------------------- responses */

    public record UserView(
            String id,
            String username,
            String email,
            String displayName,
            String avatarUrl,
            Instant createdAt) {

        static UserView of(User u) {
            return new UserView(
                    String.valueOf(u.getId()),
                    u.getUsername(),
                    u.getEmail(),
                    u.getDisplayName(),
                    u.getAvatarUrl(),
                    u.getCreatedAt());
        }
    }

    /**
     * The two shapes the frontend switches on. Both carry a {@code status}
     * discriminator so the TypeScript union narrows cleanly.
     */
    public record AuthSuccess(String status, String accessToken, UserView user) {
        public static AuthSuccess of(String accessToken, User user) {
            return new AuthSuccess("authenticated", accessToken, UserView.of(user));
        }
    }

    public record NeedsUsername(
            String status,
            String pendingToken,
            String email,
            String displayName,
            String avatarUrl,
            List<String> suggestions) {

        public static NeedsUsername of(String pendingToken, GoogleIdentity id,
                                       List<String> suggestions) {
            return new NeedsUsername("needs_username", pendingToken, id.email(),
                    id.displayName(), id.avatarUrl(), suggestions);
        }
    }

    public record UsernameCheckResponse(boolean available, List<String> suggestions) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String code, String message, Long retryAfterSeconds) {
        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, null);
        }
    }
}
