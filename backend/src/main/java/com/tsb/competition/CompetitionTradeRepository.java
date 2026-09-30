package com.tsb.competition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompetitionTradeRepository extends JpaRepository<CompetitionTrade, Long> {

    List<CompetitionTrade> findByEntryIdOrderByEntryTimeAscIdAsc(Long entryId);

    /** The trade still open for an entry, if any ("exit_time IS NULL"). */
    Optional<CompetitionTrade> findFirstByEntryIdAndExitTimeIsNullOrderByIdDesc(Long entryId);
}
