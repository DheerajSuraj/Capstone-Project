package com.tsb.paper;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PaperOrderRepository extends JpaRepository<PaperOrder, Long> {

    List<PaperOrder> findByStatus(String status);

    List<PaperOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    List<PaperOrder> findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long userId, Instant since, Pageable page);
}
