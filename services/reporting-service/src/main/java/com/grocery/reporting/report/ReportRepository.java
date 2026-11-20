package com.grocery.reporting.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.reporting.facts.Sql.bytes;
import static com.grocery.reporting.facts.Sql.instant;
import static com.grocery.reporting.facts.Sql.utc;
import static com.grocery.reporting.facts.Sql.uuid;

/**
 * The report queries: aggregates over the facts, joined at query time. Ranges are UTC instants
 * {@code [start, end)}; per-day grouping shifts the UTC timestamp by India's offset ({@code :offset} minutes) and
 * takes its date. Optional filters are appended only when present, so the indexes on
 * {@code (store_id, placed_at)} / {@code (vendor_id, received_at)} apply.
 */
@Repository
public class ReportRepository {

    private static final String DAY = "DATE(DATE_ADD(%s, INTERVAL :offset MINUTE))";

    private final JdbcClient jdbc;

    public ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- overview

    /**
     * Orders placed in the range; GMV / discounts / coupon orders of the confirmed ones. {@code platformFunded} /
     * {@code storeFunded}: their coupon discounts (items + delivery fee) by who funds the coupon; orders whose events
     * did not carry the funding (placed before schemas 1.7.0) are in neither.
     */
    public record OrderTotals(long placed, long confirmed, long cancelled, long rejected, BigDecimal gmv,
                              BigDecimal discount, BigDecimal deliveryFeeDiscount, long couponOrders,
                              BigDecimal platformFunded, BigDecimal storeFunded) {
    }

