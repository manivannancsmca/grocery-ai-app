package com.grocery.reporting;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.reporting.TestEvents.Order;
import com.grocery.reporting.TestEvents.ReceivedLine;
import com.grocery.reporting.TestEvents.ReturnedLine;
import com.grocery.reporting.facts.FactsService.Outcome;
import com.grocery.reporting.facts.Sql;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.grocery.reporting.TestEvents.deliveryCompleted;
import static com.grocery.reporting.TestEvents.goodsReceived;
import static com.grocery.reporting.TestEvents.grocery;
import static com.grocery.reporting.TestEvents.ist;
import static com.grocery.reporting.TestEvents.map;
import static com.grocery.reporting.TestEvents.money;
import static com.grocery.reporting.TestEvents.orderCancelled;
import static com.grocery.reporting.TestEvents.orderConfirmed;
import static com.grocery.reporting.TestEvents.orderCreated;
import static com.grocery.reporting.TestEvents.orderDelivered;
import static com.grocery.reporting.TestEvents.orderDeliveryFailed;
import static com.grocery.reporting.TestEvents.orderRejected;
import static com.grocery.reporting.TestEvents.partnerAssigned;
import static com.grocery.reporting.TestEvents.paymentCaptured;
import static com.grocery.reporting.TestEvents.pickedUp;
import static com.grocery.reporting.TestEvents.product;
import static com.grocery.reporting.TestEvents.ratingSummary;
import static com.grocery.reporting.TestEvents.refundCompleted;
import static com.grocery.reporting.TestEvents.republished;
import static com.grocery.reporting.TestEvents.returnApproved;
import static com.grocery.reporting.TestEvents.reviewSubmitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Event → facts for every consumed type, duplicates, any arrival order, unknown types and fields, poison events. */
class FactsIntegrationTest extends AbstractIntegrationTest {

    private final UUID customer = UUID.randomUUID();
    private final UUID vendor = UUID.randomUUID();
    private final UUID apples = UUID.randomUUID();
    private final UUID milk = UUID.randomUUID();
    private final UUID partner = UUID.randomUUID();

    private Order order() {
        return grocery(customer, vendor, "Green Basket", "30.00",
                product(apples, "Apples", 2, "50.00"), product(milk, "Milk", 1, "40.00"))
                .withCoupon("save10", "14.00", null);
    }

    // ---------------------------------------------------------------- each event type

    @Test
    void orderCreatedWritesTheOrderItsLinesAndTheNames() {
        Order o = order();
        dispatchAll(orderCreated(o, ist("2026-10-05T10:00")));

        Map<String, Object> row = orderRow(o.orderId());
        assertThat(row).containsEntry("order_number", o.orderNumber())
                .containsEntry("order_type", "GROCERY")
                .containsEntry("customer_id", customer)
                .containsEntry("store_id", vendor)
                .containsEntry("store_name", "Green Basket")
                .containsEntry("currency", "INR")
                .containsEntry("subtotal", money("140.00"))
                .containsEntry("delivery_fee", money("30.00"))
                .containsEntry("discount", money("14.00"))
                .containsEntry("delivery_fee_discount", null)
                .containsEntry("total", money("156.00"))
                .containsEntry("coupon_code", "SAVE10")                       // stored upper-case
                .containsEntry("created_at", ist("2026-10-05T10:00"))
                .containsEntry("placed_at", ist("2026-10-05T10:00"))
                .containsEntry("status", "CREATED");
        assertThat(lines(o.orderId())).containsExactly("0 Apples PRODUCT 2 x 50.00 = 100.00",
                "1 Milk PRODUCT 1 x 40.00 = 40.00");
        assertThat(jdbc.sql("SELECT store_name FROM store_dim WHERE store_id = :id").param("id", Sql.bytes(vendor))
                .query(String.class).single()).isEqualTo("Green Basket");
        assertThat(jdbc.sql("SELECT name FROM item_dim WHERE item_id = :id").param("id", Sql.bytes(apples))
                .query(String.class).single()).isEqualTo("Apples");
    }

