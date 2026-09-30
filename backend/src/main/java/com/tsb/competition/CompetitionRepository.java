package com.tsb.competition;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompetitionRepository extends JpaRepository<Competition, Long> {

    List<Competition> findAllByOrderByStartsAtDesc();

    List<Competition> findByStatus(String status);
}