    public OrderTotals orderTotals(Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS placed,
                               COUNT(confirmed_at) AS confirmed,
                               COUNT(cancelled_at) AS cancelled,
                               COUNT(rejected_at) AS rejected,
                               SUM(CASE WHEN confirmed_at IS NOT NULL THEN total END) AS gmv,
                               SUM(CASE WHEN confirmed_at IS NOT NULL THEN discount END) AS discount,
                               SUM(CASE WHEN confirmed_at IS NOT NULL THEN delivery_fee_discount END) AS dfd,
                               COUNT(CASE WHEN confirmed_at IS NOT NULL AND coupon_code IS NOT NULL THEN 1 END)
                                   AS coupon_orders,
                               SUM(CASE WHEN confirmed_at IS NOT NULL AND coupon_funded_by = 'PLATFORM'
                                   THEN COALESCE(discount, 0) + COALESCE(delivery_fee_discount, 0) END) AS platform,
                               SUM(CASE WHEN confirmed_at IS NOT NULL AND coupon_funded_by = 'STORE'
                                   THEN COALESCE(discount, 0) + COALESCE(delivery_fee_discount, 0) END) AS store
                        FROM order_fact
                        WHERE placed_at >= :start AND placed_at < :end""")
                .param("start", utc(start))
                .param("end", utc(end))
                .query((rs, n) -> new OrderTotals(rs.getLong("placed"), rs.getLong("confirmed"),
                        rs.getLong("cancelled"), rs.getLong("rejected"), money(rs, "gmv"), money(rs, "discount"),
                        money(rs, "dfd"), rs.getLong("coupon_orders"), money(rs, "platform"), money(rs, "store")))
                .single();
    }

    public record CountAndAmount(long count, BigDecimal amount) {
    }

    /** Refunds completed in the range, except LATE_CAPTURE (the order was never confirmed, so never in GMV). */
    public CountAndAmount refundTotals(Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS n, SUM(amount) AS amount
                        FROM refund_fact
                        WHERE refunded_at >= :start AND refunded_at < :end
                          AND (reason IS NULL OR reason <> 'LATE_CAPTURE')""")
                .param("start", utc(start))
                .param("end", utc(end))
                .query((rs, n) -> new CountAndAmount(rs.getLong("n"), money(rs, "amount")))
                .single();
    }

    /** Returns approved in the range (their refund amounts as approved). */
    public CountAndAmount returnTotals(Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT COUNT(*) AS n, SUM(refund_amount) AS amount
                        FROM return_fact
                        WHERE approved_at >= :start AND approved_at < :end""")
                .param("start", utc(start))
                .param("end", utc(end))
                .query((rs, n) -> new CountAndAmount(rs.getLong("n"), money(rs, "amount")))
                .single();
    }

    /** Customers whose first confirmed order (ever, as far as the facts go) was placed in the range. */
    public long newCustomers(Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM (
                            SELECT customer_id, MIN(placed_at) AS first_order_at
                            FROM order_fact
                            WHERE confirmed_at IS NOT NULL AND customer_id IS NOT NULL AND placed_at IS NOT NULL
                            GROUP BY customer_id) first_orders
                        WHERE first_order_at >= :start AND first_order_at < :end""")
                .param("start", utc(start))
                .param("end", utc(end))
                .query(Long.class)
                .single();
    }

    // ---------------------------------------------------------------- daily sales

    public record SalesAggregate(long orders, BigDecimal gmv, BigDecimal discounts) {
    }

    public Map<LocalDate, SalesAggregate> dailySales(Instant start, Instant end, int offset, String type,
                                                     UUID storeId) {
        StringBuilder sql = new StringBuilder("SELECT " + DAY.formatted("placed_at") + """
                 AS day, COUNT(*) AS orders, SUM(total) AS gmv,
                       SUM(COALESCE(discount, 0) + COALESCE(delivery_fee_discount, 0)) AS discounts
                FROM order_fact
                WHERE confirmed_at IS NOT NULL AND placed_at >= :start AND placed_at < :end""");
        if (type != null) {
            sql.append(" AND order_type = :type");
        }
        if (storeId != null) {
            sql.append(" AND store_id = :storeId");
        }
        sql.append(" GROUP BY day");
        JdbcClient.StatementSpec statement = range(jdbc.sql(sql.toString()), start, end).param("offset", offset);
        if (type != null) {
            statement = statement.param("type", type);
        }
        if (storeId != null) {
            statement = statement.param("storeId", bytes(storeId));
        }
        Map<LocalDate, SalesAggregate> days = new HashMap<>();
        statement.query((ResultSet rs) -> {
            days.put(rs.getObject("day", LocalDate.class),
                    new SalesAggregate(rs.getLong("orders"), money(rs, "gmv"), money(rs, "discounts")));
        });
        return days;
    }

    /** Refunds per India date (LATE_CAPTURE excluded); with a filter, only refunds of matching known orders. */
    public Map<LocalDate, BigDecimal> dailyRefunds(Instant start, Instant end, int offset, String type,
                                                   UUID storeId) {
        boolean filtered = type != null || storeId != null;
        StringBuilder sql = new StringBuilder("SELECT " + DAY.formatted("r.refunded_at") + """
                 AS day, SUM(r.amount) AS amount
                FROM refund_fact r""");
        if (filtered) {
            sql.append(" JOIN order_fact o ON o.order_id = r.order_id");
        }
        sql.append(" WHERE r.refunded_at >= :start AND r.refunded_at < :end")
                .append(" AND (r.reason IS NULL OR r.reason <> 'LATE_CAPTURE')");
        if (type != null) {
            sql.append(" AND o.order_type = :type");
        }
        if (storeId != null) {
            sql.append(" AND o.store_id = :storeId");
        }
        sql.append(" GROUP BY day");
        JdbcClient.StatementSpec statement = range(jdbc.sql(sql.toString()), start, end).param("offset", offset);
        if (type != null) {
            statement = statement.param("type", type);
        }
        if (storeId != null) {
            statement = statement.param("storeId", bytes(storeId));
        }
        Map<LocalDate, BigDecimal> days = new HashMap<>();
        statement.query((ResultSet rs) -> {
            days.put(rs.getObject("day", LocalDate.class), money(rs, "amount"));
        });
        return days;
    }

    // ---------------------------------------------------------------- top stores / products

    public record StoreAggregate(UUID storeId, String storeName, String orderType, long orders, BigDecimal gmv) {
    }

    /** {@code byOrders}: order count first (then GMV), else GMV first (then order count); then the store id. */
    public List<StoreAggregate> topStores(Instant start, Instant end, String type, boolean byOrders, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.store_id, COALESCE(MAX(s.store_name), MAX(o.store_name)) AS store_name,
                       MAX(o.order_type) AS order_type, COUNT(*) AS orders, SUM(o.total) AS gmv
                FROM order_fact o LEFT JOIN store_dim s ON s.store_id = o.store_id
                WHERE o.confirmed_at IS NOT NULL AND o.store_id IS NOT NULL
                  AND o.placed_at >= :start AND o.placed_at < :end""");
        if (type != null) {
            sql.append(" AND o.order_type = :type");
        }
        sql.append(" GROUP BY o.store_id ORDER BY ")
                .append(byOrders ? "orders DESC, gmv DESC" : "gmv DESC, orders DESC")
                .append(", o.store_id LIMIT :limit");
        JdbcClient.StatementSpec statement = range(jdbc.sql(sql.toString()), start, end).param("limit", limit);
        if (type != null) {
            statement = statement.param("type", type);
        }
        return statement.query((rs, n) -> new StoreAggregate(uuid(rs, "store_id"), rs.getString("store_name"),
                rs.getString("order_type"), rs.getLong("orders"), money(rs, "gmv"))).list();
    }

    public record ProductAggregate(UUID itemId, String itemType, String name, UUID storeId, String storeName,
                                   long units, BigDecimal revenue, long orders) {
    }

    /** Lines of confirmed orders placed in the range, per item. */
    public List<ProductAggregate> topProducts(Instant start, Instant end, String type, boolean byRevenue, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.item_id, COALESCE(i.item_type, p.item_type) AS item_type, COALESCE(i.name, p.name) AS name,
                       COALESCE(i.store_id, p.store_id) AS store_id, s.store_name, p.units, p.revenue, p.orders
                FROM (SELECT l.item_id, MAX(l.item_type) AS item_type, MAX(l.name) AS name,
                             MAX(o.store_id) AS store_id, SUM(l.quantity) AS units, SUM(l.line_total) AS revenue,
                             COUNT(DISTINCT l.order_id) AS orders
                      FROM order_line_fact l JOIN order_fact o ON o.order_id = l.order_id
                      WHERE o.confirmed_at IS NOT NULL AND o.placed_at >= :start AND o.placed_at < :end""");
        if (type != null) {
            sql.append(" AND o.order_type = :type");
        }
        sql.append("""
                       GROUP BY l.item_id) p
                LEFT JOIN item_dim i ON i.item_id = p.item_id
                LEFT JOIN store_dim s ON s.store_id = COALESCE(i.store_id, p.store_id)
                ORDER BY\s""")
                .append(byRevenue ? "p.revenue DESC, p.units DESC" : "p.units DESC, p.revenue DESC")
                .append(", p.item_id LIMIT :limit");
        JdbcClient.StatementSpec statement = range(jdbc.sql(sql.toString()), start, end).param("limit", limit);
        if (type != null) {
            statement = statement.param("type", type);
        }
        return statement.query((rs, n) -> new ProductAggregate(uuid(rs, "item_id"), rs.getString("item_type"),
                rs.getString("name"), uuid(rs, "store_id"), rs.getString("store_name"), rs.getLong("units"),
                money(rs, "revenue"), rs.getLong("orders"))).list();
    }

    /** Units of these items returned (approved returns) from confirmed orders placed in the range. */
    public Map<UUID, Long> returnedUnits(Instant start, Instant end, List<UUID> itemIds) {
        Map<UUID, Long> units = new HashMap<>();
        if (itemIds.isEmpty()) {
            return units;
        }
        jdbc.sql("""
                        SELECT rl.item_id, SUM(rl.quantity) AS units
                        FROM return_line_fact rl
                        JOIN return_fact rt ON rt.return_id = rl.return_id
                        JOIN order_fact o ON o.order_id = rt.order_id
                        WHERE o.confirmed_at IS NOT NULL AND o.placed_at >= :start AND o.placed_at < :end
                          AND rl.item_id IN (:items)
                        GROUP BY rl.item_id""")
                .param("start", utc(start))
                .param("end", utc(end))
                .param("items", itemIds.stream().map(id -> (Object) bytes(id)).toList())
                .query((ResultSet rs) -> {
                    units.put(uuid(rs, "item_id"), rs.getLong("units"));
                });
        return units;
    }

    // ---------------------------------------------------------------- delivery performance

    /** Average durations in microseconds ({@code null} when no delivery had both times). */
    public record DeliveryAggregate(long deliveries, BigDecimal confirmedToAssigned, BigDecimal assignedToPickedUp,
                                    BigDecimal pickedUpToDelivered) {
    }

    private static final String DELIVERY_AVERAGES = """
            COUNT(*) AS deliveries,
            AVG(CASE WHEN o.confirmed_at IS NOT NULL AND d.first_assigned_at >= o.confirmed_at
                     THEN TIMESTAMPDIFF(MICROSECOND, o.confirmed_at, d.first_assigned_at) END) AS confirm_assign,
            AVG(CASE WHEN d.assigned_at IS NOT NULL AND d.picked_up_at >= d.assigned_at
                     THEN TIMESTAMPDIFF(MICROSECOND, d.assigned_at, d.picked_up_at) END) AS assign_pickup,
            AVG(CASE WHEN d.picked_up_at IS NOT NULL AND d.delivered_at >= d.picked_up_at
                     THEN TIMESTAMPDIFF(MICROSECOND, d.picked_up_at, d.delivered_at) END) AS pickup_deliver
            FROM delivery_fact d LEFT JOIN order_fact o ON o.order_id = d.order_id
            WHERE d.delivered_at >= :start AND d.delivered_at < :end""";

    /** Deliveries completed per India date (by their delivery time). */
    public Map<LocalDate, DeliveryAggregate> deliveryDays(Instant start, Instant end, int offset) {
        Map<LocalDate, DeliveryAggregate> days = new HashMap<>();
        range(jdbc.sql("SELECT " + DAY.formatted("d.delivered_at") + " AS day, " + DELIVERY_AVERAGES
                        + " GROUP BY day"), start, end)
                .param("offset", offset)
                .query((ResultSet rs) -> {
                    days.put(rs.getObject("day", LocalDate.class), deliveryAggregate(rs));
                });
        return days;
    }

    /** Failed deliveries (OrderDeliveryFailed) per India date. */
    public Map<LocalDate, Long> failedDeliveries(Instant start, Instant end, int offset) {
        Map<LocalDate, Long> days = new HashMap<>();
        range(jdbc.sql("SELECT " + DAY.formatted("delivery_failed_at") + """
                         AS day, COUNT(*) AS failed
                        FROM order_fact
                        WHERE delivery_failed_at >= :start AND delivery_failed_at < :end
                        GROUP BY day"""), start, end)
                .param("offset", offset)
                .query((ResultSet rs) -> {
                    days.put(rs.getObject("day", LocalDate.class), rs.getLong("failed"));
                });
        return days;
    }

    private static DeliveryAggregate deliveryAggregate(ResultSet rs) throws SQLException {
        return new DeliveryAggregate(rs.getLong("deliveries"), rs.getBigDecimal("confirm_assign"),
                rs.getBigDecimal("assign_pickup"), rs.getBigDecimal("pickup_deliver"));
    }

    // ---------------------------------------------------------------- promotions

    /** {@code couponName} / {@code fundedBy}: null when none of the code's orders carried them (all before 1.7.0). */
    public record CouponAggregate(String couponCode, String couponName, String fundedBy, long orders,
                                  BigDecimal discount, BigDecimal deliveryFeeDiscount, BigDecimal gmv, long stores) {
    }

    /**
     * Confirmed orders placed in the range with a coupon, per code; largest discount given first. The coupon's name
     * and funding are those of its newest order in the range that carries them (a coupon can be renamed; orders
     * placed before schemas 1.7.0 carry neither).
     */
    public List<CouponAggregate> coupons(Instant start, Instant end) {
        return range(jdbc.sql("""
                        WITH used AS (
                            SELECT coupon_code, coupon_name, coupon_funded_by, discount, delivery_fee_discount, total,
                                   store_id,
                                   ROW_NUMBER() OVER (PARTITION BY coupon_code
                                       ORDER BY coupon_funded_by IS NULL, placed_at DESC, order_id) AS newest
                            FROM order_fact
                            WHERE confirmed_at IS NOT NULL AND coupon_code IS NOT NULL
                              AND placed_at >= :start AND placed_at < :end)
                        SELECT coupon_code, MAX(CASE WHEN newest = 1 THEN coupon_name END) AS coupon_name,
                               MAX(CASE WHEN newest = 1 THEN coupon_funded_by END) AS funded_by,
                               COUNT(*) AS orders, SUM(COALESCE(discount, 0)) AS discount,
                               SUM(COALESCE(delivery_fee_discount, 0)) AS dfd, SUM(total) AS gmv,
                               COUNT(DISTINCT store_id) AS stores
                        FROM used
                        GROUP BY coupon_code
                        ORDER BY SUM(COALESCE(discount, 0) + COALESCE(delivery_fee_discount, 0)) DESC, orders DESC,
                                 coupon_code"""), start, end)
                .query((rs, n) -> new CouponAggregate(rs.getString("coupon_code"), rs.getString("coupon_name"),
                        rs.getString("funded_by"), rs.getLong("orders"), money(rs, "discount"), money(rs, "dfd"),
                        money(rs, "gmv"), rs.getLong("stores")))
                .list();
    }

    // ---------------------------------------------------------------- ratings

    public record ReviewCounts(long submitted, long published, long flagged) {
    }

    public ReviewCounts reviewCounts(Instant start, Instant end, String targetType) {
        String sql = """
                SELECT COUNT(*) AS submitted,
                       COUNT(CASE WHEN status = 'PUBLISHED' THEN 1 END) AS published,
                       COUNT(CASE WHEN status = 'FLAGGED' THEN 1 END) AS flagged
                FROM review_fact
                WHERE first_submitted_at >= :start AND first_submitted_at < :end""";
        JdbcClient.StatementSpec statement = range(jdbc.sql(targetType == null ? sql
                : sql + " AND target_type = :targetType"), start, end);
        if (targetType != null) {
            statement = statement.param("targetType", targetType);
        }
        return statement.query((rs, n) -> new ReviewCounts(rs.getLong("submitted"), rs.getLong("published"),
                rs.getLong("flagged"))).single();
    }

    public record RatingAggregate(String targetType, UUID targetId, String name, BigDecimal averageRating,
                                  long ratingCount, List<Integer> distribution, Instant updatedAt) {
    }

    /** The current summaries, lowest ({@code ascending}) or highest first; then more ratings first, then the id. */
    public List<RatingAggregate> ratings(String targetType, int minCount, boolean ascending, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.target_type, r.target_id, r.average_rating, r.rating_count, r.stars_1, r.stars_2, r.stars_3,
                       r.stars_4, r.stars_5, r.updated_at,
                       CASE WHEN r.target_type IN ('PRODUCT', 'MENU_ITEM') THEN i.name
                            WHEN r.target_type IN ('VENDOR', 'RESTAURANT') THEN s.store_name
                            WHEN r.target_type = 'DELIVERY_PARTNER' THEN p.name END AS name
                FROM rating_fact r
                LEFT JOIN item_dim i ON i.item_id = r.target_id
                LEFT JOIN store_dim s ON s.store_id = r.target_id
                LEFT JOIN partner_dim p ON p.partner_id = r.target_id
                WHERE r.rating_count >= :minCount""");
        if (targetType != null) {
            sql.append(" AND r.target_type = :targetType");
        }
        sql.append(ascending ? " ORDER BY r.average_rating ASC" : " ORDER BY r.average_rating DESC")
                .append(", r.rating_count DESC, r.target_type, r.target_id LIMIT :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("minCount", Math.max(1, minCount))
                .param("limit", limit);
        if (targetType != null) {
            statement = statement.param("targetType", targetType);
        }
        return statement.query((rs, n) -> new RatingAggregate(rs.getString("target_type"), uuid(rs, "target_id"),
                rs.getString("name"), rs.getBigDecimal("average_rating"), rs.getLong("rating_count"),
                List.of(rs.getInt("stars_1"), rs.getInt("stars_2"), rs.getInt("stars_3"), rs.getInt("stars_4"),
                        rs.getInt("stars_5")), instant(rs, "updated_at"))).list();
    }

    // ---------------------------------------------------------------- purchasing

    public record PurchasingAggregate(LocalDate date, UUID vendorId, String vendorName, long receipts,
                                      long purchaseOrders, long units, BigDecimal cost) {
    }

    /** Goods receipts per India date and vendor, in date order. */
    public List<PurchasingAggregate> purchasing(Instant start, Instant end, int offset, UUID vendorId) {
        StringBuilder sql = new StringBuilder("SELECT " + DAY.formatted("r.received_at") + """
                 AS day, r.vendor_id, MAX(s.store_name) AS vendor_name, COUNT(*) AS receipts,
                       COUNT(DISTINCT r.purchase_order_id) AS purchase_orders, SUM(r.units) AS units,
                       SUM(r.cost) AS cost
                FROM receipt_fact r LEFT JOIN store_dim s ON s.store_id = r.vendor_id
                WHERE r.received_at >= :start AND r.received_at < :end""");
        if (vendorId != null) {
            sql.append(" AND r.vendor_id = :vendorId");
        }
        sql.append(" GROUP BY day, r.vendor_id ORDER BY day, vendor_name, r.vendor_id");
        JdbcClient.StatementSpec statement = range(jdbc.sql(sql.toString()), start, end).param("offset", offset);
        if (vendorId != null) {
            statement = statement.param("vendorId", bytes(vendorId));
        }
        return statement.query((rs, n) -> new PurchasingAggregate(rs.getObject("day", LocalDate.class),
                uuid(rs, "vendor_id"), rs.getString("vendor_name"), rs.getLong("receipts"),
                rs.getLong("purchase_orders"), rs.getLong("units"), money(rs, "cost"))).list();
    }

    // ---------------------------------------------------------------- helpers

    private static JdbcClient.StatementSpec range(JdbcClient.StatementSpec statement, Instant start, Instant end) {
        return statement.param("start", utc(start)).param("end", utc(end));
    }

    /** A money column, 2 decimals; SQL NULL (an empty SUM) is 0.00. */
    static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