    @Test
    void orderLifecycleEventsFillTheirColumns() {
        Order delivered = order();
        dispatchAll(orderCreated(delivered, ist("2026-10-05T10:00")),
                paymentCaptured(delivered.orderId(), delivered.total(), ist("2026-10-05T10:01"), false),
                orderConfirmed(delivered, ist("2026-10-05T10:01")),
                orderDelivered(delivered, partner, ist("2026-10-05T11:00")));
        assertThat(orderRow(delivered.orderId()))
                .containsEntry("confirmed_at", ist("2026-10-05T10:01"))
                .containsEntry("captured_amount", money("156.00"))
                .containsEntry("captured_at", ist("2026-10-05T10:01"))
                .containsEntry("capture_late", false)
                .containsEntry("delivered_at", ist("2026-10-05T11:00"))
                .containsEntry("partner_id", partner)
                .containsEntry("status", "DELIVERED");

        Order cancelled = order();
        dispatchAll(orderCreated(cancelled, ist("2026-10-05T12:00")),
                orderCancelled(cancelled, ist("2026-10-05T12:15"), "AWAITING_PAYMENT", "PAYMENT_TIMEOUT", "SYSTEM"));
        assertThat(orderRow(cancelled.orderId()))
                .containsEntry("cancelled_at", ist("2026-10-05T12:15"))
                .containsEntry("cancel_reason", "PAYMENT_TIMEOUT")
                .containsEntry("cancelled_by", "SYSTEM")
                .containsEntry("cancelled_from_status", "AWAITING_PAYMENT")
                .containsEntry("status", "CANCELLED");

        Order rejected = order();
        dispatchAll(orderCreated(rejected, ist("2026-10-05T13:00")), orderRejected(rejected, ist("2026-10-05T13:01")));
        assertThat(orderRow(rejected.orderId()))
                .containsEntry("rejected_at", ist("2026-10-05T13:01"))   // the occurredAt header
                .containsEntry("reject_reason", "OUT_OF_STOCK")
                .containsEntry("status", "REJECTED");

        Order failed = order();
        dispatchAll(orderCreated(failed, ist("2026-10-05T14:00")), orderConfirmed(failed, ist("2026-10-05T14:01")),
                orderDeliveryFailed(failed, partner, ist("2026-10-05T15:00")));
        assertThat(orderRow(failed.orderId()))
                .containsEntry("delivery_failed_at", ist("2026-10-05T15:00"))
                .containsEntry("delivery_failure_reason", "CUSTOMER_UNREACHABLE")
                .containsEntry("status", "DELIVERY_FAILED");
    }

