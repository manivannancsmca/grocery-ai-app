package com.grocery.review.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The eligibility projection ({@code review_order}, {@code review_order_item}), written from order.events. Each event
 * fills only its own columns with an upsert, so OrderConfirmed and OrderDelivered may arrive in any order (a replay
 * writes the same values again).
 */
@Repository
public class OrderProjection {

    /** One projected order; {@code confirmedAt} null = its items are not known (no OrderConfirmed seen). */
    public record ProjectedOrder(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                                 String storeName, Instant confirmedAt, UUID partnerId, Instant deliveredAt) {

        public boolean delivered() {
            return deliveredAt != null;
        }
    }

    /** One distinct product or dish of an order, in order-line order. */
    public record OrderItem(UUID orderId, String itemType, UUID itemId, String name, int lineNo) {
    }

    private static final String COLUMNS = """
            BIN_TO_UUID(order_id) AS order_id, order_number, order_type, BIN_TO_UUID(customer_id) AS customer_id,
            BIN_TO_UUID(store_id) AS store_id, store_name, confirmed_at, BIN_TO_UUID(partner_id) AS partner_id,
            delivered_at""";

    private final JdbcClient jdbc;

    public OrderProjection(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** OrderConfirmed: the order's store, type and lines (lines are replaced as a whole). */
    public void confirmed(ProjectedOrder order, List<OrderItem> items, Instant now) {
        jdbc.sql("""
                        INSERT INTO review_order (order_id, order_number, order_type, customer_id, store_id, store_name,
                                                  confirmed_at, updated_at)
                        VALUES (UUID_TO_BIN(:orderId), :orderNumber, :orderType, UUID_TO_BIN(:customerId),
                                UUID_TO_BIN(:storeId), :storeName, :confirmedAt, :now) AS new
                        ON DUPLICATE KEY UPDATE order_number = new.order_number, order_type = new.order_type,
                            customer_id = new.customer_id, store_id = new.store_id, store_name = new.store_name,
                            confirmed_at = new.confirmed_at, updated_at = new.updated_at""")
                .param("orderId", order.orderId().toString())
                .param("orderNumber", order.orderNumber())
                .param("orderType", order.orderType())
                .param("customerId", order.customerId().toString())
                .param("storeId", order.storeId().toString())
                .param("storeName", order.storeName())
                .param("confirmedAt", utc(order.confirmedAt()))
                .param("now", utc(now))
                .update();
        jdbc.sql("DELETE FROM review_order_item WHERE order_id = UUID_TO_BIN(:orderId)")
                .param("orderId", order.orderId().toString())
                .update();
        for (OrderItem item : items) {
            jdbc.sql("""
                            INSERT IGNORE INTO review_order_item (order_id, item_type, item_id, name, line_no)
                            VALUES (UUID_TO_BIN(:orderId), :itemType, UUID_TO_BIN(:itemId), :name, :lineNo)""")
                    .param("orderId", order.orderId().toString())
                    .param("itemType", item.itemType())
                    .param("itemId", item.itemId().toString())
                    .param("name", item.name())
                    .param("lineNo", item.lineNo())
                    .update();
        }
    }

    /** OrderDelivered: who delivered it and when (also creates the order when OrderConfirmed was never seen). */
    public void delivered(ProjectedOrder order, Instant now) {
        jdbc.sql("""
                        INSERT INTO review_order (order_id, order_number, order_type, customer_id, store_id,
                                                  partner_id, delivered_at, updated_at)
                        VALUES (UUID_TO_BIN(:orderId), :orderNumber, :orderType, UUID_TO_BIN(:customerId),
                                UUID_TO_BIN(:storeId), UUID_TO_BIN(:partnerId), :deliveredAt, :now) AS new
                        ON DUPLICATE KEY UPDATE partner_id = new.partner_id, delivered_at = new.delivered_at,
                            updated_at = new.updated_at""")
                .param("orderId", order.orderId().toString())
                .param("orderNumber", order.orderNumber())
                .param("orderType", order.orderType())
                .param("customerId", order.customerId().toString())
                .param("storeId", order.storeId().toString())
                .param("partnerId", order.partnerId().toString())
                .param("deliveredAt", utc(order.deliveredAt()))
                .param("now", utc(now))
                .update();
    }

    public Optional<ProjectedOrder> find(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_order WHERE order_id = UUID_TO_BIN(:orderId)")
                .param("orderId", orderId.toString())
                .query(OrderProjection::order)
                .optional();
    }

    /**
     * The order, row-locked until the transaction ends: submissions for one order are serialised, so the
     * "already reviewed" check cannot race.
     */
    public Optional<ProjectedOrder> findForUpdate(UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM review_order WHERE order_id = UUID_TO_BIN(:orderId) FOR UPDATE")
                .param("orderId", orderId.toString())
                .query(OrderProjection::order)
                .optional();
    }

    /** The customer's orders delivered at or after {@code since}, newest delivery first. */
    public List<ProjectedOrder> deliveredSince(UUID customerId, Instant since, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                         FROM review_order
                        WHERE customer_id = UUID_TO_BIN(:customerId) AND delivered_at >= :since
                        ORDER BY delivered_at DESC, order_id DESC
                        LIMIT :limit""")
                .param("customerId", customerId.toString())
                .param("since", utc(since))
                .param("limit", limit)
                .query(OrderProjection::order)
                .list();
    }

    /** The items of these orders, grouped by order, each in line order. */
    public Map<UUID, List<OrderItem>> items(Collection<UUID> orderIds) {
        Map<UUID, List<OrderItem>> byOrder = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return byOrder;
        }
        List<byte[]> ids = orderIds.stream().map(OrderProjection::bytes).toList();
        jdbc.sql("""
                        SELECT BIN_TO_UUID(order_id) AS order_id, item_type, BIN_TO_UUID(item_id) AS item_id, name, line_no
                        FROM review_order_item WHERE order_id IN (:ids) ORDER BY order_id, line_no""")
                .param("ids", ids)
                .query((rs, n) -> new OrderItem(UUID.fromString(rs.getString("order_id")), rs.getString("item_type"),
                        UUID.fromString(rs.getString("item_id")), rs.getString("name"), rs.getInt("line_no")))
                .list()
                .forEach(item -> byOrder.computeIfAbsent(item.orderId(), id -> new ArrayList<>()).add(item));
        return byOrder;
    }

    /** One item of an order, if the order has it. */
    public Optional<OrderItem> item(UUID orderId, String itemType, UUID itemId) {
        return jdbc.sql("""
                        SELECT BIN_TO_UUID(order_id) AS order_id, item_type, BIN_TO_UUID(item_id) AS item_id, name, line_no
                        FROM review_order_item
                        WHERE order_id = UUID_TO_BIN(:orderId) AND item_type = :itemType AND item_id = UUID_TO_BIN(:itemId)""")
                .param("orderId", orderId.toString())
                .param("itemType", itemType)
                .param("itemId", itemId.toString())
                .query((rs, n) -> new OrderItem(UUID.fromString(rs.getString("order_id")), rs.getString("item_type"),
                        UUID.fromString(rs.getString("item_id")), rs.getString("name"), rs.getInt("line_no")))
                .optional();
    }

    private static ProjectedOrder order(ResultSet rs, int rowNum) throws SQLException {
        String partner = rs.getString("partner_id");
        return new ProjectedOrder(
                UUID.fromString(rs.getString("order_id")),
                rs.getString("order_number"),
                rs.getString("order_type"),
                UUID.fromString(rs.getString("customer_id")),
                UUID.fromString(rs.getString("store_id")),
                rs.getString("store_name"),
                instant(rs.getTimestamp("confirmed_at")),
                partner == null ? null : UUID.fromString(partner),
                instant(rs.getTimestamp("delivered_at")));
    }

    static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}
