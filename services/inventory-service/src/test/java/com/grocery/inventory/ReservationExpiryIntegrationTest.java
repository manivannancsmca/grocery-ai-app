package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.TransientFailures;
import com.grocery.inventory.config.InventoryProperties;
import com.grocery.inventory.service.ReservationExpiryPoller;
import com.grocery.inventory.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reservation expiry poller (contract §6 "Inventory"): RESERVED reservations past {@code expires_at} are
 * released → {@code StockReleased(RESERVATION_EXPIRED)} + {@code StockReservationExpired}. The tests run pollers
 * themselves with a clock ahead of the real one (the scheduled poller is off in tests). The database is shared, so
 * a poller also releases reservations other tests left behind: assertions only look at this test's orders.
 */
class ReservationExpiryIntegrationTest extends AbstractIntegrationTest {

    private static final Duration PAST_TTL = Duration.ofMinutes(21);       // the TTL is 20 minutes

    @Autowired
    ReservationService reservations;
    @Autowired
    InventoryProperties properties;
    @Autowired
    Clock clock;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    /** A poller whose clock runs {@code ahead} of the service's clock, with the configured batch size. */
    private ReservationExpiryPoller pollerAhead(Duration ahead) {
        return new ReservationExpiryPoller(reservations, properties, Clock.offset(clock, ahead));
    }

    private ReservationExpiryPoller pollerAhead(Duration ahead, int batchSize) {
        InventoryProperties small = new InventoryProperties();
        small.getExpiry().setBatchSize(batchSize);
        return new ReservationExpiryPoller(reservations, small, Clock.offset(clock, ahead));
    }

    private void reserve(UUID orderId, List<Map.Entry<UUID, Integer>> lines) {
        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", lines));
        assertThat(reservationRow(orderId).get("status")).isEqualTo("RESERVED");
    }

    private String status(UUID orderId) {
        return (String) reservationRow(orderId).get("status");
    }

    private List<String> availability(UUID productId) {
        return events(productId, "ProductAvailabilityChanged").stream()
                .map(e -> e.get("inStock").asBoolean() + "@" + e.get("stockVersion").asLong())
                .toList();
    }