    @Test
    void refundsReturnsDeliveriesReviewsRatingsAndReceipts() {
        Order o = order();
        UUID refundId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID purchaseOrder = UUID.randomUUID();
        dispatchAll(orderCreated(o, ist("2026-10-05T10:00")),
                returnApproved(o, returnId, "45.00", ist("2026-10-06T09:00"),
                        new ReturnedLine(apples, "Apples", 1, "45.00")),
                refundCompleted(o.orderId(), refundId, "45.00", "RETURN", returnId, ist("2026-10-06T09:05")),
                partnerAssigned(o, deliveryId, partner, "Ravi Kumar", ist("2026-10-05T10:10")),
                pickedUp(o, deliveryId, partner, ist("2026-10-05T10:30")),
                deliveryCompleted(o, deliveryId, partner, ist("2026-10-05T11:00")),
                reviewSubmitted(reviewId, "PRODUCT", apples, vendor, 4, "PUBLISHED", false, ist("2026-10-07T10:00")),
                ratingSummary("PRODUCT", apples, "4.00", 1, List.of(0, 0, 0, 1, 0), 1, ist("2026-10-07T10:00")),
                goodsReceived(vendor, purchaseOrder, "PO-7K3M9QX2", receiptId, ist("2026-10-04T08:00"),
                        new ReceivedLine(apples, 10, "30.00"), new ReceivedLine(milk, 4, "12.35")));

        assertThat(jdbc.sql("SELECT amount, reason, return_id, refunded_at FROM refund_fact WHERE refund_id = :id")
                .param("id", Sql.bytes(refundId))
                .query((rs, n) -> rs.getBigDecimal("amount") + " " + rs.getString("reason") + " "
                        + Sql.uuid(rs, "return_id") + " " + Sql.instant(rs, "refunded_at")).single())
                .isEqualTo("45.00 RETURN " + returnId + " " + ist("2026-10-06T09:05"));
        assertThat(jdbc.sql("""
                        SELECT t.refund_amount, t.restock, t.decided_by_role, t.approved_at, l.quantity, l.name
                        FROM return_fact t JOIN return_line_fact l ON l.return_id = t.return_id
                        WHERE t.return_id = :id""")
                .param("id", Sql.bytes(returnId))
                .query((rs, n) -> rs.getBigDecimal(1) + " " + rs.getBoolean(2) + " " + rs.getString(3) + " "
                        + Sql.instant(rs, "approved_at") + " " + rs.getInt(5) + " " + rs.getString(6)).single())
                .isEqualTo("45.00 true STORE " + ist("2026-10-06T09:00") + " 1 Apples");
        assertThat(jdbc.sql("""
                        SELECT refunded_amount, returned_amount FROM order_summary WHERE order_id = :id""")
                .param("id", Sql.bytes(o.orderId()))
                .query((rs, n) -> rs.getBigDecimal(1) + "/" + rs.getBigDecimal(2)).single())
                .isEqualTo("45.00/45.00");

        assertThat(deliveryRow(deliveryId))
                .containsEntry("partner_id", partner)
                .containsEntry("assignments", 1)
                .containsEntry("first_assigned_at", ist("2026-10-05T10:10"))
                .containsEntry("assigned_at", ist("2026-10-05T10:10"))
                .containsEntry("picked_up_at", ist("2026-10-05T10:30"))
                .containsEntry("delivered_at", ist("2026-10-05T11:00"));
        assertThat(jdbc.sql("SELECT name FROM partner_dim WHERE partner_id = :id").param("id", Sql.bytes(partner))
                .query(String.class).single()).isEqualTo("Ravi Kumar");

        assertThat(jdbc.sql("""
                        SELECT target_type, rating, status, submissions FROM review_fact WHERE review_id = :id""")
                .param("id", Sql.bytes(reviewId))
                .query((rs, n) -> rs.getString(1) + " " + rs.getInt(2) + " " + rs.getString(3) + " " + rs.getInt(4))
                .single()).isEqualTo("PRODUCT 4 PUBLISHED 1");
        assertThat(ratingRow("PRODUCT", apples)).isEqualTo("4.00 1 [0, 0, 0, 1, 0] v1");

        assertThat(jdbc.sql("""
                        SELECT purchase_order_number, units, cost, received_at FROM receipt_fact WHERE receipt_id = :id""")
                .param("id", Sql.bytes(receiptId))
                .query((rs, n) -> rs.getString(1) + " " + rs.getInt(2) + " " + rs.getBigDecimal(3) + " "
                        + Sql.instant(rs, "received_at")).single())
                .isEqualTo("PO-7K3M9QX2 14 349.40 " + ist("2026-10-04T08:00"));       // 10 × 30.00 + 4 × 12.35
        assertThat(jdbc.sql("SELECT COUNT(*) FROM receipt_line_fact WHERE receipt_id = :id")
                .param("id", Sql.bytes(receiptId)).query(Long.class).single()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    void aRedeliveredEventIsAppliedOnce() {
        Order o = order();
        UUID deliveryId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        ConsumerRecord<String, String> assigned = partnerAssigned(o, deliveryId, partner, "Ravi Kumar",
                ist("2026-10-05T10:10"));
        ConsumerRecord<String, String> review = reviewSubmitted(reviewId, "VENDOR", vendor, vendor, 5, "PUBLISHED",
                false, ist("2026-10-05T12:00"));

        assertThat(dispatch(assigned)).isEqualTo(Outcome.APPLIED);
        assertThat(dispatch(review)).isEqualTo(Outcome.APPLIED);
        assertThat(dispatch(assigned)).isEqualTo(Outcome.DUPLICATE);
        assertThat(dispatch(review)).isEqualTo(Outcome.DUPLICATE);

        assertThat(processed(assigned)).isTrue();
        assertThat(deliveryRow(deliveryId)).containsEntry("assignments", 1);
        assertThat(jdbc.sql("SELECT submissions FROM review_fact WHERE review_id = :id")
                .param("id", Sql.bytes(reviewId)).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theSameRefundReturnOrReceiptPublishedAgainUnderANewEventIdCountsOnce() {
        Order o = order();
        UUID returnId = UUID.randomUUID();
        ConsumerRecord<String, String> refund = refundCompleted(o.orderId(), UUID.randomUUID(), "45.00", "RETURN",
                returnId, ist("2026-10-06T09:05"));
        ConsumerRecord<String, String> approved = returnApproved(o, returnId, "45.00", ist("2026-10-06T09:00"),
                new ReturnedLine(apples, "Apples", 1, "45.00"));
        ConsumerRecord<String, String> receipt = goodsReceived(vendor, UUID.randomUUID(), "PO-1", UUID.randomUUID(),
                ist("2026-10-04T08:00"), new ReceivedLine(apples, 10, "30.00"));

        dispatchAll(refund, republished(refund), approved, republished(approved), receipt, republished(receipt));

        assertThat(count("refund_fact")).isEqualTo(1);
        assertThat(count("return_fact")).isEqualTo(1);
        assertThat(count("return_line_fact")).isEqualTo(1);
        assertThat(count("receipt_fact")).isEqualTo(1);
        assertThat(count("receipt_line_fact")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- arrival order

    @Test
    void anyArrivalOrderGivesTheSameFacts() {
        Order inOrder = order();
        Order shuffled = inOrder.copy();
        List<ConsumerRecord<String, String>> first = lifecycle(inOrder);
        List<ConsumerRecord<String, String>> second = lifecycle(shuffled);
        Collections.reverse(second);                       // the worst case: every event before its predecessors
        first.forEach(this::dispatch);
        second.forEach(this::dispatch);

        assertThat(withoutIds(orderRow(shuffled.orderId()))).isEqualTo(withoutIds(orderRow(inOrder.orderId())));
        assertThat(lines(shuffled.orderId())).isEqualTo(lines(inOrder.orderId())).hasSize(2);
        assertThat(deliveriesOf(shuffled.orderId())).isEqualTo(deliveriesOf(inOrder.orderId()));
        assertThat(summary(shuffled.orderId())).isEqualTo(summary(inOrder.orderId())).isEqualTo("45.00/45.00");

        // and a random interleaving of a third copy
        Order random = inOrder.copy();
        List<ConsumerRecord<String, String>> third = lifecycle(random);
        Collections.shuffle(third, new Random(42));
        third.forEach(this::dispatch);
        assertThat(withoutIds(orderRow(random.orderId()))).isEqualTo(withoutIds(orderRow(inOrder.orderId())));
        assertThat(deliveriesOf(random.orderId())).isEqualTo(deliveriesOf(inOrder.orderId()));
        assertThat(orderRow(random.orderId())).containsEntry("status", "DELIVERED")
                .containsEntry("placed_at", ist("2026-10-05T10:00"));
    }

    /** Every event of one delivered, partly returned order: created … refunded, with one reassignment. */
    private List<ConsumerRecord<String, String>> lifecycle(Order o) {
        UUID deliveryId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        UUID otherPartner = UUID.randomUUID();
        return new ArrayList<>(List.of(
                orderCreated(o, ist("2026-10-05T10:00")),
                paymentCaptured(o.orderId(), o.total(), ist("2026-10-05T10:01"), false),
                orderConfirmed(o, ist("2026-10-05T10:01")),
                partnerAssigned(o, deliveryId, otherPartner, "Meena Iyer", ist("2026-10-05T10:10")),
                partnerAssigned(o, deliveryId, partner, "Ravi Kumar", ist("2026-10-05T10:15")),
                pickedUp(o, deliveryId, partner, ist("2026-10-05T10:30")),
                deliveryCompleted(o, deliveryId, partner, ist("2026-10-05T11:00")),
                orderDelivered(o, partner, ist("2026-10-05T11:00")),
                returnApproved(o, returnId, "45.00", ist("2026-10-06T09:00"),
                        new ReturnedLine(apples, "Apples", 1, "45.00")),
                refundCompleted(o.orderId(), UUID.randomUUID(), "45.00", "RETURN", returnId,
                        ist("2026-10-06T09:05"))));
    }

    @Test
    void anOlderRatingSummaryNeverReplacesANewerOne() {
        UUID target = UUID.randomUUID();
        dispatchAll(ratingSummary("RESTAURANT", target, "4.00", 2, List.of(0, 0, 0, 2, 0), 5, ist("2026-10-05T10:00")),
                ratingSummary("RESTAURANT", target, "3.00", 1, List.of(0, 0, 1, 0, 0), 4, ist("2026-10-05T09:00")),
                ratingSummary("RESTAURANT", target, "4.00", 2, List.of(0, 0, 0, 2, 0), 5, ist("2026-10-05T10:00")));
        assertThat(ratingRow("RESTAURANT", target)).isEqualTo("4.00 2 [0, 0, 0, 2, 0] v5");

        dispatchAll(ratingSummary("RESTAURANT", target, "4.33", 3, List.of(0, 0, 0, 2, 1), 6, ist("2026-10-05T11:00")));
        assertThat(ratingRow("RESTAURANT", target)).isEqualTo("4.33 3 [0, 0, 0, 2, 1] v6");
    }

    @Test
    void aLateReviewEditDoesNotOverwriteANewerOne() {
        UUID reviewId = UUID.randomUUID();
        dispatchAll(reviewSubmitted(reviewId, "PRODUCT", apples, vendor, 2, "FLAGGED", true, ist("2026-10-05T12:00")),
                reviewSubmitted(reviewId, "PRODUCT", apples, vendor, 5, "PUBLISHED", false, ist("2026-10-05T10:00")));

        assertThat(jdbc.sql("""
                        SELECT rating, status, submissions, first_submitted_at, last_submitted_at
                        FROM review_fact WHERE review_id = :id""")
                .param("id", Sql.bytes(reviewId))
                .query((rs, n) -> rs.getInt(1) + " " + rs.getString(2) + " " + rs.getInt(3) + " "
                        + Sql.instant(rs, "first_submitted_at") + " " + Sql.instant(rs, "last_submitted_at")).single())
                .isEqualTo("2 FLAGGED 2 " + ist("2026-10-05T10:00") + " " + ist("2026-10-05T12:00"));
    }

    // ---------------------------------------------------------------- what is not ours

    @Test
    void eventTypesThisServiceDoesNotUseAreIgnoredWithoutTouchingTheDatabase() {
        Order o = order();
        ConsumerRecord<String, String> accepted = TestEvents.event("order.events", "OrderAccepted", o.orderId(),
                map("orderId", o.orderId(), "orderNumber", o.orderNumber(), "orderType", "GROCERY",
                        "customerId", customer, "storeId", vendor, "readyInMinutes", 15,
                        "readyBy", Instant.now(), "acceptedAt", Instant.now()), Instant.now());
        ConsumerRecord<String, String> vendorApproved = TestEvents.event("vendor.events", "VendorApproved", vendor,
                map("vendorId", vendor, "ownerUserId", UUID.randomUUID(), "businessName", "Green Basket",
                        "reason", null, "actorUserId", UUID.randomUUID()), Instant.now());

        assertThat(dispatchRaw(accepted)).isEqualTo(Outcome.IGNORED);
        assertThat(dispatchRaw(vendorApproved)).isEqualTo(Outcome.IGNORED);
        assertThat(processed(accepted)).isFalse();
        assertThat(count("order_fact")).isZero();
    }

    @Test
    void theCouponsNameAndFundingComeWithTheOrderEventsAndOlderEventsHaveNone() {
        Order funded = order().withCoupon("save10", "14.00", null, "  Save 10% ", "platform");
        dispatchAll(orderCreated(funded, ist("2026-10-05T10:00")), orderConfirmed(funded, ist("2026-10-05T10:01")));
        assertThat(orderRow(funded.orderId()))
                .containsEntry("coupon_code", "SAVE10")
                .containsEntry("coupon_name", "Save 10%")
                .containsEntry("coupon_funded_by", "PLATFORM");

        // an order placed before schemas 1.7.0: its events have neither field, as written (raw) and through Avro
        Order older = order();
        assertThat(dispatchRaw(orderCreated(older, ist("2026-10-05T11:00")))).isEqualTo(Outcome.APPLIED);
        dispatchAll(orderConfirmed(older, ist("2026-10-05T11:01")));
        assertThat(orderRow(older.orderId()))
                .containsEntry("coupon_code", "SAVE10")
                .containsEntry("discount", money("14.00"))
                .containsEntry("coupon_name", null)
                .containsEntry("coupon_funded_by", null);

        // an event without them never erases what an earlier one said (the latest non-null value wins)
        Order mixed = order().withCoupon("SAVE10", "14.00", null, "Save 10%", "STORE");
        Order sameOrderWithoutThem = mixed.withCoupon("SAVE10", "14.00", null);
        dispatchAll(orderConfirmed(mixed, ist("2026-10-05T12:01")),
                orderCreated(sameOrderWithoutThem, ist("2026-10-05T12:00")));
        assertThat(orderRow(mixed.orderId()))
                .containsEntry("coupon_name", "Save 10%")
                .containsEntry("coupon_funded_by", "STORE");
    }

    @Test
    void unknownFieldsAreIgnored() {
        Order o = order();
        Map<String, Object> payload = map("orderId", o.orderId(), "orderNumber", o.orderNumber(),
                "orderType", "GROCERY", "customerId", customer, "storeId", vendor, "partnerId", partner,
                "deliveredAt", ist("2026-10-05T11:00"), "proofOfDelivery", map("photo", "x.jpg"), "rating", 5);
        assertThat(dispatchRaw(TestEvents.event("order.events", "OrderDelivered", o.orderId(), payload,
                ist("2026-10-05T11:00")))).isEqualTo(Outcome.APPLIED);
        assertThat(orderRow(o.orderId())).containsEntry("delivered_at", ist("2026-10-05T11:00"));
    }

    @Test
    void anEventWithoutItsIdsIsPoison() {
        ConsumerRecord<String, String> noOrderId = TestEvents.event("payment.events", "RefundCompleted", "x",
                map("refundId", UUID.randomUUID(), "amount", money("10.00"), "reason", "ADMIN"), Instant.now());
        ConsumerRecord<String, String> notJson = new ConsumerRecord<>("order.events", 0, 0L, "x", "{not json");
        TestEvents.event("order.events", "OrderCreated", "x", Map.of(), null).headers()
                .forEach(h -> notJson.headers().add(h));

        assertThatThrownBy(() -> dispatchRaw(noOrderId)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> dispatchRaw(notJson)).isInstanceOf(InvalidEventException.class);
        assertThat(processed(noOrderId)).isFalse();
    }

    @Test
    void aRefundWithoutAnOccurredAtHeaderCountsWhenItIsProcessed() {
        UUID refundId = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(1);
        ConsumerRecord<String, String> refund = TestEvents.event("payment.events", "RefundCompleted", "o",
                map("orderId", UUID.randomUUID(), "paymentId", UUID.randomUUID(), "refundId", refundId,
                        "amount", money("10.00"), "reason", "ADMIN", "totalRefunded", money("10.00"),
                        "fullyRefunded", false, "returnId", null), null);
        dispatchAll(refund);
        Instant refundedAt = jdbc.sql("SELECT refunded_at FROM refund_fact WHERE refund_id = :id")
                .param("id", Sql.bytes(refundId)).query((rs, n) -> Sql.instant(rs, "refunded_at")).single();
        assertThat(refundedAt).isAfter(before).isBefore(Instant.now().plusSeconds(1));
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> orderRow(UUID orderId) {
        return jdbc.sql("SELECT * FROM order_fact WHERE order_id = :id")
                .param("id", Sql.bytes(orderId))
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        String column = rs.getMetaData().getColumnLabel(i);
                        Object value = rs.getObject(i);
                        if (value instanceof byte[] bytes) {
                            value = Sql.uuid(bytes);
                        } else if (value instanceof LocalDateTime time) {
                            value = time.toInstant(ZoneOffset.UTC);
                        }
                        row.put(column, value);
                    }
                    return row;
                }).single();
    }

    private static Map<String, Object> withoutIds(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("order_id");
        copy.remove("order_number");
        copy.remove("updated_at");
        return copy;
    }

    private List<String> lines(UUID orderId) {
        return jdbc.sql("""
                        SELECT line_no, name, item_type, quantity, unit_price, line_total FROM order_line_fact
                        WHERE order_id = :id ORDER BY line_no""")
                .param("id", Sql.bytes(orderId))
                .query((rs, n) -> rs.getInt(1) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getInt(4)
                        + " x " + rs.getBigDecimal(5) + " = " + rs.getBigDecimal(6))
                .list();
    }

    private Map<String, Object> deliveryRow(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT partner_id, assignments, first_assigned_at, assigned_at, picked_up_at, delivered_at
                        FROM delivery_fact WHERE delivery_id = :id""")
                .param("id", Sql.bytes(deliveryId))
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("partner_id", Sql.uuid(rs, "partner_id"));
                    row.put("assignments", rs.getInt("assignments"));
                    row.put("first_assigned_at", Sql.instant(rs, "first_assigned_at"));
                    row.put("assigned_at", Sql.instant(rs, "assigned_at"));
                    row.put("picked_up_at", Sql.instant(rs, "picked_up_at"));
                    row.put("delivered_at", Sql.instant(rs, "delivered_at"));
                    return row;
                }).single();
    }

    /** The delivery facts of an order (without the delivery id). */
    private List<String> deliveriesOf(UUID orderId) {
        return jdbc.sql("""
                        SELECT partner_id, assignments, first_assigned_at, assigned_at, picked_up_at, delivered_at
                        FROM delivery_fact WHERE order_id = :id""")
                .param("id", Sql.bytes(orderId))
                .query((rs, n) -> Sql.uuid(rs, "partner_id") + " " + rs.getInt(2) + " "
                        + Sql.instant(rs, "first_assigned_at") + " " + Sql.instant(rs, "assigned_at") + " "
                        + Sql.instant(rs, "picked_up_at") + " " + Sql.instant(rs, "delivered_at"))
                .list();
    }

    private String summary(UUID orderId) {
        return jdbc.sql("SELECT refunded_amount, returned_amount FROM order_summary WHERE order_id = :id")
                .param("id", Sql.bytes(orderId))
                .query((rs, n) -> rs.getBigDecimal(1) + "/" + rs.getBigDecimal(2)).single();
    }

    private String ratingRow(String targetType, UUID targetId) {
        return jdbc.sql("""
                        SELECT average_rating, rating_count, stars_1, stars_2, stars_3, stars_4, stars_5, summary_version
                        FROM rating_fact WHERE target_type = :type AND target_id = :id""")
                .param("type", targetType)
                .param("id", Sql.bytes(targetId))
                .query((rs, n) -> rs.getBigDecimal(1) + " " + rs.getInt(2) + " "
                        + List.of(rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7))
                        + " v" + rs.getLong(8)).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
