package com.grocery.payment.repository;

import com.grocery.payment.entity.Refund;
import com.grocery.payment.entity.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    /** Oldest first (the order shown in {@code PaymentView.refunds}). */
    List<Refund> findByPaymentIdOrderByCreatedAtAscIdAsc(UUID paymentId);

    List<Refund> findByPaymentIdIn(Collection<UUID> paymentIds);

    boolean existsByPaymentIdAndStatus(UUID paymentId, RefundStatus status);

    /** Phase 12: a refund of this return exists already (any status) — one refund per return. */
    boolean existsByReturnId(UUID returnId);

    /** Sum of the payment's refunds in one status (0 when none). */
    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.paymentId = :paymentId and r.status = :status")
    BigDecimal sumAmount(@Param("paymentId") UUID paymentId, @Param("status") RefundStatus status);

    @Query("select r.id from Refund r where r.razorpayRefundId = :razorpayRefundId")
    Optional<UUID> findIdByRazorpayRefundId(@Param("razorpayRefundId") String razorpayRefundId);

    @Query("select r.paymentId from Refund r where r.id = :id")
    Optional<UUID> findPaymentIdById(@Param("id") UUID id);

    /** {@code SELECT … FOR UPDATE} by primary key. Only while the refund's payment row is locked (lock order). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> lockById(@Param("id") UUID id);

    /**
     * The refund poller's claim: up to {@code limit} PENDING refunds due for a (re)try whose provider call is not
     * in flight, locked with {@code FOR UPDATE SKIP LOCKED} (replicas claim disjoint batches). Served by
     * {@code idx_refund_status_next}. Returns the ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM refund
            WHERE status = 'PENDING' AND next_attempt_at <= :now
              AND (lease_until IS NULL OR lease_until <= :now)
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