    @Test
    void settingsHaveTheContractDefaults() {
        assertThat(properties.getReservationTtl()).isEqualTo(Duration.ofMinutes(20));   // ${RESERVATION_TTL:20m}
        assertThat(properties.getExpiry().getInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getExpiry().getBatchSize()).isEqualTo(20);
        assertThatThrownBy(() -> properties.setReservationTtl(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryProperties().getExpiry().setBatchSize(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnpaidReservationIsReleasedOnceItsTtlHasPassed() throws Exception {
        UUID eggs = newProductWithStock(vendorId, "Eggs", 8);                          // threshold 5
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID orderId = Ids.newId();
        reserve(orderId, List.of(Map.entry(eggs, 8), Map.entry(rice, 2)));
        UUID reservationId = UUID.fromString(events(orderId, "StockReserved").getFirst().get("reservationId").asString());
        assertThat(availability(eggs)).containsExactly("true@1", "false@2");

        pollerAhead(Duration.ofMinutes(19)).releaseExpired();                          // not yet due
        assertThat(status(orderId)).isEqualTo("RESERVED");
        assertThat(reserved(eggs)).isEqualTo(8);

        assertThat(pollerAhead(PAST_TTL).releaseExpired()).isPositive();

        assertThat(status(orderId)).isEqualTo("RELEASED");
        assertThat(reserved(eggs)).isZero();
        assertThat(reserved(rice)).isZero();
        assertThat(onHand(eggs)).isEqualTo(8);
        assertThat(movements(orderId, "RELEASE")).isEqualTo(2);
        assertThat(reservationEventTypes(orderId))
                .containsExactly("StockReserved", "StockReleased", "StockReservationExpired");
        assertThat(outboxRow(orderId, "StockReservationExpired"))
                .containsEntry("topic", "inventory.events")
                .containsEntry("aggregate_type", "Reservation")
                .containsEntry("aggregate_id", orderId.toString());
        JsonNode released = events(orderId, "StockReleased").getFirst();
        assertThat(released.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("orderId", "reservationId", "reason");
        assertThat(released.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(released.get("reservationId").asString()).isEqualTo(reservationId.toString());
        assertThat(released.get("reason").asString()).isEqualTo("RESERVATION_EXPIRED");
        JsonNode expired = events(orderId, "StockReservationExpired").getFirst();
        assertThat(expired.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("orderId", "reservationId");
        assertThat(expired.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(expired.get("reservationId").asString()).isEqualTo(reservationId.toString());
        // the Phase 4 stock events still follow every transition
        assertThat(availability(eggs)).containsExactly("true@1", "false@2", "true@3");
        assertThat(events(rice, "ProductAvailabilityChanged")).hasSize(1);             // 10 → 8 → 10: no flip

        // Nothing is released twice: another run, then the cancellation order-service sends for the expired order
        long version = stockVersion(eggs);
        pollerAhead(Duration.ofHours(1)).releaseExpired();
        String cancelled = newEventId();
        orderListener.on(orderCancelled(cancelled, orderId, "GROCERY"));
        assertThat(processed(cancelled)).isEqualTo(1);
        // nor reserved again by a redelivered OrderCreated
        orderListener.on(orderCreated(newEventId(), orderId, "GROCERY", List.of(Map.entry(eggs, 8))));
        assertThat(stockVersion(eggs)).isEqualTo(version);
        assertThat(movements(orderId, "RELEASE")).isEqualTo(2);
        assertThat(reservationEventTypes(orderId))
                .containsExactly("StockReserved", "StockReleased", "StockReservationExpired");
    }

    @Test
    void aConfirmedOrderKeepsItsStockPastTheTtl() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID orderId = Ids.newId();
        reserve(orderId, List.of(Map.entry(rice, 4)));
        orderListener.on(orderConfirmed(newEventId(), orderId, "GROCERY"));
        assertThat(reservationRow(orderId).get("expires_at")).isNull();

        pollerAhead(PAST_TTL).releaseExpired();
        pollerAhead(Duration.ofDays(3)).releaseExpired();

        assertThat(status(orderId)).isEqualTo("RESERVED");
        assertThat(reservationRow(orderId).get("expires_at")).isNull();
        assertThat(reserved(rice)).isEqualTo(4);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved");

        // Held until pickup — or until the order is cancelled
        orderListener.on(orderCancelled(newEventId(), orderId, "GROCERY"));
        assertThat(reserved(rice)).isZero();
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockReleased");
        assertThat(events(orderId, "StockReleased").getFirst().get("reason").asString()).isEqualTo("ORDER_CANCELLED");
    }

    @Test
    void aReservationLockedByAnotherTransactionIsSkippedNotWaitedFor() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID busy = Ids.newId();
        UUID free = Ids.newId();
        reserve(busy, List.of(Map.entry(rice, 2)));
        reserve(free, List.of(Map.entry(rice, 3)));

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // e.g. another replica's batch, or an OrderConfirmed in flight, holds the reservation row
            execute(other, "SELECT id FROM stock_reservation WHERE order_id = UUID_TO_BIN(?) FOR UPDATE", busy);

            long started = System.nanoTime();
            pollerAhead(PAST_TTL).releaseExpired();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));

            assertThat(status(free)).isEqualTo("RELEASED");
            assertThat(status(busy)).isEqualTo("RESERVED");                          // skipped, not waited for
            assertThat(reserved(rice)).isEqualTo(2);
            other.rollback();
        }

        pollerAhead(PAST_TTL).releaseExpired();                                       // the next run takes it
        assertThat(status(busy)).isEqualTo("RELEASED");
        assertThat(reserved(rice)).isZero();
        assertThat(reservationEventTypes(busy))
                .containsExactly("StockReserved", "StockReleased", "StockReservationExpired");
    }

    @Test
    void twoPollersAtTheSameTimeReleaseEveryReservationExactlyOnce() throws Exception {
        UUID atta = newProductWithStock(vendorId, "Atta", 100);
        UUID oil = newProductWithStock(vendorId, "Oil", 100);
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID orderId = Ids.newId();
            reserve(orderId, List.of(Map.entry(atta, 1), Map.entry(oil, 2)));
            orders.add(orderId);
        }
        assertThat(reserved(atta)).isEqualTo(12);
        assertThat(reserved(oil)).isEqualTo(24);

        // two replicas, small batches, released together
        ReservationExpiryPoller first = pollerAhead(PAST_TTL, 3);
        ReservationExpiryPoller second = pollerAhead(PAST_TTL, 3);
        List<Object> results = race(List.of(first::releaseExpired, second::releaseExpired));

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(Integer.class));
        assertThat(results.stream().mapToInt(r -> (Integer) r).sum()).isGreaterThanOrEqualTo(12);
        assertThat(reserved(atta)).isZero();
        assertThat(reserved(oil)).isZero();
        assertThat(stockVersion(atta)).isEqualTo(1 + 12 + 12);                       // increase, 12 reserves, 12 releases
        for (UUID orderId : orders) {
            assertThat(status(orderId)).isEqualTo("RELEASED");
            assertThat(movements(orderId, "RELEASE")).isEqualTo(2);
            assertThat(reservationEventTypes(orderId))
                    .containsExactly("StockReserved", "StockReleased", "StockReservationExpired");
        }
    }

    /**
     * The one lock-order exception (see ReservationService#releaseExpired): an expiry batch holds the reservation
     * and wants the stock row, while a cancellation of the same order holds the stock row and wants the
     * reservation. InnoDB detects the deadlock at once and rolls one back; here the cancellation is the lighter
     * transaction. Its failure is transient (Kafka retries the record, no dead letter) and left nothing behind
     * (not even the processed mark), so the retry releases the stock exactly once.
     */
    @Test
    void aCancellationDeadlockingWithAnExpiryBatchIsRetried() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        UUID orderId = Ids.newId();
        reserve(orderId, List.of(Map.entry(rice, 3)));
        String cancelled = newEventId();
        Throwable failure;
        SQLException batchFailure = null;

        try (Connection batch = dataSource.getConnection()) {
            batch.setAutoCommit(false);
            // a busy expiry batch: plenty of changes already (= the heavier transaction), then claims the reservation
            for (int i = 0; i < 200; i++) {
                try (PreparedStatement insert = batch.prepareStatement(
                        "INSERT INTO processed_event (event_id, consumer, processed_at) VALUES (?, 'test-weight', NOW(6))")) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.executeUpdate();
                }
            }
            execute(batch, "SELECT id FROM stock_reservation WHERE order_id = UUID_TO_BIN(?) FOR UPDATE", orderId);

            // OrderCancelled: marks the event, locks the stock row, then waits for the reservation row
            CompletableFuture<Void> cancel = CompletableFuture.runAsync(
                    () -> orderListener.on(orderCancelled(cancelled, orderId, "GROCERY")), executor);
            Thread.sleep(700);
            assertThat(cancel).isNotDone();

            // the batch now wants the stock row the cancellation holds: a cycle
            try {
                execute(batch, "SELECT product_id FROM stock_item WHERE product_id = UUID_TO_BIN(?) FOR UPDATE", rice);
            } catch (SQLException e) {
                batchFailure = e;
            }
            failure = catchFailure(cancel);
            batch.rollback();                                                          // the batch gives up too
        }

        assertThat((Throwable) batchFailure).as("the heavier expiry batch should survive the deadlock").isNull();
        assertThat(failure).as("the cancellation should be the deadlock victim").isNotNull();
        assertThat(TransientFailures.isTransient(failure)).isTrue();
        assertThat(processed(cancelled)).isZero();                                    // rolled back with it
        assertThat(reserved(rice)).isEqualTo(3);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved");

        orderListener.on(orderCancelled(cancelled, orderId, "GROCERY"));              // Kafka's retry
        assertThat(processed(cancelled)).isEqualTo(1);
        assertThat(reserved(rice)).isZero();
        assertThat(movements(orderId, "RELEASE")).isEqualTo(1);
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockReleased");
        pollerAhead(PAST_TTL).releaseExpired();
        assertThat(reservationEventTypes(orderId)).containsExactly("StockReserved", "StockReleased");
    }

    private static Throwable catchFailure(CompletableFuture<Void> task) throws Exception {
        try {
            task.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /** Runs all tasks at the same moment; returns each task's result or the exception it threw. */
    private List<Object> race(List<Callable<?>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        return results;
    }

    private static void execute(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.execute();
        }
    }
}
