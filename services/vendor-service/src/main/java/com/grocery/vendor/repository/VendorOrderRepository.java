package com.grocery.vendor.repository;

import com.grocery.vendor.entity.StoreOrderStatus;
import com.grocery.vendor.entity.VendorOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorOrderRepository extends JpaRepository<VendorOrder, UUID> {

    /**
     * {@code SELECT … FOR UPDATE} on one store order (by primary key: a record lock, no gap lock): the first read of
     * every status change (API actions, OrderCancelled). See {@code service/StoreOrderService}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from VendorOrder o where o.orderId = :orderId")
    Optional<VendorOrder> lockById(@Param("orderId") UUID orderId);

    Optional<VendorOrder> findByOrderIdAndVendorId(UUID orderId, UUID vendorId);

    /** The board's filter, shared by the board, its count and its export (alias {@code o}). */
    String BOARD = """
            from VendorOrder o
            where o.vendorId = :vendorId and o.status in :statuses
            """;

    /**
     * The board (contract §2): NEW orders first, the most urgent deadline first; then the rest, newest first.
     * Served by {@code idx_vendor_order_board (vendor_id, status, accept_by)}.
     */
    @Query(value = "select o " + BOARD + """
            order by case when o.status = com.grocery.vendor.entity.StoreOrderStatus.NEW then 0 else 1 end,
                     case when o.status = com.grocery.vendor.entity.StoreOrderStatus.NEW then o.acceptBy end,
                     o.receivedAt desc, o.orderId desc
            """,
            countQuery = "select count(o) " + BOARD)
    Page<VendorOrder> board(@Param("vendorId") UUID vendorId, @Param("statuses") Collection<StoreOrderStatus> statuses,
                            Pageable pageable);

    /**
     * The auto-reject poller's claim: up to {@code limit} NEW orders past their deadline, oldest deadline first, locked
     * with {@code FOR UPDATE SKIP LOCKED} — rows another poller (or an accept / reject / cancel in flight) holds are
     * skipped, so replicas claim disjoint batches and never wait. Served by {@code idx_vendor_order_deadline}. Run in a
     * READ COMMITTED transaction, so only the claimed rows are locked (no gap locks). Returns the ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(order_id) FROM vendor_order
            WHERE status = 'NEW' AND accept_by < :now
            ORDER BY accept_by
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimExpired(@Param("now") Instant now, @Param("limit") int limit);

    /** The sales report's orders: every order of one vendor received in {@code [from, to)}. */
    @Query("""
            select new com.grocery.vendor.repository.ReportRow(o.receivedAt, o.status, o.acceptedAt, o.rejectionAuto,
                   o.subtotal, o.itemCount)
            from VendorOrder o
            where o.vendorId = :vendorId and o.receivedAt >= :from and o.receivedAt < :to
            """)
    List<ReportRow> reportRows(@Param("vendorId") UUID vendorId, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * The sales report's top items: [itemId, quantity, sales, name] of the 5 items with the most units in the orders
     * of one vendor received in {@code [from, to)} that were accepted and not cancelled; ties by sales, then id. The
     * name is the one of the item's most recent line.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(t.item_id) AS item_id, t.quantity, t.sales,
                   (SELECT i2.name FROM vendor_order_item i2 JOIN vendor_order o2 ON o2.order_id = i2.order_id
                    WHERE i2.item_id = t.item_id AND o2.vendor_id = UUID_TO_BIN(:vendorId)
                      AND o2.received_at >= :from AND o2.received_at < :to
                    ORDER BY o2.received_at DESC, i2.line_no LIMIT 1) AS name
            FROM (SELECT i.item_id, SUM(i.quantity) AS quantity, SUM(i.line_total) AS sales
                  FROM vendor_order_item i JOIN vendor_order o ON o.order_id = i.order_id
                  WHERE o.vendor_id = UUID_TO_BIN(:vendorId) AND o.received_at >= :from AND o.received_at < :to
                    AND o.accepted_at IS NOT NULL AND o.status <> 'CANCELLED'
                  GROUP BY i.item_id
                  ORDER BY quantity DESC, sales DESC, i.item_id
                  LIMIT :limit) t
            ORDER BY t.quantity DESC, t.sales DESC, t.item_id""", nativeQuery = true)
    List<Object[]> topItems(@Param("vendorId") String vendorId, @Param("from") Instant from, @Param("to") Instant to,
                            @Param("limit") int limit);
}
