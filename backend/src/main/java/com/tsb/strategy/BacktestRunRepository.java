package com.tsb.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BacktestRunRepository extends JpaRepository<BacktestRun, Long> {

    /** All runs across all versions of one strategy, newest first. An
     *  explicit JPQL subquery instead of an association join — the FK-as-id
     *  style means the query says exactly what it does. */
    @Query("select r from BacktestRun r where r.strategyVersionId in "
            + "(select v.id from StrategyVersion v where v.strategyId = :strategyId) "
            + "order by r.createdAt desc")
    List<BacktestRun> findByStrategy(Long strategyId);
}