package com.tsb.paper;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface PaperAccountRepository extends JpaRepository<PaperAccount, Long> {

    /**
     * SELECT ... FOR UPDATE. Every change to a wallet takes this lock first,
     * so two clicks (or a click and the order matcher) can never spend the
     * same cash twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PaperAccount a where a.userId = :userId")
    Optional<PaperAccount> lock(@Param("userId") Long userId);

    /**
     * Creates the wallet if there is none — safely when two requests do it
     * at once (a new user's first page load sends two, and a plain
     * find-then-save let both insert and one fail with a duplicate key).
     */
    @Modifying
    @Query(value = "INSERT INTO paper_accounts (user_id, starting_cash, cash) "
            + "VALUES (:userId, :cash, :cash) ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    int insertIfMissing(@Param("userId") Long userId, @Param("cash") BigDecimal cash);
}
