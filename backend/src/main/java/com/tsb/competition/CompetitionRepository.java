package com.tsb.competition;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CompetitionRepository extends JpaRepository<Competition, Long> {

    List<Competition> findAllByOrderByStartsAtDesc();

    List<Competition> findByStatus(String status);

    /**
     * The competition row, locked until the transaction ends (SELECT … FOR
     * UPDATE). The runner, a cancel and an entry submit all take it, so they
     * happen one after another instead of overwriting each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Competition c where c.id = :id")
    Optional<Competition> findLockedById(@Param("id") Long id);
}
