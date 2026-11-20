package com.grocery.reporting.facts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.grocery.reporting.facts.Sql.bytes;
import static com.grocery.reporting.facts.Sql.utc;

/**
 * The fact writers. Every statement is idempotent and commutative, so duplicates and any arrival order across topics
 * converge to the same rows (MySQL 8.4 row-alias upserts: {@code INSERT … AS new ON DUPLICATE KEY UPDATE}).
 *
 * <p>MySQL evaluates the {@code ON DUPLICATE KEY UPDATE} assignments left to right and later ones see the values set by
 * earlier ones: the column a guard compares ({@code name_at}, {@code summary_version}, {@code last_submitted_at}) is
 * therefore always assigned <b>last</b>.
 */
@Repository
public class FactsRepository {

    private final JdbcClient jdbc;

    public FactsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- orders

    public void upsertOrder(OrderUpdate u, Instant now) {
        jdbc.sql("""
                        INSERT INTO order_fact (order_id, order_number, order_type, customer_id, store_id, store_name,
                            currency, subtotal, delivery_fee, discount, delivery_fee_discount, total, coupon_code,
                            coupon_name, coupon_funded_by,
                            created_at, confirmed_at, cancelled_at, cancel_reason, cancelled_by, cancelled_from_status,
                            rejected_at, reject_reason, delivered_at, delivery_failed_at, delivery_failure_reason,
                            partner_id, captured_amount, captured_at, capture_late, updated_at)
                        VALUES (:orderId, :orderNumber, :orderType, :customerId, :storeId, :storeName,
                            :currency, :subtotal, :deliveryFee, :discount, :deliveryFeeDiscount, :total, :couponCode,
                            :couponName, :couponFundedBy,
                            :createdAt, :confirmedAt, :cancelledAt, :cancelReason, :cancelledBy, :cancelledFromStatus,
                            :rejectedAt, :rejectReason, :deliveredAt, :deliveryFailedAt, :deliveryFailureReason,
                            :partnerId, :capturedAmount, :capturedAt, :captureLate, :updatedAt) AS new
                        ON DUPLICATE KEY UPDATE
                            order_number = COALESCE(new.order_number, order_fact.order_number),
                            order_type = COALESCE(new.order_type, order_fact.order_type),
                            customer_id = COALESCE(new.customer_id, order_fact.customer_id),
                            store_id = COALESCE(new.store_id, order_fact.store_id),
                            store_name = COALESCE(new.store_name, order_fact.store_name),
                            currency = COALESCE(new.currency, order_fact.currency),
                            subtotal = COALESCE(new.subtotal, order_fact.subtotal),
                            delivery_fee = COALESCE(new.delivery_fee, order_fact.delivery_fee),
                            discount = COALESCE(new.discount, order_fact.discount),
                            delivery_fee_discount = COALESCE(new.delivery_fee_discount, order_fact.delivery_fee_discount),
                            total = COALESCE(new.total, order_fact.total),
                            coupon_code = COALESCE(new.coupon_code, order_fact.coupon_code),
                            coupon_name = COALESCE(new.coupon_name, order_fact.coupon_name),
                            coupon_funded_by = COALESCE(new.coupon_funded_by, order_fact.coupon_funded_by),
                            created_at = COALESCE(order_fact.created_at, new.created_at),
                            confirmed_at = COALESCE(order_fact.confirmed_at, new.confirmed_at),
                            cancel_reason = IF(order_fact.cancelled_at IS NULL, new.cancel_reason, order_fact.cancel_reason),
                            cancelled_by = IF(order_fact.cancelled_at IS NULL, new.cancelled_by, order_fact.cancelled_by),
                            cancelled_from_status = IF(order_fact.cancelled_at IS NULL, new.cancelled_from_status,
                                order_fact.cancelled_from_status),
                            cancelled_at = COALESCE(order_fact.cancelled_at, new.cancelled_at),
                            reject_reason = IF(order_fact.rejected_at IS NULL, new.reject_reason, order_fact.reject_reason),
                            rejected_at = COALESCE(order_fact.rejected_at, new.rejected_at),
                            delivered_at = COALESCE(order_fact.delivered_at, new.delivered_at),
                            delivery_failure_reason = IF(order_fact.delivery_failed_at IS NULL,
                                new.delivery_failure_reason, order_fact.delivery_failure_reason),
                            delivery_failed_at = COALESCE(order_fact.delivery_failed_at, new.delivery_failed_at),
                            partner_id = COALESCE(new.partner_id, order_fact.partner_id),
                            captured_amount = IF(order_fact.captured_at IS NULL, new.captured_amount,
                                order_fact.captured_amount),
                            capture_late = IF(order_fact.captured_at IS NULL, new.capture_late, order_fact.capture_late),
                            captured_at = COALESCE(order_fact.captured_at, new.captured_at),
                            updated_at = new.updated_at""")
                .param("orderId", bytes(u.orderId))
                .param("orderNumber", u.orderNumber)
                .param("orderType", u.orderType)
                .param("customerId", bytes(u.customerId))
                .param("storeId", bytes(u.storeId))
                .param("storeName", u.storeName)
                .param("currency", u.currency)
                .param("subtotal", u.subtotal)
                .param("deliveryFee", u.deliveryFee)
                .param("discount", u.discount)
                .param("deliveryFeeDiscount", u.deliveryFeeDiscount)
                .param("total", u.total)
                .param("couponCode", u.couponCode)
                .param("couponName", u.couponName)
                .param("couponFundedBy", u.couponFundedBy)
                .param("createdAt", utc(u.createdAt))
                .param("confirmedAt", utc(u.confirmedAt))
                .param("cancelledAt", utc(u.cancelledAt))
                .param("cancelReason", u.cancelReason)
                .param("cancelledBy", u.cancelledBy)
                .param("cancelledFromStatus", u.cancelledFromStatus)
                .param("rejectedAt", utc(u.rejectedAt))
                .param("rejectReason", u.rejectReason)
                .param("deliveredAt", utc(u.deliveredAt))
                .param("deliveryFailedAt", utc(u.deliveryFailedAt))
                .param("deliveryFailureReason", u.deliveryFailureReason)
                .param("partnerId", bytes(u.partnerId))
                .param("capturedAmount", u.capturedAmount)
                .param("capturedAt", utc(u.capturedAt))
                .param("captureLate", u.captureLate)
                .param("updatedAt", utc(now))
                .update();
    }

