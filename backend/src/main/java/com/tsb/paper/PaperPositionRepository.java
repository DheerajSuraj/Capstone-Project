package com.tsb.paper;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperPositionRepository extends JpaRepository<PaperPosition, Long> {

    List<PaperPosition> findByUserIdOrderBySymbolIdAsc(Long userId);

    Optional<PaperPosition> findByUserIdAndSymbolId(Long userId, Long symbolId);

    void deleteByUserId(Long userId);
}
