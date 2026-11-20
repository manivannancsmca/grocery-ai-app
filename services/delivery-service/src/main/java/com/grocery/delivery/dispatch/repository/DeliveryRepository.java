package com.grocery.delivery.dispatch.repository;

import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    /**
     * {@code SELECT … FOR UPDATE} on one delivery (primary key: a record lock): the first read of every change of a
     * delivery and of its offers. See dispatch/service/AssignmentEngine for the locking rule.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id = :id")
    Optional<Delivery> lockById(@Param("id") UUID id);

    /** The same, found by its order (unique key): the order event consumer. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.orderId = :orderId")
    Optional<Delivery> lockByOrderId(@Param("orderId") UUID orderId);

    /** Several deliveries locked in ascending id order (one statement). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Delivery d where d.id in :ids order by d.id")
    List<Delivery> lockAllById(@Param("ids") Collection<UUID> ids);

    boolean existsByOrderId(UUID orderId);

    Optional<Delivery> findByOrderId(UUID orderId);

    /**
     * The dispatch poller's claim: up to {@code limit} SEARCHING deliveries whose {@code next_search_at} has come
     * (offers to expire, a next wave, a retry), the most overdue first, locked with {@code FOR UPDATE SKIP LOCKED} —
     * rows another poller replica, an accept or a rejection holds are skipped, so replicas never wait or collide.
     * Served by {@code idx_delivery_search}. Run in a READ COMMITTED transaction (no gap locks). Ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM delivery
            WHERE status = 'SEARCHING' AND next_search_at <= :now
            ORDER BY next_search_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimDue(@Param("now") Instant now, @Param("limit") int limit);

    /** The claim for one delivery (the poller's one-by-one fallback after a failed batch). */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM delivery
            WHERE id = UUID_TO_BIN(:id) AND status = 'SEARCHING' AND next_search_at <= :now
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    Optional<String> claimOne(@Param("id") String id, @Param("now") Instant now);

    /** Puts a delivery that cannot be advanced right now back for later (the poller's poison protection). */
    @Modifying
    @Query(value = "UPDATE delivery SET next_search_at = :at WHERE id = UUID_TO_BIN(:id) AND status = 'SEARCHING'",
            nativeQuery = true)
    int postpone(@Param("id") String id, @Param("at") Instant at);

    /**
     * A store's deliveries in {@code statuses} (the store board: still at the store), oldest first, as many as
     * {@code limit} allows. {@code storeId} is the vendorId or restaurantId. Served by idx_delivery_store_status.
     */
    @Query("""
            select d from Delivery d
            where d.storeId = :storeId and d.status in :statuses
            order by d.createdAt, d.id
            """)
    List<Delivery> findOfStore(@Param("storeId") UUID storeId,
                               @Param("statuses") Collection<DeliveryStatus> statuses, Pageable limit);

    /** The admin monitor's filters, shared by its list, count and export (alias {@code d}). */
    String ADMIN_SEARCH = """
            from Delivery d
            where (:status is null or d.status = :status)
              and (:assignmentFailed is null or d.assignmentFailed = :assignmentFailed)
              and (:partnerId is null or d.partnerId = :partnerId)
              and (:orderNumber is null or upper(d.orderNumber) like :orderNumber)
            """;

    /** The admin monitor (contract §5): filters optional, {@code orderNumber} upper-cased with % wildcards. */
    @Query(value = "select d " + ADMIN_SEARCH + " order by d.createdAt desc, d.id desc",
            countQuery = "select count(d) " + ADMIN_SEARCH)
    Page<Delivery> search(@Param("status") DeliveryStatus status, @Param("assignmentFailed") Boolean assignmentFailed,
                          @Param("partnerId") UUID partnerId, @Param("orderNumber") String orderNumber,
                          Pageable pageable);

    /**
     * The partner's finished deliveries, shared by the history list, its count and its export (alias {@code d}):
     * {@code statuses}, and ended in [{@code start}, {@code end}) when those are given (null = open).
     */
    String PARTNER_HISTORY = """
            from Delivery d
            where d.partnerId = :partnerId and d.finishedAt is not null and d.status in :statuses
              and (:start is null or d.finishedAt >= :start)
              and (:end is null or d.finishedAt < :end)
            """;

    /**
     * The partner's finished deliveries (DELIVERED / FAILED), newest first. Served by idx_delivery_partner_finished
     * (partner_id, finished_at): the time range is a range on that index, the status a filter on the rows it finds.
     */
    @Query(value = "select d " + PARTNER_HISTORY + " order by d.finishedAt desc, d.id desc",
            countQuery = "select count(d) " + PARTNER_HISTORY)
    Page<Delivery> history(@Param("partnerId") UUID partnerId,
                           @Param("statuses") Collection<DeliveryStatus> statuses,
                           @Param("start") Instant start, @Param("end") Instant end, Pageable pageable);

    /** How many of the partner's deliveries ended with {@code status} at or after {@code from}. */
    @Query("""
            select count(d) from Delivery d
            where d.partnerId = :partnerId and d.status = :status and d.finishedAt >= :from
            """)
    long countFinishedSince(@Param("partnerId") UUID partnerId, @Param("status") DeliveryStatus status,
                            @Param("from") Instant from);

    /** The partner's deliveries assigned, ended or cancelled within [start, end): their dashboard statistics. */
    @Query("""
            select d from Delivery d
            where d.partnerId = :partnerId
              and ((d.assignedAt >= :start and d.assignedAt < :end)
                or (d.finishedAt >= :start and d.finishedAt < :end)
                or (d.cancelledAt >= :start and d.cancelledAt < :end))
            """)
    List<Delivery> findForPartnerStats(@Param("partnerId") UUID partnerId, @Param("start") Instant start,
                                       @Param("end") Instant end);
}
