package com.grocery.order;

import com.grocery.order.config.OrderProperties;
import com.grocery.order.service.StuckOrderWatchdog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The stuck-order watchdog (Phase 10 contract §4): every row of the threshold table, first detection only
 * ({@code OrderStuckDetected} once per order and status, also with two replicas at once), the flag resolving itself on
 * the order's next status change and in the job, and {@code GET /api/admin/orders/stuck}. The watchdog runs at the real
 * time; the tests move their orders' times back with SQL. It sees every order of the shared database, so the
 * assertions look at the tests' own orders only.
 */
class StuckOrderWatchdogIntegrationTest extends AbstractIntegrationTest {

    private final UUID admin = UUID.randomUUID();
    @Autowired
    private OrderProperties properties;

    private static Instant minutesAgo(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant at(Object datetime) {
        return datetime == null ? null : ((LocalDateTime) datetime).toInstant(ZoneOffset.UTC);
    }

    private long stuckEvents(UUID orderId) {
        return orderEventTypes(orderId).stream().filter("OrderStuckDetected"::equals).count();
    }

    private JsonNode stuckList(String role) throws Exception {
        return body(mvc.perform(get("/api/admin/orders/stuck").with(as(admin, role))).andExpect(status().isOk()));
    }

    /** The list's entries of these orders, in the list's order. */
    private static List<JsonNode> entriesOf(JsonNode list, UUID... orders) {
        List<String> ids = Arrays.stream(orders).map(UUID::toString).toList();
        return list.valueStream().filter(entry -> ids.contains(entry.get("orderId").asString())).toList();
    }

    // ---------------------------------------------------------------- detection

    @Test
    void everyStatusIsFlaggedOnceItsThresholdHasPassed() throws Exception {
        UUID created = newGroceryOrder(UUID.randomUUID());
        movedBack(created, "created_at", "CREATED", minutesAgo(3));                  // > 2 min
        UUID awaiting = awaitingPaymentGroceryOrder(UUID.randomUUID());
        movedBack(awaiting, "payment_due_at", null, minutesAgo(3));                  // dueAt + 2 min passed
        movedBack(awaiting, "created_at", "AWAITING_PAYMENT", minutesAgo(18));
        UUID confirmed = confirmedOrder(UUID.randomUUID());
        movedBack(confirmed, "confirmed_at", "CONFIRMED", minutesAgo(16));          // > 15 min
        UUID accepted = acceptedOrder(UUID.randomUUID());
        movedBack(accepted, "ready_by", "ACCEPTED", minutesAgo(31));                // readyBy + 30 min passed
        UUID preparing = preparingOrder(UUID.randomUUID());
        movedBack(preparing, "ready_by", "PREPARING", minutesAgo(31));
        UUID ready = readyOrder(UUID.randomUUID());
        movedBack(ready, "ready_at", "READY_FOR_PICKUP", minutesAgo(31));           // > 30 min
        UUID outForDelivery = outForDeliveryOrder(UUID.randomUUID());
        movedBack(outForDelivery, "picked_up_at", "OUT_FOR_DELIVERY", minutesAgo(91)); // > 90 min

        StuckOrderWatchdog.Run run = watchdog.run();

        assertThat(run.flagged()).isGreaterThanOrEqualTo(7);
        Map<UUID, String[]> expected = Map.of(
                created, new String[]{"CREATED", "STOCK_CHECK_OVERDUE"},
                awaiting, new String[]{"AWAITING_PAYMENT", "PAYMENT_EXPIRY_OVERDUE"},
                confirmed, new String[]{"CONFIRMED", "STORE_RESPONSE_OVERDUE"},
                accepted, new String[]{"ACCEPTED", "PREPARATION_OVERDUE"},
                preparing, new String[]{"PREPARING", "PREPARATION_OVERDUE"},
                ready, new String[]{"READY_FOR_PICKUP", "PICKUP_OVERDUE"},
                outForDelivery, new String[]{"OUT_FOR_DELIVERY", "DELIVERY_OVERDUE"});
        expected.forEach((orderId, statusAndReason) -> {
            Map<String, Map<String, Object>> flags = stuckFlags(orderId);
            assertThat(flags).as("flags of %s", statusAndReason[0]).containsOnlyKeys(statusAndReason[0]);
            Map<String, Object> flag = flags.get(statusAndReason[0]);
            assertThat(flag.get("reason")).isEqualTo(statusAndReason[1]);
            assertThat(flag.get("resolved_at")).isNull();
            List<JsonNode> published = orderEvents(orderId, "OrderStuckDetected");
            assertThat(published).as("OrderStuckDetected of %s", statusAndReason[0]).hasSize(1);
            assertThat(published.getFirst().get("status").asString()).isEqualTo(statusAndReason[0]);
            assertThat(published.getFirst().get("reason").asString()).isEqualTo(statusAndReason[1]);
        });

        // since = when the order entered the status (its timeline entry); due = the threshold
        Map<String, Object> createdFlag = stuckFlags(created).get("CREATED");
        Instant createdAt = at(createdFlag.get("since"));
        assertThat(createdAt).isCloseTo(minutesAgo(3), within(5, ChronoUnit.SECONDS));
        assertThat(at(createdFlag.get("due_at"))).isEqualTo(createdAt.plus(Duration.ofMinutes(2)));
        assertThat(at(stuckFlags(awaiting).get("AWAITING_PAYMENT").get("since")))
                .isCloseTo(minutesAgo(18), within(5, ChronoUnit.SECONDS));
        assertThat(at(stuckFlags(awaiting).get("AWAITING_PAYMENT").get("due_at")))
                .isCloseTo(minutesAgo(1), within(5, ChronoUnit.SECONDS));

        // the event (schema 1.3.0): who, what, since when, detected when; keyed by the order, aggregate Order
        JsonNode event = orderEvents(created, "OrderStuckDetected").getFirst();
        assertThat(fields(event)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "status", "reason", "since", "detectedAt");
        assertThat(event.get("orderId").asString()).isEqualTo(created.toString());
        assertThat(event.get("orderNumber").asString()).startsWith("GA-");
        assertThat(event.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(event.get("storeId").asString()).isEqualTo(storeIdOf(created).toString());
        assertThat(Instant.parse(event.get("since").asString())).isEqualTo(createdAt);
        assertThat(Instant.parse(event.get("detectedAt").asString()))
                .isEqualTo(at(createdFlag.get("detected_at")))
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(outboxRow(created, "OrderStuckDetected")).containsEntry("topic", "order.events")
                .containsEntry("aggregate_type", "Order").containsEntry("aggregate_id", created.toString());
        // the order itself does not change
        assertThat(orderRow(created).get("status")).isEqualTo("CREATED");

        // first detection only: the next runs publish nothing more
        watchdog.run();
        watchdog.run();
        expected.keySet().forEach(orderId -> assertThat(stuckEvents(orderId)).isEqualTo(1));
    }

    @Test
    void ordersInsideTheirThresholdAndFinishedOrdersAreNotFlagged() throws Exception {
        UUID young = newGroceryOrder(UUID.randomUUID());
        movedBack(young, "created_at", "CREATED", minutesAgo(1));                    // 1 of 2 min
        UUID paying = awaitingPaymentGroceryOrder(UUID.randomUUID());                // due in 15 min
        UUID justConfirmed = confirmedOrder(UUID.randomUUID());
        movedBack(justConfirmed, "confirmed_at", "CONFIRMED", minutesAgo(14));       // 14 of 15 min
        UUID cooking = acceptedOrder(UUID.randomUUID());
        movedBack(cooking, "ready_by", null, minutesAgo(29));                       // readyBy + 29 of 30 min
        UUID delivered = outForDeliveryOrder(UUID.randomUUID());
        deliveryListener.on(deliveryCompleted(newEventId(), delivered, partnerOf(delivered), Instant.now()));
        movedBack(delivered, "picked_up_at", null, minutesAgo(300));                 // terminal: never stuck
        UUID rejected = newGroceryOrder(UUID.randomUUID());
        inventoryListener.on(stockReservationFailed(newEventId(), rejected, Map.of(UUID.randomUUID(), new int[]{1, 0})));
        movedBack(rejected, "created_at", null, minutesAgo(300));

        watchdog.run();

        for (UUID orderId : List.of(young, paying, justConfirmed, cooking, delivered, rejected)) {
            assertThat(stuckFlags(orderId)).isEmpty();
            assertThat(stuckEvents(orderId)).isZero();
        }
    }

    @Test
    void thresholdsComeFromTheSettings() {
        // the contract's defaults, bound from application.yml (env ORDER_WATCHDOG_INTERVAL, ORDER_STUCK_*)
        OrderProperties.Watchdog settings = properties.getWatchdog();
        assertThat(settings.isEnabled()).isFalse();                                  // tests run it themselves
        assertThat(settings.getBatchSize()).isEqualTo(100);
        assertThat(settings.getInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.getCreatedAfter()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.getPaymentGrace()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.getConfirmedAfter()).isEqualTo(Duration.ofMinutes(15));
        assertThat(settings.getPreparationGrace()).isEqualTo(Duration.ofMinutes(30));
        assertThat(settings.getPickupAfter()).isEqualTo(Duration.ofMinutes(30));
        assertThat(settings.getDeliveryAfter()).isEqualTo(Duration.ofMinutes(90));
    }

    // ---------------------------------------------------------------- resolution

    @Test
    void aStockCheckFlagResolvesItselfWhenTheReservationArrives() throws Exception {
        UUID orderId = newGroceryOrder(UUID.randomUUID());
        movedBack(orderId, "created_at", "CREATED", minutesAgo(5));
        watchdog.run();
        assertThat(stuckFlags(orderId).get("CREATED").get("resolved_at")).isNull();
        assertThat(entriesOf(stuckList("SUPPORT"), orderId)).hasSize(1);

        inventoryListener.on(stockReserved(newEventId(), orderId));                  // CREATED → AWAITING_PAYMENT

        // resolved in the transaction of the status change, before any watchdog run
        Instant resolvedAt = at(stuckFlags(orderId).get("CREATED").get("resolved_at"));
        assertThat(resolvedAt).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(entriesOf(stuckList("SUPPORT"), orderId)).isEmpty();
        watchdog.run();
        assertThat(stuckFlags(orderId)).containsOnlyKeys("CREATED");                 // not stuck in the new status
        assertThat(stuckEvents(orderId)).isEqualTo(1);
    }

    @Test
    void aStoreResponseFlagResolvesOnAcceptanceAndANewStatusIsFlaggedAgain() throws Exception {
        UUID orderId = confirmedOrder(UUID.randomUUID());
        movedBack(orderId, "confirmed_at", "CONFIRMED", minutesAgo(20));
        watchdog.run();
        assertThat(stuckFlags(orderId).get("CONFIRMED").get("reason")).isEqualTo("STORE_RESPONSE_OVERDUE");

        onStoreEvent(storeAccepted(RESTAURANT, newEventId(), orderId, storeIdOf(orderId), 10, Instant.now()));

        assertThat(stuckFlags(orderId).get("CONFIRMED").get("resolved_at")).isNotNull();
        // later the restaurant is late with the food: a flag (and an event) of its own for ACCEPTED
        movedBack(orderId, "ready_by", "ACCEPTED", minutesAgo(45));
        watchdog.run();
        assertThat(stuckFlags(orderId)).containsOnlyKeys("CONFIRMED", "ACCEPTED");
        assertThat(stuckFlags(orderId).get("ACCEPTED").get("reason")).isEqualTo("PREPARATION_OVERDUE");
        assertThat(stuckFlags(orderId).get("ACCEPTED").get("resolved_at")).isNull();
        assertThat(orderEvents(orderId, "OrderStuckDetected")).extracting(e -> e.get("reason").asString())
                .containsExactly("STORE_RESPONSE_OVERDUE", "PREPARATION_OVERDUE");
    }

    @Test
    void aDeliveryFlagResolvesWhenTheOrderIsDelivered() throws Exception {
        UUID orderId = outForDeliveryOrder(UUID.randomUUID());
        movedBack(orderId, "picked_up_at", "OUT_FOR_DELIVERY", minutesAgo(120));
        watchdog.run();
        assertThat(stuckFlags(orderId).get("OUT_FOR_DELIVERY").get("reason")).isEqualTo("DELIVERY_OVERDUE");

        deliveryListener.on(deliveryCompleted(newEventId(), orderId, partnerOf(orderId), Instant.now()));

        assertThat(stuckFlags(orderId).get("OUT_FOR_DELIVERY").get("resolved_at")).isNotNull();
    }

    @Test
    void aPickupFlagResolvesWhenAnAdminCancelsTheOrder() throws Exception {
        UUID orderId = readyOrder(UUID.randomUUID());
        movedBack(orderId, "ready_at", "READY_FOR_PICKUP", minutesAgo(40));
        watchdog.run();
        assertThat(stuckFlags(orderId).get("READY_FOR_PICKUP").get("reason")).isEqualTo("PICKUP_OVERDUE");

        mvc.perform(post("/api/admin/orders/{id}/cancel", orderId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No rider for an hour\"}"))
                .andExpect(status().isOk());

        assertThat(stuckFlags(orderId).get("READY_FOR_PICKUP").get("resolved_at")).isNotNull();
    }

    @Test
    void theJobResolvesAFlagWhoseOrderMovedOnUnnoticed() throws Exception {
        UUID orderId = confirmedOrder(UUID.randomUUID());
        movedBack(orderId, "confirmed_at", "CONFIRMED", minutesAgo(20));
        watchdog.run();
        // the status changes behind the service's back (e.g. a manual fix in the database)
        jdbc().sql("UPDATE customer_order SET status = 'ACCEPTED', ready_by = ? WHERE id = UUID_TO_BIN(?)")
                .param(Instant.now().plus(Duration.ofMinutes(20)).atOffset(ZoneOffset.UTC).toLocalDateTime())
                .param(orderId.toString()).update();
        assertThat(stuckFlags(orderId).get("CONFIRMED").get("resolved_at")).isNull();

        StuckOrderWatchdog.Run run = watchdog.run();

        assertThat(run.resolved()).isGreaterThanOrEqualTo(1);
        assertThat(stuckFlags(orderId).get("CONFIRMED").get("resolved_at")).isNotNull();
        assertThat(stuckFlags(orderId)).containsOnlyKeys("CONFIRMED");
    }

    /** Two replicas running the watchdog at the same moment: every stuck order is flagged and announced once. */
    @Test
    void twoWatchdogsAtOnceFlagEveryOrderOnce() throws Exception {
        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID orderId = newGroceryOrder(UUID.randomUUID());
            movedBack(orderId, "created_at", "CREATED", minutesAgo(10));
            orders.add(orderId);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<StuckOrderWatchdog.Run>> runs = new ArrayList<>();
            for (int replica = 0; replica < 2; replica++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return watchdog.run();
                }));
            }
            start.countDown();
            for (Future<StuckOrderWatchdog.Run> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID orderId : orders) {
            assertThat(stuckFlags(orderId)).containsOnlyKeys("CREATED");
            assertThat(stuckEvents(orderId)).as("OrderStuckDetected of %s", orderId).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- GET /api/admin/orders/stuck

    @Test
    void theStuckListShowsTheLongestOverdueFirst() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID slightly = confirmedOrder(customerId);
        movedBack(slightly, "confirmed_at", "CONFIRMED", minutesAgo(25));            // 10 min overdue
        UUID badly = newGroceryOrder(UUID.randomUUID());
        movedBack(badly, "created_at", "CREATED", minutesAgo(42));                   // 40 min overdue
        UUID medium = readyOrder(UUID.randomUUID());
        movedBack(medium, "ready_at", "READY_FOR_PICKUP", minutesAgo(55));           // 25 min overdue
        UUID resolved = newGroceryOrder(UUID.randomUUID());
        movedBack(resolved, "created_at", "CREATED", minutesAgo(60));
        watchdog.run();
        inventoryListener.on(stockReserved(newEventId(), resolved));                 // no longer stuck

        JsonNode list = stuckList("SUPPORT");

        assertThat(list.isArray()).isTrue();                                          // a plain array, not a page
        List<JsonNode> mine = entriesOf(list, slightly, badly, medium, resolved);
        assertThat(mine).extracting(entry -> entry.get("orderId").asString())
                .containsExactly(badly.toString(), medium.toString(), slightly.toString());
        assertThat(mine).extracting(entry -> entry.get("minutesOverdue").asLong())
                .satisfiesExactly(m -> assertThat(m).isBetween(39L, 41L), m -> assertThat(m).isBetween(24L, 26L),
                        m -> assertThat(m).isBetween(9L, 11L));

        JsonNode entry = mine.get(2);
        assertThat(fields(entry)).containsExactly("orderId", "orderNumber", "type", "status", "reason", "since",
                "detectedAt", "minutesOverdue", "store", "customerId");
        assertThat(entry.get("orderNumber").asString()).startsWith("GA-");
        assertThat(entry.get("type").asString()).isEqualTo("RESTAURANT");
        assertThat(entry.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(entry.get("reason").asString()).isEqualTo("STORE_RESPONSE_OVERDUE");
        assertThat(Instant.parse(entry.get("since").asString())).isCloseTo(minutesAgo(25), within(5, ChronoUnit.SECONDS));
        assertThat(Instant.parse(entry.get("detectedAt").asString())).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(fields(entry.get("store"))).containsExactly("id", "name");
        assertThat(entry.get("store").get("id").asString()).isEqualTo(storeIdOf(slightly).toString());
        assertThat(entry.get("store").get("name").asString()).isEqualTo("Spice Route");
        assertThat(entry.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(mine.get(0).get("store").get("name").asString()).isEqualTo("Green Basket");
    }

    @Test
    void anyAdminMayReadTheStuckList() throws Exception {
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "DELIVERY_MANAGER")) {
            mvc.perform(get("/api/admin/orders/stuck").with(as(admin, role))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/admin/orders/stuck").with(customer(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/orders/stuck").with(as(admin, "VENDOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/orders/stuck")).andExpect(status().isUnauthorized());
    }
}