    /** One order line as the order events carry it. */
    public record Line(UUID itemId, String itemType, String name, int quantity, BigDecimal unitPrice,
                       BigDecimal lineTotal) {
    }

    /** The order's lines are those of the latest event that lists them (OrderCreated and OrderConfirmed agree). */
    public void replaceLines(UUID orderId, List<Line> lines) {
        jdbc.sql("DELETE FROM order_line_fact WHERE order_id = :orderId").param("orderId", bytes(orderId)).update();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            jdbc.sql("""
                            INSERT INTO order_line_fact (order_id, line_no, item_id, item_type, name, quantity,
                                unit_price, line_total)
                            VALUES (:orderId, :lineNo, :itemId, :itemType, :name, :quantity, :unitPrice, :lineTotal)""")
                    .param("orderId", bytes(orderId))
                    .param("lineNo", i)
                    .param("itemId", bytes(line.itemId()))
                    .param("itemType", line.itemType())
                    .param("name", line.name())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPrice())
                    .param("lineTotal", line.lineTotal())
                    .update();
        }
    }

    // ---------------------------------------------------------------- names (the newest event time wins)

    public void upsertStore(UUID storeId, String storeType, String name, Instant at) {
        jdbc.sql("""
                        INSERT INTO store_dim (store_id, store_type, store_name, name_at)
                        VALUES (:storeId, :storeType, :name, :at) AS new
                        ON DUPLICATE KEY UPDATE
                            store_type = COALESCE(new.store_type, store_dim.store_type),
                            store_name = IF(new.name_at >= store_dim.name_at, new.store_name, store_dim.store_name),
                            name_at = GREATEST(store_dim.name_at, new.name_at)""")
                .param("storeId", bytes(storeId))
                .param("storeType", storeType)
                .param("name", name)
                .param("at", utc(at))
                .update();
    }

    public void upsertItem(UUID itemId, String itemType, UUID storeId, String name, Instant at) {
        jdbc.sql("""
                        INSERT INTO item_dim (item_id, item_type, store_id, name, name_at)
                        VALUES (:itemId, :itemType, :storeId, :name, :at) AS new
                        ON DUPLICATE KEY UPDATE
                            item_type = COALESCE(new.item_type, item_dim.item_type),
                            store_id = COALESCE(new.store_id, item_dim.store_id),
                            name = IF(new.name_at >= item_dim.name_at, new.name, item_dim.name),
                            name_at = GREATEST(item_dim.name_at, new.name_at)""")
                .param("itemId", bytes(itemId))
                .param("itemType", itemType)
                .param("storeId", bytes(storeId))
                .param("name", name)
                .param("at", utc(at))
                .update();
    }

    public void upsertPartner(UUID partnerId, String name, Instant at) {
        jdbc.sql("""
                        INSERT INTO partner_dim (partner_id, name, name_at)
                        VALUES (:partnerId, :name, :at) AS new
                        ON DUPLICATE KEY UPDATE
                            name = IF(new.name_at >= partner_dim.name_at, new.name, partner_dim.name),
                            name_at = GREATEST(partner_dim.name_at, new.name_at)""")
                .param("partnerId", bytes(partnerId))
                .param("name", name)
                .param("at", utc(at))
                .update();
    }

    // ---------------------------------------------------------------- refunds and returns

    /** One row per refundId: a second delivery of the same refund (another event id) changes nothing. */
    public void insertRefund(UUID refundId, UUID orderId, UUID paymentId, UUID returnId, BigDecimal amount,
                             String reason, Instant refundedAt) {
        jdbc.sql("""
                        INSERT IGNORE INTO refund_fact (refund_id, order_id, payment_id, return_id, amount, reason,
                            refunded_at)
                        VALUES (:refundId, :orderId, :paymentId, :returnId, :amount, :reason, :refundedAt)""")
                .param("refundId", bytes(refundId))
                .param("orderId", bytes(orderId))
                .param("paymentId", bytes(paymentId))
                .param("returnId", bytes(returnId))
                .param("amount", amount)
                .param("reason", reason)
                .param("refundedAt", utc(refundedAt))
                .update();
    }

    public record ReturnRow(UUID returnId, String returnNumber, UUID orderId, String orderType, UUID storeId,
                            UUID customerId, BigDecimal refundAmount, Boolean restock, String decidedByRole,
                            Instant approvedAt) {
    }

    public record ReturnLineRow(UUID itemId, String name, int quantity, BigDecimal refundAmount) {
    }

    /** One row per returnId (and its lines): repeated approvals of the same return change nothing. */
    public void insertReturn(ReturnRow row, List<ReturnLineRow> lines) {
        int inserted = jdbc.sql("""
                        INSERT IGNORE INTO return_fact (return_id, return_number, order_id, order_type, store_id,
                            customer_id, refund_amount, restock, decided_by_role, approved_at)
                        VALUES (:returnId, :returnNumber, :orderId, :orderType, :storeId, :customerId, :refundAmount,
                            :restock, :decidedByRole, :approvedAt)""")
                .param("returnId", bytes(row.returnId()))
                .param("returnNumber", row.returnNumber())
                .param("orderId", bytes(row.orderId()))
                .param("orderType", row.orderType())
                .param("storeId", bytes(row.storeId()))
                .param("customerId", bytes(row.customerId()))
                .param("refundAmount", row.refundAmount())
                .param("restock", row.restock())
                .param("decidedByRole", row.decidedByRole())
                .param("approvedAt", utc(row.approvedAt()))
                .update();
        if (inserted == 0) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            ReturnLineRow line = lines.get(i);
            jdbc.sql("""
                            INSERT IGNORE INTO return_line_fact (return_id, line_no, item_id, name, quantity,
                                refund_amount)
                            VALUES (:returnId, :lineNo, :itemId, :name, :quantity, :refundAmount)""")
                    .param("returnId", bytes(row.returnId()))
                    .param("lineNo", i)
                    .param("itemId", bytes(line.itemId()))
                    .param("name", line.name())
                    .param("quantity", line.quantity())
                    .param("refundAmount", line.refundAmount())
                    .update();
        }
    }

    // ---------------------------------------------------------------- deliveries

    /**
     * A delivery event: {@code assignedAt} for DeliveryPartnerAssigned (one more assignment; the first and the latest
     * are kept), {@code pickedUpAt} / {@code deliveredAt} for the later steps (the first value wins).
     */
    public void upsertDelivery(UUID deliveryId, UUID orderId, String orderNumber, UUID partnerId, Instant assignedAt,
                               Instant pickedUpAt, Instant deliveredAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO delivery_fact (delivery_id, order_id, order_number, partner_id, assignments,
                            first_assigned_at, assigned_at, picked_up_at, delivered_at, updated_at)
                        VALUES (:deliveryId, :orderId, :orderNumber, :partnerId, :assignments,
                            :assignedAt, :assignedAt, :pickedUpAt, :deliveredAt, :updatedAt) AS new
                        ON DUPLICATE KEY UPDATE
                            order_number = COALESCE(new.order_number, delivery_fact.order_number),
                            partner_id = CASE
                                WHEN new.assigned_at IS NULL THEN COALESCE(new.partner_id, delivery_fact.partner_id)
                                WHEN delivery_fact.assigned_at IS NULL OR new.assigned_at >= delivery_fact.assigned_at
                                    THEN COALESCE(new.partner_id, delivery_fact.partner_id)
                                ELSE delivery_fact.partner_id END,
                            assignments = delivery_fact.assignments + new.assignments,
                            first_assigned_at = LEAST(COALESCE(delivery_fact.first_assigned_at, new.first_assigned_at),
                                COALESCE(new.first_assigned_at, delivery_fact.first_assigned_at)),
                            assigned_at = GREATEST(COALESCE(delivery_fact.assigned_at, new.assigned_at),
                                COALESCE(new.assigned_at, delivery_fact.assigned_at)),
                            picked_up_at = COALESCE(delivery_fact.picked_up_at, new.picked_up_at),
                            delivered_at = COALESCE(delivery_fact.delivered_at, new.delivered_at),
                            updated_at = new.updated_at""")
                .param("deliveryId", bytes(deliveryId))
                .param("orderId", bytes(orderId))
                .param("orderNumber", orderNumber)
                .param("partnerId", bytes(partnerId))
                .param("assignments", assignedAt == null ? 0 : 1)
                .param("assignedAt", utc(assignedAt))
                .param("pickedUpAt", utc(pickedUpAt))
                .param("deliveredAt", utc(deliveredAt))
                .param("updatedAt", utc(now))
                .update();
    }

    // ---------------------------------------------------------------- reviews and ratings

    public record ReviewRow(UUID reviewId, String targetType, UUID targetId, UUID storeId, UUID customerId,
                            UUID orderId, int rating, String status, String flagReason, Instant submittedAt) {
    }

    /** First submission creates the row; an edit (same reviewId) updates rating and status if it is newer. */
    public void upsertReview(ReviewRow row) {
        jdbc.sql("""
                        INSERT INTO review_fact (review_id, target_type, target_id, store_id, customer_id, order_id,
                            rating, status, flag_reason, submissions, first_submitted_at, last_submitted_at)
                        VALUES (:reviewId, :targetType, :targetId, :storeId, :customerId, :orderId,
                            :rating, :status, :flagReason, 1, :submittedAt, :submittedAt) AS new
                        ON DUPLICATE KEY UPDATE
                            rating = IF(new.last_submitted_at >= review_fact.last_submitted_at, new.rating,
                                review_fact.rating),
                            status = IF(new.last_submitted_at >= review_fact.last_submitted_at, new.status,
                                review_fact.status),
                            flag_reason = IF(new.last_submitted_at >= review_fact.last_submitted_at, new.flag_reason,
                                review_fact.flag_reason),
                            submissions = review_fact.submissions + 1,
                            first_submitted_at = LEAST(review_fact.first_submitted_at, new.first_submitted_at),
                            last_submitted_at = GREATEST(review_fact.last_submitted_at, new.last_submitted_at)""")
                .param("reviewId", bytes(row.reviewId()))
                .param("targetType", row.targetType())
                .param("targetId", bytes(row.targetId()))
                .param("storeId", bytes(row.storeId()))
                .param("customerId", bytes(row.customerId()))
                .param("orderId", bytes(row.orderId()))
                .param("rating", row.rating())
                .param("status", row.status())
                .param("flagReason", row.flagReason())
                .param("submittedAt", utc(row.submittedAt()))
                .update();
    }

    public record RatingRow(String targetType, UUID targetId, BigDecimal averageRating, int ratingCount,
                            List<Integer> distribution, long summaryVersion, Instant updatedAt) {
    }

    /** Version-guarded: a summary older than (or as old as) the stored one changes nothing. */
    public void upsertRating(RatingRow row) {
        jdbc.sql("""
                        INSERT INTO rating_fact (target_type, target_id, average_rating, rating_count, stars_1, stars_2,
                            stars_3, stars_4, stars_5, summary_version, updated_at)
                        VALUES (:targetType, :targetId, :average, :count, :s1, :s2, :s3, :s4, :s5, :version,
                            :updatedAt) AS new
                        ON DUPLICATE KEY UPDATE
                            average_rating = IF(new.summary_version > rating_fact.summary_version, new.average_rating,
                                rating_fact.average_rating),
                            rating_count = IF(new.summary_version > rating_fact.summary_version, new.rating_count,
                                rating_fact.rating_count),
                            stars_1 = IF(new.summary_version > rating_fact.summary_version, new.stars_1, rating_fact.stars_1),
                            stars_2 = IF(new.summary_version > rating_fact.summary_version, new.stars_2, rating_fact.stars_2),
                            stars_3 = IF(new.summary_version > rating_fact.summary_version, new.stars_3, rating_fact.stars_3),
                            stars_4 = IF(new.summary_version > rating_fact.summary_version, new.stars_4, rating_fact.stars_4),
                            stars_5 = IF(new.summary_version > rating_fact.summary_version, new.stars_5, rating_fact.stars_5),
                            updated_at = IF(new.summary_version > rating_fact.summary_version, new.updated_at,
                                rating_fact.updated_at),
                            summary_version = GREATEST(rating_fact.summary_version, new.summary_version)""")
                .param("targetType", row.targetType())
                .param("targetId", bytes(row.targetId()))
                .param("average", row.averageRating())
                .param("count", row.ratingCount())
                .param("s1", row.distribution().get(0))
                .param("s2", row.distribution().get(1))
                .param("s3", row.distribution().get(2))
                .param("s4", row.distribution().get(3))
                .param("s5", row.distribution().get(4))
                .param("version", row.summaryVersion())
                .param("updatedAt", utc(row.updatedAt()))
                .update();
    }

    // ---------------------------------------------------------------- purchasing

    public record ReceiptRow(UUID receiptId, UUID vendorId, UUID purchaseOrderId, String purchaseOrderNumber,
                             UUID supplierId, int units, BigDecimal cost, Instant receivedAt) {
    }

    public record ReceiptLineRow(UUID productId, int quantity, BigDecimal unitCost) {
    }

    /** One row per receiptId (and its lines): a receipt is immutable. */
    public void insertReceipt(ReceiptRow row, List<ReceiptLineRow> lines) {
        int inserted = jdbc.sql("""
                        INSERT IGNORE INTO receipt_fact (receipt_id, vendor_id, purchase_order_id,
                            purchase_order_number, supplier_id, units, cost, received_at)
                        VALUES (:receiptId, :vendorId, :purchaseOrderId, :purchaseOrderNumber, :supplierId, :units,
                            :cost, :receivedAt)""")
                .param("receiptId", bytes(row.receiptId()))
                .param("vendorId", bytes(row.vendorId()))
                .param("purchaseOrderId", bytes(row.purchaseOrderId()))
                .param("purchaseOrderNumber", row.purchaseOrderNumber())
                .param("supplierId", bytes(row.supplierId()))
                .param("units", row.units())
                .param("cost", row.cost())
                .param("receivedAt", utc(row.receivedAt()))
                .update();
        if (inserted == 0) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            ReceiptLineRow line = lines.get(i);
            jdbc.sql("""
                            INSERT IGNORE INTO receipt_line_fact (receipt_id, line_no, product_id, quantity, unit_cost)
                            VALUES (:receiptId, :lineNo, :productId, :quantity, :unitCost)""")
                    .param("receiptId", bytes(row.receiptId()))
                    .param("lineNo", i)
                    .param("productId", bytes(line.productId()))
                    .param("quantity", line.quantity())
                    .param("unitCost", line.unitCost())
                    .update();
        }
    }
}
