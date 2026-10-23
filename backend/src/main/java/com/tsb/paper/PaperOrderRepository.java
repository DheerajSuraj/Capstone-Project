package com.tsb.paper;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface PaperOrderRepository extends JpaRepository<PaperOrder, Long> {

    List<PaperOrder> findByStatus(String status);

    /** Open orders oldest first: when two cross in the same tick and only
     *  one can be afforded, the one placed first fills. */
    List<PaperOrder> findByStatusOrderByCreatedAtAsc(String status);

    /** Finished orders (filled, cancelled, rejected) since a reset, newest
     *  activity first — open orders don't crowd the history out. */
    @Query("select o from PaperOrder o where o.userId = :userId and o.createdAt >= :since "
            + "and o.status <> 'OPEN' "
            + "order by coalesce(o.filledAt, o.cancelledAt, o.createdAt) desc, o.id desc")
    List<PaperOrder> history(@Param("userId") Long userId, @Param("since") Instant since,
                             Pageable page);

    List<PaperOrder> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);

    List<PaperOrder> findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            Long userId, Instant since, Pageable page);
}
