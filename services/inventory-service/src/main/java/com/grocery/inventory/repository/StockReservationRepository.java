package com.grocery.inventory.repository;

import com.grocery.inventory.entity.StockReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {

    Optional<StockReservation> findByOrderId(UUID orderId);

    /** {@code FOR UPDATE} on an existing reservation (by primary key: a record lock, no gap lock). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from StockReservation r where r.id = :id")
    Optional<StockReservation> lockById(@Param("id") UUID id);

    /**
     * The expiry poller's claim: up to {@code limit} RESERVED reservations past their expiry, oldest first, locked
     * with {@code FOR UPDATE SKIP LOCKED} — rows another poller (or a confirm / release in flight) holds are
     * skipped, so replicas claim disjoint batches. Served by {@code idx_stock_reservation_status_expires}; held
     * reservations ({@code expires_at IS NULL}) are never in the range. Run in a READ COMMITTED transaction, so
     * only the claimed rows are locked (no gap locks that would block new reservations). Returns the ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM stock_reservation
            WHERE status = 'RESERVED' AND expires_at < :now
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimExpired(@Param("now") Instant now, @Param("limit") int limit);
}
