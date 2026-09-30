package com.tsb.competition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompetitionEntryRepository extends JpaRepository<CompetitionEntry, Long> {

    List<CompetitionEntry> findByCompetitionIdOrderByIdAsc(Long competitionId);

    List<CompetitionEntry> findByCompetitionIdAndUserIdOrderByIdAsc(Long competitionId, Long userId);

    long countByCompetitionId(Long competitionId);

    long countByCompetitionIdAndUserId(Long competitionId, Long userId);
}
