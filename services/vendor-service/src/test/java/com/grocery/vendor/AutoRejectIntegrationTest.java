package com.grocery.vendor;

import com.grocery.vendor.config.FulfillmentProperties;
import com.grocery.vendor.dto.StoreOrderDtos.StoreOrderView;
import com.grocery.vendor.entity.RejectionReason;
import com.grocery.vendor.event.VendorOrderEvents;
import com.grocery.vendor.service.AutoRejectPoller;
import com.grocery.vendor.service.StoreOrderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The auto-reject poller (contract §2 "Auto-reject") and the races of the locking rule: accept vs auto-reject and a
 * store action vs OrderCancelled always end with exactly one outcome. The tests run pollers themselves with a clock of
 * their choice (the scheduled poller is off in tests). The database is shared, so a poller also rejects NEW orders other
 * tests left behind: assertions only look at this test's orders.
 */
class AutoRejectIntegrationTest extends StoreOrderTestSupport {

    private static final Duration PAST_DEADLINE = Duration.ofMinutes(6);              // the timeout is 5 minutes

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    /** Runs {@code task}; an expected StoreOrderException is returned as the outcome instead of thrown. */
    private Callable<Object> outcome(CountDownLatch start, Callable<Object> task) {
        return () -> {
            start.await();
            try {
                return task.call();
            } catch (StoreOrderException e) {
                return e;
            }
        };
    }

    @Test
    void settingsHaveTheContractDefaults() {
        assertThat(fulfillment.acceptanceTimeout()).isEqualTo(Duration.ofMinutes(5));    // ${ACCEPTANCE_TIMEOUT:5m}
        assertThat(fulfillment.pollInterval()).isEqualTo(Duration.ofSeconds(15));        // ${ACCEPTANCE_POLL_INTERVAL:15s}
        assertThat(fulfillment.pollBatchSize()).isEqualTo(20);
        assertThat(fulfillment.defaultReadyInMinutes()).isEqualTo(15);
        assertThat(fulfillment.zone().getId()).isEqualTo("Asia/Kolkata");
    }

    @Test
    void aNewOrderIsRejectedOnlyOnceItsDeadlineHasPassed() throws Exception {
        Store store = newStore();
        Instant received = Instant.now().minus(Duration.ofDays(400));                // older than anything else
        clock.set(received);
        UUID orderId = confirm(store);
        UUID accepted = confirm(store);
        storeOrders.accept(vendorUser(store), accepted, null);
        clock.reset();
        Instant acceptBy = received.plus(Duration.ofMinutes(5));

        pollerAt(acceptBy.minusSeconds(1)).rejectExpired();
        pollerAt(acceptBy).rejectExpired();                                           // at the deadline: still in time
        assertThat(orderStatus(orderId)).isEqualTo("NEW");

        Instant now = acceptBy.plusNanos(1_000);
        assertThat(pollerAt(now).rejectExpired()).isPositive();

        assertThat(orderStatus(orderId)).isEqualTo("REJECTED");
        assertThat(orderStatus(accepted)).isEqualTo("ACCEPTED");                           // only NEW orders
        assertThat(history(orderId)).containsExactly("NEW", "REJECTED");
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.REJECTED);
        assertThat(outboxRow(orderId, VendorOrderEvents.REJECTED))
                .containsEntry("topic", "vendor.events")
                .containsEntry("aggregate_type", "VendorOrder")
                .containsEntry("aggregate_id", orderId.toString());
        JsonNode rejected = storeEvent(orderId, VendorOrderEvents.REJECTED);
        assertThat(rejected.get("reason").asString()).isEqualTo("NO_RESPONSE");
        assertThat(rejected.get("auto").asBoolean()).isTrue();
        assertThat(rejected.get("note").isNull()).isTrue();
        assertThat(rejected.get("actorUserId").isNull()).isTrue();
        assertThat(rejected.get("vendorId").asString()).isEqualTo(store.vendorId().toString());
        assertThat(Instant.parse(rejected.get("rejectedAt").asString())).isEqualTo(now);
        assertThat(jdbc().sql("SELECT actor_user_id FROM vendor_order_status_history "
                        + "WHERE order_id = UUID_TO_BIN(?) AND status = 'REJECTED'")
                .param(orderId.toString()).query().singleRow().get("actor_user_id")).isNull();

        JsonNode view = body(mvc.perform(get("/api/vendor/orders/{id}", orderId).with(vendor(store)))
                .andExpect(status().isOk()));
        assertThat(view.get("rejection").get("reason").asString()).isEqualTo("NO_RESPONSE");
        assertThat(view.get("rejection").get("auto").asBoolean()).isTrue();
        assertThat(view.get("rejection").get("note").isNull()).isTrue();

