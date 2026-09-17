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
     * Housekeeping. Rows that are long expired carry no information worth
     * keeping — the audit value of a used token ends when it can no longer
     * be presented.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
