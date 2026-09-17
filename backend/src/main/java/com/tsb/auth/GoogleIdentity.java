package com.tsb.auth;

/**
 * A Google identity whose ID token has already been verified — signature
 * checked against Google's published keys, issuer and audience confirmed,
 * and {@code email_verified} true.
 *
 * <p>Nothing constructs this except {@link GoogleTokenVerifier}, so holding
 * one is proof the identity was checked rather than merely claimed.
 */
public record GoogleIdentity(
        String subject,
        String email,
        String displayName,
        String avatarUrl) {
}
