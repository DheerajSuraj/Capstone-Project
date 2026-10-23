package com.tsb.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Revokes every token descended from one sign-in. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update RefreshToken t
              set t.revokedAt = :now
            where t.familyId = :familyId
              and t.revokedAt is null
           """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** Used by "sign out everywhere" and when an account is disabled. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update RefreshToken t
              set t.revokedAt = :now
            where t.userId = :userId
              and t.revokedAt is null
           """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Spends a token — only if nobody has yet. Returns 1 for the one request
     * that won, 0 for any other that raced it: two refreshes at the same
     * moment can't both rotate the same token (forking the family).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update RefreshToken t
              set t.usedAt = :now
            where t.id = :id
              and t.usedAt is null
           """)
    int spend(@Param("id") Long id, @Param("now") Instant now);

    /** When the first token of a family — the sign-in — was issued. */
    @Query("select min(t.issuedAt) from RefreshToken t where t.familyId = :familyId")
    Instant familyStartedAt(@Param("familyId") UUID familyId);

    /**
     * Housekeeping: deletes whole sign-ins whose newest token expired before
     * {@code before}. A family goes all at once, never row by row: its first
     * row is what says when the sign-in began ({@link #familyStartedAt}), so
     * deleting old rows of a family still in use would keep moving its start
     * forward, and the longest-session cap would never be reached.
     */
    @Modifying
    @Query("""
           delete from RefreshToken t
            where t.familyId in (select f.familyId from RefreshToken f
                                  group by f.familyId
                                 having max(f.expiresAt) < :before)
           """)
    int deleteExpiredBefore(@Param("before") Instant before);
}
