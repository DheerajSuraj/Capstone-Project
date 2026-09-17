package com.tsb.auth;

import java.util.Optional;

import com.tsb.user.User;
import com.tsb.user.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Who is making this request.
 *
 * <p>This is the seam that replaces the hardcoded {@code user id 1} the
 * services have been using since V3. Anywhere a service currently writes
 * {@code users.getReferenceById(1L)} or similar, inject this and call
 * {@link #requireId()}.
 *
 * <p>Reading the id from the validated JWT rather than from a request
 * parameter is the whole point: a client can ask for any strategy id it
 * likes, but it cannot ask to <em>be</em> another user.
 */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    /** The authenticated user's id, or empty when the request is anonymous. */
    public Optional<Long> id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return Optional.empty();
        try {
            return Optional.of(Long.valueOf(jwt.getSubject()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Long requireId() {
        return id().orElseThrow(AuthException::unauthenticated);
    }

    public User require() {
        return users.findById(requireId()).orElseThrow(AuthException::unauthenticated);
    }

    /**
     * Guards a resource owned by somebody.
     *
     * <p>Throws the same error for "does not exist" and "belongs to someone
     * else", so the API cannot be used to discover which strategy ids exist.
     */
    public void requireOwns(Long ownerId) {
        if (ownerId == null || !ownerId.equals(requireId())) {
            throw AuthException.unauthenticated();
        }
    }
}
