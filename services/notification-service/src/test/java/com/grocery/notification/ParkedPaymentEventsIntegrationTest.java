package com.grocery.notification;

import com.grocery.common.kafka.TransientFailures;
import com.grocery.notification.TestEvents.Order;
import com.grocery.notification.repository.UuidBytes;
import com.grocery.notification.service.NotificationRetention;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static com.grocery.notification.RealtimeRecorder.mentioning;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract §5: a payment event whose order is not known yet (its OrderCreated is on another topic) is parked — the
 * partition is never blocked — and applied, in arrival order and with its original occurredAt, in the transaction
 * that makes the order known.
 */
class ParkedPaymentEventsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    NotificationRetention retention;

    @Test
    void aPaymentEventBeforeItsOrderIsParkedThenAppliedAfterTheOrdersOwnMessages() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), "Insufficient funds", true);

        dispatch(failed);   // no exception: the record is done, the partition moves on

        assertThat(processed(failed)).isTrue();
        assertThat(parked(order.orderId())).containsExactly(TestEvents.eventId(failed));
        assertThat(notificationsOf(failed)).isEmpty();

        dispatch(failed);   // redelivered while parked
        assertThat(parked(order.orderId())).as("not parked twice").containsExactly(TestEvents.eventId(failed));
        awaitMarker();
        assertThat(recorder.matching(mentioning(order.orderId()))).as("nothing sent while parked").isEmpty();

        ConsumerRecord<String, String> created = TestEvents.orderCreated(order);
        dispatch(created);

        assertThat(parked(order.orderId())).isEmpty();
        Stored notification = notificationOf(failed, "PAYMENT_FAILED");
        assertThat(notification.audiences()).containsExactly("user:" + order.customerId());
        assertThat(notification.title()).isEqualTo("Payment declined for order " + order.orderNumber());
        List<JsonNode> messages = recorder.await(mentioning(order.orderId()), 5, WAIT);
        assertThat(messages).extracting(ParkedPaymentEventsIntegrationTest::describe).containsExactly(
                "signal ORDER_UPDATED OrderCreated",
                "signal ADMIN_ORDERS_CHANGED OrderCreated",
                "notification PAYMENT_FAILED",
                "signal ORDER_UPDATED PaymentFailed",
                "signal ADMIN_ORDERS_CHANGED PaymentFailed");
        assertThat(messages.get(3).at("/payload/data/status").asString()).isEqualTo("CREATED");

        // redeliveries of either event apply nothing twice
        dispatch(failed);
        dispatch(created);
        awaitMarker();
        assertThat(notificationsOf(failed)).hasSize(1);
        assertThat(recorder.matching(mentioning(order.orderId()))).hasSize(5);
    }

    @Test
    void parkedEventsAreAppliedInArrivalOrder() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        ConsumerRecord<String, String> initiated = TestEvents.paymentInitiated(order.orderId());
        ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), null, true);
        ConsumerRecord<String, String> refunded = TestEvents.refundCompleted(order.orderId(), new BigDecimal("245.00"));
        dispatch(initiated);
        dispatch(failed);
        dispatch(refunded);
        assertThat(parked(order.orderId())).containsExactly(TestEvents.eventId(initiated),
                TestEvents.eventId(failed), TestEvents.eventId(refunded));

        dispatch(TestEvents.orderCreated(order));

        List<JsonNode> updates = recorder.await(RealtimeRecorder.signal("ORDER_UPDATED")
                .and(mentioning(order.orderId())), 4, WAIT);
        assertThat(updates).extracting(m -> m.at("/payload/data/event").asString())
                .containsExactly("OrderCreated", "PaymentInitiated", "PaymentFailed", "RefundCompleted");
        List<JsonNode> notifications = recorder.await(RealtimeRecorder.kind("notification")
                .and(mentioning(order.orderId())), 2, WAIT);
        assertThat(notifications).extracting(m -> m.at("/payload/type").asString())
                .containsExactly("PAYMENT_FAILED", "REFUND_COMPLETED");
        assertThat(notificationOf(failed, "PAYMENT_FAILED").id())
                .isLessThan(notificationOf(refunded, "REFUND_COMPLETED").id());
    }

    @Test
    void aParkedEventThatGrewOlderThanMaxEventAgeNotifiesNobody() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), "Declined", true);
        ConsumerRecord<String, String> refunded = TestEvents.refundCompleted(order.orderId(), new BigDecimal("10.00"));
        dispatch(failed);
        dispatch(refunded);
        // the order arrives much later: the first event's ORIGINAL occurredAt is now 20 minutes old
        jdbc.sql("UPDATE pending_event SET occurred_at = :at WHERE event_id = :id")
                .param("at", LocalDateTime.ofInstant(Instant.now().minus(Duration.ofMinutes(20)), ZoneOffset.UTC))
                .param("id", TestEvents.eventId(failed))
                .update();

        dispatch(TestEvents.orderCreated(order));

        assertThat(parked(order.orderId())).isEmpty();
        assertThat(notificationsOf(failed)).as("projections only (payment events have none)").isEmpty();
        assertThat(notificationOf(refunded, "REFUND_COMPLETED").audiences())
                .containsExactly("user:" + order.customerId());
        awaitMarker();
        assertThat(recorder.matching(RealtimeRecorder.signal("ORDER_UPDATED").and(mentioning(order.orderId()))))
                .extracting(m -> m.at("/payload/data/event").asString())
                .containsExactly("OrderCreated", "RefundCompleted");
    }

    @Test
    void anOldOrderCreatedStillAppliesTheParkedEvents() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), "Declined", true);
        dispatch(failed);

        // e.g. a backlog after downtime: the OrderCreated itself is old, the payment event (by its own time) is not
        dispatch(TestEvents.old(TestEvents.orderCreated(order)));

        assertThat(parked(order.orderId())).isEmpty();
        assertThat(notificationOf(failed, "PAYMENT_FAILED").audiences()).containsExactly("user:" + order.customerId());
        List<JsonNode> updates = recorder.await(RealtimeRecorder.signal("ORDER_UPDATED")
                .and(mentioning(order.orderId())), 1, WAIT);
        assertThat(updates).extracting(m -> m.at("/payload/data/event").asString()).containsExactly("PaymentFailed");
    }

    @Test
    void anyOrderEventMakesTheOrderKnown() {
        Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
        ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), "Declined", true);
        dispatch(failed);

        // its OrderCreated was never seen (e.g. before this consumer group's first offset)
        dispatch(TestEvents.orderAwaitingPayment(order));

        assertThat(parked(order.orderId())).isEmpty();
        assertThat(notificationOf(failed, "PAYMENT_FAILED").audiences()).containsExactly("user:" + order.customerId());
    }

    @Test
    void anOldPaymentEventOfAnUnknownOrderIsNeitherParkedNorKeptWaiting() {
        UUID orderId = UUID.randomUUID();
        ConsumerRecord<String, String> old = TestEvents.old(TestEvents.paymentFailed(orderId, "Declined", true));

        dispatch(old);

        assertThat(processed(old)).isTrue();
        assertThat(parked(orderId)).isEmpty();
        assertThat(notificationsOf(old)).isEmpty();
    }

    @Test
    void theNightlyCleanupDropsParkedEventsWhoseOrderNeverArrived() {
        UUID lostOrder = UUID.randomUUID();
        UUID recentOrder = UUID.randomUUID();
        ConsumerRecord<String, String> lost = TestEvents.paymentInitiated(lostOrder);
        ConsumerRecord<String, String> recent = TestEvents.paymentInitiated(recentOrder);
        dispatch(lost);
        dispatch(recent);
        jdbc.sql("UPDATE pending_event SET received_at = :at WHERE event_id = :id")
                .param("at", LocalDateTime.ofInstant(Instant.now().minus(Duration.ofHours(25)), ZoneOffset.UTC))
                .param("id", TestEvents.eventId(lost))
                .update();

        retention.nightly();

        assertThat(parked(lostOrder)).isEmpty();
        assertThat(parked(recentOrder)).containsExactly(TestEvents.eventId(recent));
        assertThat(retention.dropPendingEventsParkedBefore(Instant.now().minus(Duration.ofHours(24)))).isZero();
    }

    @Test
    void aPaymentEventRacingItsOrderCreatedOnAnotherThreadIsAppliedExactlyOnce() throws Exception {
        for (int i = 0; i < 15; i++) {
            Order order = TestEvents.groceryOrder(UUID.randomUUID(), UUID.randomUUID());
            ConsumerRecord<String, String> failed = TestEvents.paymentFailed(order.orderId(), "Declined", true);
            ConsumerRecord<String, String> created = TestEvents.orderCreated(order);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Void> payment = CompletableFuture.runAsync(() -> consume(start, failed));
            CompletableFuture<Void> orderCreated = CompletableFuture.runAsync(() -> consume(start, created));
            start.countDown();
            CompletableFuture.allOf(payment, orderCreated).get();

            assertThat(parked(order.orderId())).as("run %d: nothing left behind", i).isEmpty();
            assertThat(notificationsOf(failed)).as("run %d: applied once", i).hasSize(1);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Like the listener container: a transient failure (lock wait, deadlock victim) retries the same record. */
    private void consume(CountDownLatch start, ConsumerRecord<String, String> record) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                dispatch(record);
                return;
            } catch (RuntimeException e) {
                if (!TransientFailures.isTransient(e) || attempt == 5) {
                    throw e;
                }
            }
        }
    }

    /** Event ids parked for the order, in arrival order. */
    private List<String> parked(UUID orderId) {
        return jdbc.sql("SELECT event_id FROM pending_event WHERE order_id = :orderId ORDER BY seq")
                .param("orderId", UuidBytes.bytes(orderId))
                .query(String.class)
                .list();
    }

    /** Once a later event's message arrived, everything published before it has arrived too. */
    private void awaitMarker() {
        UUID marker = UUID.randomUUID();
        dispatch(TestEvents.restaurantListing(marker, UUID.randomUUID(), "Marker", "APPROVED", true));
        recorder.await(mentioning(marker), 1, WAIT);
    }

    private static String describe(JsonNode message) {
        String kind = message.get("kind").asString();
        return "notification".equals(kind)
                ? kind + " " + message.at("/payload/type").asString()
                : kind + " " + message.at("/payload/type").asString() + " "
                + message.at("/payload/data/event").asString();
    }
}
