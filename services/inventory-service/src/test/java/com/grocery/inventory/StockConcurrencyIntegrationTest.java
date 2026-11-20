package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.inventory.dto.InventoryDtos.AdjustmentRequest;
import com.grocery.inventory.dto.InventoryDtos.BulkAdjustmentItem;
import com.grocery.inventory.dto.InventoryDtos.BulkAdjustmentRequest;
import com.grocery.inventory.dto.InventoryDtos.StockView;
import com.grocery.inventory.service.InventoryException;
import com.grocery.inventory.service.ReservationFailedException;
import com.grocery.inventory.service.ReservationService;
import com.grocery.inventory.service.ReservationService.ReservationLine;
import com.grocery.inventory.service.ReservationService.ReservationView;
import com.grocery.inventory.service.StockAdjustmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance criterion "concurrent stock updates never oversell or lose writes": many threads change the same
 * stock at once (released together by a latch) and the result must equal the serial result exactly.
 */
class StockConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(15);

    @Autowired
    StockAdjustmentService adjustments;
    @Autowired
    ReservationService reservations;

    private final ExecutorService executor = Executors.newFixedThreadPool(32);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
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

    @Test
    void parallelAdjustmentsOfOneProductNeverLoseAnUpdate() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Atta 5 kg", 100);
        CurrentUser user = vendorUser(vendorId);
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            AdjustmentRequest request = i % 3 == 0
                    ? new AdjustmentRequest("DECREASE", 3, "DAMAGED", "thread " + i)
                    : new AdjustmentRequest("INCREASE", 5, "RESTOCK", "thread " + i);
            tasks.add(() -> adjustments.adjust(user, productId, request));
        }

        List<Object> results = race(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        assertThat(onHand(productId)).isEqualTo(100 + 16 * 5 - 8 * 3);              // 156: no lost update
        assertThat(stockVersion(productId)).isEqualTo(25);
        assertThat(movementCount(productId)).isEqualTo(25);
        // Each change was applied on top of the previous one: versions 1..25, onHand(v) = onHand(v-1) + delta(v)
        List<JsonNode> adjusted = eventsByVersion(productId, "StockAdjusted");
        assertThat(adjusted).extracting(e -> e.get("stockVersion").asLong())
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 25).boxed().toList());
        int previous = 0;
        for (JsonNode event : adjusted) {
            assertThat(event.get("onHand").asInt()).isEqualTo(previous + event.get("delta").asInt());
            previous = event.get("onHand").asInt();
        }
        assertThat(previous).isEqualTo(156);
    }

    @Test
    void parallelReservationsNeverOversell() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Eggs (12)", 10);
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            UUID orderId = Ids.newId();
            tasks.add(() -> reservations.reserve(orderId, List.of(new ReservationLine(productId, 1)), TTL));
        }

        List<Object> results = race(tasks);

        assertThat(results.stream().filter(ReservationView.class::isInstance)).hasSize(10);
        assertThat(results.stream().filter(ReservationFailedException.class::isInstance)).hasSize(15);
        assertThat(results).allSatisfy(r -> assertThat(r)
                .isInstanceOfAny(ReservationView.class, ReservationFailedException.class));
        JsonNode view = stock(vendorId, productId);
        assertThat(view.get("onHand").asInt()).isEqualTo(10);
        assertThat(view.get("reserved").asInt()).isEqualTo(10);
        assertThat(view.get("available").asInt()).isZero();
        assertThat(view.get("inStock").asBoolean()).isFalse();
        assertThat(stockVersion(productId)).isEqualTo(11);
        assertThat(jdbc().sql("""
                SELECT COUNT(*) FROM stock_reservation r JOIN stock_reservation_item i ON i.reservation_id = r.id
                WHERE i.product_id = UUID_TO_BIN(?) AND r.status = 'RESERVED'""")
                .param(productId.toString()).query(Long.class).single()).isEqualTo(10);
        // Every RESERVE movement saw a valid state; availability flipped to false exactly once, at the last one
        assertThat(jdbc().sql("SELECT MAX(reserved_after) FROM stock_movement WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Integer.class).single()).isEqualTo(10);
        assertThat(eventsByVersion(productId, "ProductAvailabilityChanged"))
                .extracting(e -> e.get("inStock").asBoolean() + "@" + e.get("stockVersion").asLong())
                .containsExactly("true@1", "false@11");
        assertThat(events(productId, "LowStockDetected")).singleElement()
                .satisfies(e -> assertThat(e.get("available").asInt()).isEqualTo(5));
    }

    @Test
    void theSameOrderReservedInParallelHoldsStockOnce() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Paneer", 10);
        UUID orderId = Ids.newId();
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> reservations.reserve(orderId, List.of(new ReservationLine(productId, 3)), TTL));
        }

        List<Object> results = race(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(ReservationView.class));
        assertThat(results.stream().map(r -> ((ReservationView) r).reservationId()).distinct()).hasSize(1);
        assertThat(reserved(productId)).isEqualTo(3);
        assertThat(onHand(productId)).isEqualTo(10);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM stock_movement WHERE reference = ? AND type = 'RESERVE'")
                .param(orderId.toString()).query(Long.class).single()).isEqualTo(1);

        // Parallel release and commit of the same order: exactly one wins, the stock maths stay right
        List<Object> outcomes = race(List.of(() -> reservations.release(orderId), () -> reservations.commit(orderId)));
        assertThat(reserved(productId)).isZero();
        int onHand = onHand(productId);
        assertThat(onHand).isIn(7, 10);                                       // committed, or released
        assertThat(outcomes).hasSize(2);
    }

    @Test
    void reservationsAndAdjustmentsInterleaveWithoutOverselling() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Curd", 20);
        CurrentUser user = vendorUser(vendorId);
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID orderId = Ids.newId();
            tasks.add(() -> reservations.reserve(orderId, List.of(new ReservationLine(productId, 2)), TTL));
            tasks.add(() -> adjustments.adjust(user, productId, new AdjustmentRequest("DECREASE", 1, "DAMAGED", null)));
        }

        List<Object> results = race(tasks);

        long reservedOk = results.stream().filter(ReservationView.class::isInstance).count();
        long decreasedOk = results.stream().filter(StockView.class::isInstance).count();
        assertThat(results).allMatch(r -> r instanceof ReservationView || r instanceof ReservationFailedException
                || r instanceof StockView
                || r instanceof InventoryException e && e.errorCode().equals("INSUFFICIENT_STOCK"));
        assertThat(reservedOk).isPositive();
        assertThat(decreasedOk).isPositive();
        assertThat(reserved(productId)).isEqualTo((int) reservedOk * 2);
        assertThat(onHand(productId)).isEqualTo(20 - (int) decreasedOk);
        assertThat(onHand(productId)).isGreaterThanOrEqualTo(reserved(productId));
        assertThat(stockVersion(productId)).isEqualTo(1 + reservedOk + decreasedOk);
    }

    @Test
    void bulkAdjustmentsInOppositeOrderDoNotDeadlock() throws Exception {
        UUID first = newProduct(vendorId, "Lentils");
        // High bit set: Java's signed UUID.compareTo and MySQL's byte order disagree about it
        UUID random = UUID.randomUUID();
        UUID second = new UUID(random.getMostSignificantBits() | Long.MIN_VALUE, random.getLeastSignificantBits());
        upsert(second, vendorId, "Chickpeas", null, "PUBLISHED", 1);
        CurrentUser user = vendorUser(vendorId);
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            List<UUID> order = i % 2 == 0 ? List.of(first, second) : List.of(second, first);
            tasks.add(() -> adjustments.bulkAdjust(user, new BulkAdjustmentRequest(order.stream()
                    .map(id -> new BulkAdjustmentItem(id, "INCREASE", 1, "RESTOCK", null)).toList())));
        }

        List<Object> results = race(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        assertThat(onHand(first)).isEqualTo(20);
        assertThat(onHand(second)).isEqualTo(20);
        assertThat(stockVersion(first)).isEqualTo(20);
    }

    @Test
    void anAdjustmentWaitsForTheRowLockAndAppliesOnTopOfTheCommittedChange() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Honey", 10);
        ExecutorService single = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // Another stock change in flight: row locked, on hand changed, version bumped, not yet committed
            execute(other, "SELECT product_id FROM stock_item WHERE product_id = UUID_TO_BIN(?) FOR UPDATE", productId);
            execute(other, "UPDATE stock_item SET on_hand = on_hand + 7, stock_version = stock_version + 1 "
                    + "WHERE product_id = UUID_TO_BIN(?)", productId);

            CompletableFuture<Integer> increase = CompletableFuture.supplyAsync(() -> {
                try {
                    return mvc.perform(post("/api/vendor/inventory/{id}/adjustments", productId).with(vendor(vendorId))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"type\":\"INCREASE\",\"quantity\":3,\"reason\":\"RESTOCK\"}"))
                            .andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, single);
            Thread.sleep(700);
            assertThat(increase).isNotDone();                                // blocked on FOR UPDATE

            other.commit();
            assertThat(increase.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            single.shutdownNow();
        }
        assertThat(onHand(productId)).isEqualTo(20);                         // 10 + 7 + 3
        JsonNode adjusted = events(productId, "StockAdjusted").getLast();
        assertThat(adjusted.get("onHand").asInt()).isEqualTo(20);
        assertThat(adjusted.get("stockVersion").asLong()).isEqualTo(3);      // 1 increase, 2 other writer, 3 ours
    }

    private static void execute(Connection connection, String sql, UUID productId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, productId.toString());
            statement.execute();
        }
    }
}
