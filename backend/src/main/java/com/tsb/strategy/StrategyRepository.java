package com.tsb.strategy;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StrategyRepository extends JpaRepository<Strategy, Long> {

    List<Strategy> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /** The strategy row, locked until the transaction ends (FOR UPDATE):
     *  saves of the same strategy take turns picking the next number. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Strategy s where s.id = :id")
    Optional<Strategy> findLockedById(@Param("id") Long id);
}