        // Nothing is rejected twice; the store can no longer accept it
        pollerAt(now.plus(Duration.ofHours(1))).rejectExpired();
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.REJECTED);
        StoreOrderException late = org.junit.jupiter.api.Assertions.assertThrows(StoreOrderException.class,
                () -> storeOrders.accept(vendorUser(store), orderId, null));
        assertThat(late.errorCode()).isEqualTo("STORE_ORDER_NOT_PENDING");
    }

    @Test
    void anOrderLockedByAnotherTransactionIsSkippedNotWaitedFor() throws Exception {
        Store store = newStore();
        UUID busy = confirm(store);
        UUID free = confirm(store);

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // e.g. a store user accepting it right now, or another replica's batch, holds the row
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT order_id FROM vendor_order WHERE order_id = UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, busy.toString());
                lock.executeQuery().close();
            }

            long started = System.nanoTime();
            pollerAhead(PAST_DEADLINE).rejectExpired();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));

            assertThat(orderStatus(free)).isEqualTo("REJECTED");
            assertThat(orderStatus(busy)).isEqualTo("NEW");                                // skipped, not waited for
            other.rollback();
        }

        pollerAhead(PAST_DEADLINE).rejectExpired();                                   // the next run takes it
        assertThat(orderStatus(busy)).isEqualTo("REJECTED");
        assertThat(storeEventTypes(busy)).containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.REJECTED);
    }

    @Test
    void twoPollersRejectEveryExpiredOrderExactlyOnce() throws Exception {
        Store store = newStore();
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            orders.add(confirm(store));
        }
        FulfillmentProperties small = fulfillment.withPollBatchSize(3);
        Clock ahead = Clock.offset(clock, PAST_DEADLINE);
        AutoRejectPoller first = new AutoRejectPoller(storeOrders, small, ahead);
        AutoRejectPoller second = new AutoRejectPoller(storeOrders, small, ahead);
        CountDownLatch start = new CountDownLatch(1);

        Future<Object> one = executor.submit(outcome(start, first::rejectExpired));
        Future<Object> two = executor.submit(outcome(start, second::rejectExpired));
        start.countDown();
        int claimed = (Integer) one.get(60, TimeUnit.SECONDS) + (Integer) two.get(60, TimeUnit.SECONDS);

        assertThat(claimed).isGreaterThanOrEqualTo(orders.size());
        for (UUID orderId : orders) {
            assertThat(orderStatus(orderId)).as("status of %s", orderId).isEqualTo("REJECTED");
            assertThat(history(orderId)).as("history of %s", orderId).containsExactly("NEW", "REJECTED");
            assertThat(storeEventTypes(orderId)).as("events of %s", orderId)
                    .containsExactly(VendorOrderEvents.RECEIVED, VendorOrderEvents.REJECTED);
        }
    }

    @Test
    void acceptRacingTheAutoRejectHasExactlyOneOutcome() throws Exception {
        Store store = newStore();
        for (int round = 0; round < 8; round++) {
            UUID orderId = confirm(store);
            CountDownLatch start = new CountDownLatch(1);
            // The store user is still in time on the service's clock; the poller already runs past the deadline
            Future<Object> accept = executor.submit(outcome(start,
                    () -> storeOrders.accept(vendorUser(store), orderId, null)));
            Future<Object> poll = executor.submit(outcome(start, () -> pollerAhead(PAST_DEADLINE).rejectExpired()));
            start.countDown();
            Object accepted = accept.get(60, TimeUnit.SECONDS);
            poll.get(60, TimeUnit.SECONDS);

            if (accepted instanceof StoreOrderView view) {
                assertThat(view.status()).isEqualTo("ACCEPTED");
                assertThat(orderStatus(orderId)).isEqualTo("ACCEPTED");
                assertThat(history(orderId)).containsExactly("NEW", "ACCEPTED");
                assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED,
                        VendorOrderEvents.ACCEPTED);
            } else {
                assertThat(accepted).isInstanceOf(StoreOrderException.class);
                assertThat(((StoreOrderException) accepted).errorCode()).isEqualTo("STORE_ORDER_NOT_PENDING");
                assertThat(((StoreOrderException) accepted).properties()).containsEntry("currentStatus", "REJECTED");
                assertThat(orderStatus(orderId)).isEqualTo("REJECTED");
                assertThat(history(orderId)).containsExactly("NEW", "REJECTED");
                assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED,
                        VendorOrderEvents.REJECTED);
                assertThat(storeEvent(orderId, VendorOrderEvents.REJECTED).get("auto").asBoolean()).isTrue();
            }
        }
    }

    @Test
    void rejectRacingOrderCancelledHasExactlyOneOutcome() throws Exception {
        Store store = newStore();
        for (int round = 0; round < 8; round++) {
            UUID orderId = confirm(store);
            String cancelEvent = newEventId();
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> reject = executor.submit(outcome(start,
                    () -> storeOrders.reject(vendorUser(store), orderId, RejectionReason.TOO_BUSY, null)));
            Future<Object> cancelled = executor.submit(outcome(start, () -> {
                cancel(cancelEvent, store, orderId, "2026-11-11T10:06:00Z");
                return "cancelled";
            }));
            start.countDown();
            Object rejected = reject.get(60, TimeUnit.SECONDS);
            assertThat(cancelled.get(60, TimeUnit.SECONDS)).isEqualTo("cancelled");
            assertThat(processed(cancelEvent)).isEqualTo(1);

            if (rejected instanceof StoreOrderView view) {
                // the store won: REJECTED + VendorOrderRejected; the cancellation found a terminal order
                assertThat(view.status()).isEqualTo("REJECTED");
                assertThat(orderStatus(orderId)).isEqualTo("REJECTED");
                assertThat(history(orderId)).containsExactly("NEW", "REJECTED");
                assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED,
                        VendorOrderEvents.REJECTED);
            } else {
                // the cancellation won: CANCELLED, the store's reject is 409 and publishes nothing
                assertThat(((StoreOrderException) rejected).errorCode()).isEqualTo("STORE_ORDER_NOT_PENDING");
                assertThat(((StoreOrderException) rejected).properties()).containsEntry("currentStatus", "CANCELLED");
                assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
                assertThat(history(orderId)).containsExactly("NEW", "CANCELLED");
                assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED);
            }
        }
    }

    @Test
    void acceptRacingOrderCancelledNeverPublishesForAnOrderItFoundCancelled() throws Exception {
        Store store = newStore();
        for (int round = 0; round < 6; round++) {
            UUID orderId = confirm(store);
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> accept = executor.submit(outcome(start,
                    () -> storeOrders.accept(vendorUser(store), orderId, null)));
            Future<Object> cancelled = executor.submit(outcome(start, () -> {
                cancel(newEventId(), store, orderId, "2026-11-11T10:06:00Z");
                return "cancelled";
            }));
            start.countDown();
            Object accepted = accept.get(60, TimeUnit.SECONDS);
            cancelled.get(60, TimeUnit.SECONDS);

            assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");                      // the customer's cancel stands
            if (accepted instanceof StoreOrderView) {
                assertThat(history(orderId)).containsExactly("NEW", "ACCEPTED", "CANCELLED");
                assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED,
                        VendorOrderEvents.ACCEPTED);
            } else {
                assertThat(((StoreOrderException) accepted).errorCode()).isEqualTo("STORE_ORDER_NOT_PENDING");
                assertThat(history(orderId)).containsExactly("NEW", "CANCELLED");
                assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED);
            }
        }
    }

    @Test
    void anAcceptWaitingForTheRowLockSeesTheChangeCommittedBeforeIt() throws Exception {
        Store store = newStore();
        UUID orderId = confirm(store);
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT order_id FROM vendor_order WHERE order_id = UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, orderId.toString());
                lock.executeQuery().close();
            }
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> accept = executor.submit(outcome(start,
                    () -> storeOrders.accept(vendorUser(store), orderId, null)));
            start.countDown();
            Thread.sleep(500);
            assertThat(accept.isDone()).as("the accept waits for the row lock").isFalse();
            // Meanwhile the lock holder rejects the order (as the poller would) and commits
            try (PreparedStatement change = other.prepareStatement(
                    "UPDATE vendor_order SET status = 'REJECTED', rejection_reason = 'NO_RESPONSE', "
                            + "rejection_auto = TRUE, rejected_at = NOW(6) WHERE order_id = UUID_TO_BIN(?)")) {
                change.setString(1, orderId.toString());
                change.executeUpdate();
            }
            other.commit();

            Object accepted = accept.get(60, TimeUnit.SECONDS);
            assertThat(accepted).isInstanceOf(StoreOrderException.class);
            assertThat(((StoreOrderException) accepted).errorCode()).isEqualTo("STORE_ORDER_NOT_PENDING");
            assertThat(((StoreOrderException) accepted).properties()).containsEntry("currentStatus", "REJECTED");
        }
        assertThat(storeEventTypes(orderId)).containsExactly(VendorOrderEvents.RECEIVED);
    }
}
