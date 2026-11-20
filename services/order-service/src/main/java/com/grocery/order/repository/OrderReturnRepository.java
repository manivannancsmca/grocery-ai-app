package com.grocery.order.repository;

import com.grocery.order.entity.OrderReturn;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Return requests (Phase 12 §3). Changes lock the order row first ({@code OrderLocks}), then the return. */
public interface OrderReturnRepository
        extends JpaRepository<OrderReturn, UUID>, JpaSpecificationExecutor<OrderReturn> {

    /** The order's returns, oldest first. */
    List<OrderReturn> findByOrderIdOrderByRequestedAtAscIdAsc(UUID orderId);

    /** The returns of several orders (lists), any order. */
    List<OrderReturn> findByOrderIdIn(Collection<UUID> orderIds);

    /** Locates a return without loading it (the caller locks its order first, then the return). */
    @Query("select r.orderId from OrderReturn r where r.id = :id")
    Optional<UUID> findOrderIdById(@Param("id") UUID id);

    /** {@code SELECT … FOR UPDATE} by primary key — only while the return's order row is locked (lock order). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from OrderReturn r where r.id = :id")
    Optional<OrderReturn> lockById(@Param("id") UUID id);

    /**
     * The escalation poller's claim: up to {@code limit} REQUESTED returns past their {@code decide_by}, locked with
     * {@code FOR UPDATE SKIP LOCKED} (replicas, and a store deciding one right now, are skipped). Served by
     * {@code idx_order_return_status_decide}. Returns the ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM order_return
            WHERE status = 'REQUESTED' AND decide_by <= :now
            ORDER BY decide_by, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimOverdue(@Param("now") Instant now, @Param("limit") int limit);
}
