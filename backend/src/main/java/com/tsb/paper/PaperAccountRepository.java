package com.tsb.paper;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
