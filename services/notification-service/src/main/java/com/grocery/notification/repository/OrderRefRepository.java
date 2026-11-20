package com.grocery.notification.repository;

import com.grocery.notification.entity.OrderRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code order_ref} projection. {@code OrderCreated} fills it; every other order event updates the status and
 * fills fields that are still unknown (an order whose OrderCreated was never seen still gets a row from its later
 * events). Events of one order are keyed by its id, so they arrive in order on one partition.
 */
@Repository
public class OrderRefRepository {

    /** A (re)delivered OrderCreated never moves the status back. */
    private static final String CREATED_SQL = """
            INSERT INTO order_ref (order_id, order_number, order_type, customer_id, store_id, store_name, total, status,
                                   updated_at)
            VALUES (:orderId, :orderNumber, :orderType, :customerId, :storeId, :storeName, :total, :status, :updatedAt)
                AS new
            ON DUPLICATE KEY UPDATE order_number = new.order_number,
                                    order_type = COALESCE(new.order_type, order_ref.order_type),
                                    customer_id = new.customer_id,
                                    store_id = COALESCE(new.store_id, order_ref.store_id),
                                    store_name = COALESCE(new.store_name, order_ref.store_name),
                                    total = COALESCE(new.total, order_ref.total),
                                    updated_at = new.updated_at
            """;
    private static final String STATUS_SQL = """
            INSERT INTO order_ref (order_id, order_number, order_type, customer_id, store_id, store_name, total, status,
                                   updated_at)
            VALUES (:orderId, :orderNumber, :orderType, :customerId, :storeId, :storeName, :total, :status, :updatedAt)
                AS new
            ON DUPLICATE KEY UPDATE order_number = COALESCE(new.order_number, order_ref.order_number),
                                    order_type = COALESCE(order_ref.order_type, new.order_type),
                                    customer_id = COALESCE(order_ref.customer_id, new.customer_id),
                                    store_id = COALESCE(order_ref.store_id, new.store_id),
                                    store_name = COALESCE(order_ref.store_name, new.store_name),
                                    total = COALESCE(new.total, order_ref.total),
                                    status = new.status,
                                    updated_at = new.updated_at
            """;
    private static final String FIND_SQL = """
            SELECT order_id, order_number, order_type, customer_id, store_id, store_name, total, status, delivery_id,
                   partner_id
            FROM order_ref WHERE order_id = :orderId""";
    /** Phase 9: DeliveryPartnerAssigned (a later assignment replaces an earlier one). */
    private static final String ASSIGN_SQL = """
            UPDATE order_ref SET delivery_id = :deliveryId, partner_id = :partnerId, updated_at = :updatedAt
            WHERE order_id = :orderId""";
    /** Phase 9: DeliveryPartnerUnassigned / DeliveryCancelled — only the partner still recorded is cleared. */
    private static final String UNASSIGN_SQL = """
            UPDATE order_ref SET partner_id = NULL, updated_at = :updatedAt
            WHERE order_id = :orderId AND partner_id = :partnerId""";

    private final JdbcClient jdbc;

    public OrderRefRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** OrderCreated. */
    public void created(OrderRef ref, Instant now) {
        write(CREATED_SQL, ref, now);
    }

    /** Any later order event: the new status, plus whatever the row does not know yet. */
    public void statusChanged(OrderRef ref, Instant now) {
        write(STATUS_SQL, ref, now);
    }

    public Optional<OrderRef> find(UUID orderId) {
        return find(FIND_SQL, orderId);
    }

    /** The order's delivery partner (Phase 9); false when the order is not known. */
    public boolean assignPartner(UUID orderId, UUID deliveryId, UUID partnerId, Instant now) {
        return jdbc.sql(ASSIGN_SQL)
                .param("orderId", UuidBytes.bytes(orderId))
                .param("deliveryId", UuidBytes.bytes(deliveryId))
                .param("partnerId", UuidBytes.bytes(partnerId))
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update() == 1;
    }

    /** Clears the order's delivery partner if it is still {@code partnerId}; false when it was not. */
    public boolean unassignPartner(UUID orderId, UUID partnerId, Instant now) {
        return jdbc.sql(UNASSIGN_SQL)
                .param("orderId", UuidBytes.bytes(orderId))
                .param("partnerId", UuidBytes.bytes(partnerId))
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update() == 1;
    }

    /**
     * A locking read for payment events ({@code FOR SHARE}): an OrderCreated being written by a concurrent
     * transaction is waited for, and a missing row is gap-locked, so that OrderCreated waits until a payment event
     * parked meanwhile is committed — and then finds it when it drains the order's parked events.
     */
    public Optional<OrderRef> findLocked(UUID orderId) {
        return find(FIND_SQL + " FOR SHARE", orderId);
    }

    private Optional<OrderRef> find(String sql, UUID orderId) {
        return jdbc.sql(sql)
                .param("orderId", UuidBytes.bytes(orderId))
                .query((rs, rowNum) -> new OrderRef(
                        UuidBytes.uuid(rs.getBytes("order_id")),
                        rs.getString("order_number"),
                        rs.getString("order_type"),
                        UuidBytes.uuid(rs.getBytes("customer_id")),
                        UuidBytes.uuid(rs.getBytes("store_id")),
                        rs.getString("store_name"),
                        rs.getBigDecimal("total"),
                        rs.getString("status"),
                        UuidBytes.uuid(rs.getBytes("delivery_id")),
                        UuidBytes.uuid(rs.getBytes("partner_id"))))
                .optional();
    }

    private void write(String sql, OrderRef ref, Instant now) {
        jdbc.sql(sql)
                .param("orderId", UuidBytes.bytes(ref.orderId()))
                .param("orderNumber", ref.orderNumber())
                .param("orderType", ref.orderType())
                .param("customerId", UuidBytes.bytes(ref.customerId()))
                .param("storeId", UuidBytes.bytes(ref.storeId()))
                .param("storeName", StoreOwnerRepository.blankToNull(ref.storeName()))
                .param("total", ref.total())
                .param("status", ref.status())
                .param("updatedAt", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
