package com.grocery.payment.repository;

import com.grocery.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

    /** Locates a payment without loading the entity (the changing transaction then locks it by id). */
    @Query("select p.id from Payment p where p.orderId = :orderId")
    Optional<UUID> findIdByOrderId(@Param("orderId") UUID orderId);

    @Query("select p.id from Payment p where p.razorpayOrderId = :razorpayOrderId")
    Optional<UUID> findIdByRazorpayOrderId(@Param("razorpayOrderId") String razorpayOrderId);

    /** {@code SELECT … FOR UPDATE} by primary key (a record lock, no gap lock). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    /**
     * The expiry poller's claim: up to {@code limit} CREATED / PENDING payments past {@code expires_at} that no
     * other poller holds (no lease, or an expired one), oldest first, locked with {@code FOR UPDATE SKIP LOCKED} —
     * rows another poller (or a capture / cancel in flight) has locked are skipped, so replicas claim disjoint
     * batches. Served by {@code idx_payment_status_expires}. Returns the ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM payment
            WHERE status IN ('CREATED', 'PENDING') AND expires_at <= :now
              AND (poll_lease_until IS NULL OR poll_lease_until <= :now)
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimExpired(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Reconciliation's claim (Phase 10 §3): up to {@code limit} PENDING payments whose Razorpay order was created at
     * or before {@code orderedBefore} and whose window is still open, that no run holds (no lease, or an expired one)
     * and that this run ({@code runStartedAt}) has not checked yet, oldest Razorpay order first, locked with
     * {@code FOR UPDATE SKIP LOCKED} so replicas claim disjoint batches. Served by
     * {@code idx_payment_status_provider_order}. Returns the ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM payment
            WHERE status = 'PENDING' AND razorpay_order_id IS NOT NULL
              AND provider_order_at <= :orderedBefore AND expires_at > :now
              AND (reconcile_lease_until IS NULL OR reconcile_lease_until <= :now)
              AND (reconciled_at IS NULL OR reconciled_at < :runStartedAt)
            ORDER BY provider_order_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimForReconciliation(@Param("now") Instant now, @Param("orderedBefore") Instant orderedBefore,
                                        @Param("runStartedAt") Instant runStartedAt, @Param("limit") int limit);
}
