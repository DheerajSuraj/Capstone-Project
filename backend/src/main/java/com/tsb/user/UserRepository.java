package com.tsb.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Kept from before the auth phase, because existing code calls it —
     * notably wherever the 'dev' user is looked up by name.
     *
     * Prefer findByUsernameIgnoringCase for anything new. This one is
     * case-SENSITIVE, which no longer matches the database: V4 adds a unique
     * index on LOWER(username), so 'Juan' and 'juan' cannot both exist, and a
     * case-sensitive finder will miss a row the index already considers a
     * duplicate.
     */
    Optional<User> findByUsername(String username);

    /** Matches the uq_users_username_lower index in V4. */
    @Query("select u from User u where lower(u.username) = lower(:value)")
    Optional<User> findByUsernameIgnoringCase(@Param("value") String value);

    @Query("select u from User u where lower(u.email) = lower(:value)")
    Optional<User> findByEmailIgnoringCase(@Param("value") String value);

    Optional<User> findByGoogleSub(String googleSub);

    @Query("select (count(u) > 0) from User u where lower(u.username) = lower(:value)")
    boolean usernameTaken(@Param("value") String value);

    /** Sign-in accepts either an email or a username in one field. */
    @Query("""
           select u from User u
           where lower(u.email) = lower(:value)
              or lower(u.username) = lower(:value)
           """)
    Optional<User> findByEmailOrUsername(@Param("value") String value);
}