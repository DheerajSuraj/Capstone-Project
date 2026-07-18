package com.tsb.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StrategyVersionRepository
        extends JpaRepository<StrategyVersion, Long> {

    List<StrategyVersion> findByStrategyIdOrderByVersionNumberDesc(Long strategyId);

    Optional<StrategyVersion> findByStrategyIdAndVersionNumber(
            Long strategyId, Integer versionNumber);

    @Query("select coalesce(max(v.versionNumber), 0) from StrategyVersion v "
            + "where v.strategyId = :strategyId")
    int maxVersionNumber(Long strategyId);
}