package com.grocery.order;

import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.TransientFailures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * delivery.events → orders (docs/api/phase-9-delivery.md §7): every row of the delivery state-guard table, the
 * OrderView's {@code delivery} and {@code trackable}, no cancellation after pickup, the new statuses in the lists and
 * stats, duplicates, events out of state, skipped event types, unknown orders, broken events and an outage. Also the
 * store-location snapshot taken at creation and sent in OrderConfirmed (v3).
 */
class DeliveryEventsIntegrationTest extends AbstractIntegrationTest {

    static final String CONSUMER = "order-service.delivery-events";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private final UUID admin = UUID.randomUUID();

    private static List<String> timeline(JsonNode order) {
        return order.get("timeline").valueStream()
                .map(e -> e.get("status").asString() + (e.get("reason").isNull() ? "" : ":" + e.get("reason").asString()))
                .toList();
    }

    private static Instant instant(JsonNode node) {
        return Instant.parse(node.asString());
    }

    private static String eventId(ConsumerRecord<String, String> record) {
        return new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- store location (creation, OrderConfirmed v3)

    @Test
    void theStoreLocationIsSnapshottedAndSentInOrderConfirmed() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedGroceryOrder(customerId);

        Map<String, Object> row = jdbc().sql("SELECT store_city, store_latitude, store_longitude FROM customer_order "
                + "WHERE id = UUID_TO_BIN(?)").param(orderId.toString()).query().singleRow();
        assertThat(row.get("store_city")).isEqualTo(StubCart.STORE_CITY);
        assertThat(((Number) row.get("store_latitude")).doubleValue()).isEqualTo(StubCart.STORE_LATITUDE);
        assertThat(((Number) row.get("store_longitude")).doubleValue()).isEqualTo(StubCart.STORE_LONGITUDE);

        JsonNode location = orderEvents(orderId, "OrderConfirmed").getFirst().get("storeLocation");
        assertThat(fields(location)).containsExactlyInAnyOrder("name", "city", "latitude", "longitude");
        assertThat(location.get("name").asString()).isEqualTo("Green Basket");
        assertThat(location.get("city").asString()).isEqualTo(StubCart.STORE_CITY);
        assertThat(location.get("latitude").asDouble()).isEqualTo(StubCart.STORE_LATITUDE);
        assertThat(location.get("longitude").asDouble()).isEqualTo(StubCart.STORE_LONGITUDE);
    }

    @Test
    void aStoreWithoutCoordinatesSendsNoStoreLocation() throws Exception {
        // no coordinates at all, and only one of the two: both mean "no location"
        for (Double longitude : new Double[]{null, 77.6}) {
            UUID customerId = UUID.randomUUID();
            defaultAddress(customerId);
            CART.carts.put(customerId, restaurantCart(customerId).at("Mysuru", null, longitude));
            UUID orderId = id(body(place(customerId, newKey(), RESTAURANT_TOTAL)
                    .andExpect(status().isCreated())));
            paymentListener.on(paymentCaptured(newEventId(), orderId, false));

            Map<String, Object> row = jdbc().sql("SELECT store_city, store_latitude, store_longitude "
                    + "FROM customer_order WHERE id = UUID_TO_BIN(?)").param(orderId.toString()).query().singleRow();
            assertThat(row.get("store_city")).isEqualTo("Mysuru");
            assertThat(row.get("store_latitude")).isNull();
            assertThat(row.get("store_longitude")).isNull();
            JsonNode confirmed = orderEvents(orderId, "OrderConfirmed").getFirst();
            assertThat(confirmed.has("storeLocation")).isTrue();
            assertThat(confirmed.get("storeLocation").isNull()).isTrue();
        }
    }

    // ---------------------------------------------------------------- partner assigned / unassigned

    @Test
    void anAssignedPartnerIsRecordedBeforePickupWithoutAStatusChange() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID accepted = acceptedOrder(customerId);
        UUID preparing = preparingOrder(customerId);
        UUID ready = readyOrder(customerId);
        assertThat(order(customerId, accepted).get("delivery").isNull()).isTrue();

        Instant assignedAt = Instant.parse("2026-11-11T10:30:00.123456Z");
        for (UUID orderId : List.of(accepted, preparing, ready)) {
            String status = order(customerId, orderId).get("status").asString();
            List<String> eventsBefore = orderEventTypes(orderId);
            String eventId = newEventId();

            deliveryListener.on(partnerAssigned(eventId, orderId, UUID.randomUUID(), "Ravi Kumar", assignedAt));

            JsonNode order = order(customerId, orderId);
            assertThat(order.get("status").asString()).isEqualTo(status);                // no status change …
            assertThat(orderEventTypes(orderId)).isEqualTo(eventsBefore);               // … and no event
            JsonNode delivery = order.get("delivery");
            assertThat(fields(delivery)).containsExactly("partnerName", "assignedAt", "pickedUpAt", "deliveredAt");
            assertThat(delivery.get("partnerName").asString()).isEqualTo("Ravi");      // first name only
            assertThat(instant(delivery.get("assignedAt"))).isEqualTo(assignedAt);
            assertThat(delivery.get("pickedUpAt").isNull()).isTrue();
            assertThat(delivery.get("deliveredAt").isNull()).isTrue();
            assertThat(order.get("trackable").asBoolean()).isTrue();
            assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        }
        assertThat(jdbc().sql("SELECT partner_name FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(ready.toString()).query(String.class).single()).isEqualTo("Ravi Kumar");   // stored in full
    }

    @Test
    void anAssignmentOutsideItsStatusesIsIgnored() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID confirmed = confirmedOrder(customerId);
        UUID cancelled = confirmedOrder(customerId);
        mvc.perform(post("/api/orders/{id}/cancel", cancelled).with(customer(customerId))).andExpect(status().isOk());
        UUID outForDelivery = outForDeliveryOrder(customerId);
        UUID partnerBefore = partnerOf(outForDelivery);

        for (UUID orderId : List.of(confirmed, cancelled, outForDelivery)) {
            JsonNode before = order(customerId, orderId);
            String eventId = newEventId();
            deliveryListener.on(partnerAssigned(eventId, orderId, UUID.randomUUID(), "Meena", Instant.now()));
            assertThat(order(customerId, orderId)).isEqualTo(before);
            assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        }
        assertThat(partnerOf(confirmed)).isNull();
        assertThat(partnerOf(outForDelivery)).isEqualTo(partnerBefore);
    }

    @Test
    void anUnassignmentClearsTheRecordedPartnerOnly() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = preparingOrder(customerId);
        UUID ravi = UUID.randomUUID();
        UUID meena = UUID.randomUUID();
        deliveryListener.on(partnerAssigned(newEventId(), orderId, ravi, "Ravi", Instant.now()));

        // another partner's (stale) unassignment changes nothing
        String stale = newEventId();
        deliveryListener.on(partnerUnassigned(stale, orderId, meena));
        assertThat(partnerOf(orderId)).isEqualTo(ravi);
        assertThat(processed(stale, CONSUMER)).isEqualTo(1);

        String eventId = newEventId();
        deliveryListener.on(partnerUnassigned(eventId, orderId, ravi));
        JsonNode order = order(customerId, orderId);
        assertThat(order.get("delivery").isNull()).isTrue();
        assertThat(order.get("status").asString()).isEqualTo("PREPARING");
        assertThat(order.get("trackable").asBoolean()).isTrue();
        assertThat(partnerOf(orderId)).isNull();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM customer_order WHERE id = UUID_TO_BIN(?) AND partner_name IS NULL "
                + "AND partner_assigned_at IS NULL").param(orderId.toString()).query(Long.class).single())
                .isEqualTo(1);

        // reassigned to Meena
        deliveryListener.on(partnerAssigned(newEventId(), orderId, meena, "Meena", Instant.now()));
        assertThat(order(customerId, orderId).get("delivery").get("partnerName").asString()).isEqualTo("Meena");
        assertThat(orderEventTypes(orderId)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed",
                "OrderAccepted", "OrderPreparing");

        // after pickup an unassignment is ignored
        UUID picked = outForDeliveryOrder(customerId);
        UUID partner = partnerOf(picked);
        deliveryListener.on(partnerUnassigned(newEventId(), picked, partner));
        assertThat(partnerOf(picked)).isEqualTo(partner);
    }

    @Test
    void aPartnerWithoutANameIsShownWithoutOne() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = acceptedOrder(customerId);
        deliveryListener.on(partnerAssigned(newEventId(), orderId, UUID.randomUUID(), null, Instant.now()));
        JsonNode delivery = order(customerId, orderId).get("delivery");
        assertThat(delivery.get("partnerName").isNull()).isTrue();
        assertThat(delivery.get("assignedAt").isNull()).isFalse();
    }

    // ---------------------------------------------------------------- picked up

    @Test
    void deliveryPickedUpMovesAReadyOrderOutForDelivery() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = readyOrder(customerId);
        UUID partnerId = UUID.randomUUID();
        deliveryListener.on(partnerAssigned(newEventId(), orderId, partnerId, "Ravi Kumar",
                Instant.parse("2026-11-11T10:30:00Z")));
        Instant pickedUpAt = Instant.parse("2026-11-11T10:45:00.654321Z");
        String eventId = newEventId();

        deliveryListener.on(deliveryPickedUp(eventId, orderId, partnerId, pickedUpAt));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(order.get("trackable").asBoolean()).isTrue();
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PAID");
        assertThat(order.get("delivery").get("partnerName").asString()).isEqualTo("Ravi");
        assertThat(instant(order.get("delivery").get("pickedUpAt"))).isEqualTo(pickedUpAt);
        assertThat(timeline(order)).containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED", "ACCEPTED",
                "PREPARING", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);

        assertThat(orderEventTypes(orderId)).endsWith("OrderReadyForPickup", "OrderOutForDelivery");
        JsonNode event = orderEvents(orderId, "OrderOutForDelivery").getFirst();
        assertThat(fields(event)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "partnerId", "pickedUpAt");
        assertThat(event.get("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(event.get("orderNumber").asString()).isEqualTo(order.get("orderNumber").asString());
        assertThat(event.get("orderType").asString()).isEqualTo("RESTAURANT");
        assertThat(event.get("customerId").asString()).isEqualTo(customerId.toString());
        assertThat(event.get("storeId").asString()).isEqualTo(storeIdOf(orderId).toString());
        assertThat(event.get("partnerId").asString()).isEqualTo(partnerId.toString());
        assertThat(instant(event.get("pickedUpAt"))).isEqualTo(pickedUpAt);
        Map<String, Object> outbox = outboxRow(orderId, "OrderOutForDelivery");
        assertThat(outbox.get("topic")).isEqualTo("order.events");
        assertThat(outbox.get("aggregate_type")).isEqualTo("Order");
        assertThat(outbox.get("aggregate_id")).isEqualTo(orderId.toString());

        // redelivered, or picked up again: once only
        deliveryListener.on(deliveryPickedUp(eventId, orderId, partnerId, pickedUpAt));
        deliveryListener.on(deliveryPickedUp(newEventId(), orderId, partnerId, Instant.now()));
        assertThat(orderEvents(orderId, "OrderOutForDelivery")).hasSize(1);
        assertThat(order(customerId, orderId)).isEqualTo(order);
    }

    @Test
    void aGroceryOrderPickedUpWithoutARecordedAssignmentTakesThePickupPartner() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = confirmedGroceryOrder(customerId);
        UUID vendorId = storeIdOf(orderId);
        vendorListener.on(storeAccepted(VENDOR, newEventId(), orderId, vendorId, 15, Instant.now()));
        vendorListener.on(storeReady(VENDOR, newEventId(), orderId, vendorId, Instant.now()));
        UUID partnerId = UUID.randomUUID();

        deliveryListener.on(deliveryPickedUp(newEventId(), orderId, partnerId, Instant.now()));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(order.get("delivery").get("partnerName").isNull()).isTrue();
        assertThat(order.get("delivery").get("assignedAt").isNull()).isTrue();
        assertThat(partnerOf(orderId)).isEqualTo(partnerId);
        JsonNode event = orderEvents(orderId, "OrderOutForDelivery").getFirst();
        assertThat(event.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(event.get("partnerId").asString()).isEqualTo(partnerId.toString());
    }

    @Test
    void aPickupBeforeTheOrderIsReadyOrAfterACancellationIsIgnored() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID preparing = preparingOrder(customerId);
        UUID confirmed = confirmedOrder(customerId);
        UUID cancelled = readyOrder(customerId);
        mvc.perform(post("/api/admin/orders/{id}/cancel", cancelled).with(as(admin, "OPS_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Store closed\"}"))
                .andExpect(status().isOk());

        for (UUID orderId : List.of(preparing, confirmed, cancelled)) {
            JsonNode before = order(customerId, orderId);
            List<String> eventsBefore = orderEventTypes(orderId);
            String eventId = newEventId();
            deliveryListener.on(deliveryPickedUp(eventId, orderId, UUID.randomUUID(), Instant.now()));
            assertThat(order(customerId, orderId)).isEqualTo(before);
            assertThat(orderEventTypes(orderId)).isEqualTo(eventsBefore);
            assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- completed / failed

    @Test
    void deliveryCompletedDeliversTheOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = outForDeliveryOrder(customerId);
        UUID partnerId = partnerOf(orderId);
        Instant deliveredAt = Instant.parse("2026-11-11T11:05:00.000001Z");
        String eventId = newEventId();

        deliveryListener.on(deliveryCompleted(eventId, orderId, partnerId, deliveredAt));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(order.get("trackable").asBoolean()).isFalse();
        assertThat(order.get("cancellable").asBoolean()).isFalse();
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PAID");
        assertThat(order.get("delivery").get("partnerName").asString()).isEqualTo("Ravi");
        assertThat(instant(order.get("delivery").get("deliveredAt"))).isEqualTo(deliveredAt);
        assertThat(timeline(order)).endsWith("READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "DELIVERED");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);

        assertThat(orderEventTypes(orderId)).endsWith("OrderOutForDelivery", "OrderDelivered");
        JsonNode event = orderEvents(orderId, "OrderDelivered").getFirst();
        assertThat(fields(event)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "partnerId", "deliveredAt");
        assertThat(event.get("partnerId").asString()).isEqualTo(partnerId.toString());
        assertThat(instant(event.get("deliveredAt"))).isEqualTo(deliveredAt);
        assertThat(event.get("customerId").asString()).isEqualTo(customerId.toString());

        // DELIVERED is terminal: a late failure or a second completion changes nothing
        deliveryListener.on(deliveryFailed(newEventId(), orderId, partnerId, "OTHER", "late", Instant.now()));
        deliveryListener.on(deliveryCompleted(newEventId(), orderId, partnerId, Instant.now()));
        deliveryListener.on(deliveryCompleted(eventId, orderId, partnerId, deliveredAt));
        assertThat(order(customerId, orderId)).isEqualTo(order);
        assertThat(orderEventTypes(orderId)).containsOnlyOnce("OrderDelivered").doesNotContain("OrderDeliveryFailed");
    }

    @Test
    void deliveryFailedEndsTheOrderAndKeepsThePayment() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = outForDeliveryOrder(customerId);
        UUID partnerId = partnerOf(orderId);
        Instant failedAt = Instant.parse("2026-11-11T11:10:00Z");
        String eventId = newEventId();

        deliveryListener.on(deliveryFailed(eventId, orderId, partnerId, "CUSTOMER_UNREACHABLE",
                "Called three times", failedAt));

        JsonNode order = order(customerId, orderId);
        assertThat(order.get("status").asString()).isEqualTo("DELIVERY_FAILED");
        assertThat(order.get("trackable").asBoolean()).isFalse();
        assertThat(order.get("payment").get("status").asString()).isEqualTo("PAID");   // an admin decides on a refund
        assertThat(order.get("delivery").get("deliveredAt").isNull()).isTrue();
        assertThat(timeline(order)).endsWith("OUT_FOR_DELIVERY", "DELIVERY_FAILED:CUSTOMER_UNREACHABLE");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);

        JsonNode event = orderEvents(orderId, "OrderDeliveryFailed").getFirst();
        assertThat(fields(event)).containsExactlyInAnyOrder("orderId", "orderNumber", "orderType", "customerId",
                "storeId", "partnerId", "reason", "note", "failedAt");
        assertThat(event.get("reason").asString()).isEqualTo("CUSTOMER_UNREACHABLE");
        assertThat(event.get("note").asString()).isEqualTo("Called three times");
        assertThat(event.get("partnerId").asString()).isEqualTo(partnerId.toString());
        assertThat(instant(event.get("failedAt"))).isEqualTo(failedAt);

        // terminal: a late completion changes nothing; no admin cancel either
        deliveryListener.on(deliveryCompleted(newEventId(), orderId, partnerId, Instant.now()));
        assertThat(order(customerId, orderId)).isEqualTo(order);
        mvc.perform(post("/api/admin/orders/{id}/cancel", orderId).with(as(admin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Refund it\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.currentStatus").value("DELIVERY_FAILED"));
    }

    @Test
    void aFailureWithoutANoteHasANullNote() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = outForDeliveryOrder(customerId);
        deliveryListener.on(deliveryFailed(newEventId(), orderId, partnerOf(orderId), "ADDRESS_NOT_FOUND", null,
                Instant.now()));
        JsonNode event = orderEvents(orderId, "OrderDeliveryFailed").getFirst();
        assertThat(event.get("reason").asString()).isEqualTo("ADDRESS_NOT_FOUND");
        assertThat(event.get("note").isNull()).isTrue();
    }

    @Test
    void completionsAndFailuresBeforePickupAreIgnored() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID ready = readyOrder(customerId);
        UUID partnerId = UUID.randomUUID();
        deliveryListener.on(partnerAssigned(newEventId(), ready, partnerId, "Ravi", Instant.now()));
        JsonNode before = order(customerId, ready);
        List<String> eventsBefore = orderEventTypes(ready);

        String completed = newEventId();
        String failed = newEventId();
        deliveryListener.on(deliveryCompleted(completed, ready, partnerId, Instant.now()));
        deliveryListener.on(deliveryFailed(failed, ready, partnerId, "OTHER", "x", Instant.now()));

        assertThat(order(customerId, ready)).isEqualTo(before);
        assertThat(orderEventTypes(ready)).isEqualTo(eventsBefore);
        assertThat(processed(completed, CONSUMER)).isEqualTo(1);
        assertThat(processed(failed, CONSUMER)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- no cancellation after pickup

    @Test
    void nobodyCanCancelAfterPickup() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = outForDeliveryOrder(customerId);

        mvc.perform(post("/api/orders/{id}/cancel", orderId).with(customer(customerId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.currentStatus").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.status").value(409));
        for (String role : List.of("SUPER_ADMIN", "OPS_ADMIN", "SUPPORT")) {
            mvc.perform(post("/api/admin/orders/{id}/cancel", orderId).with(as(admin, role))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too late\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_CANCELLABLE"))
                    .andExpect(jsonPath("$.currentStatus").value("OUT_FOR_DELIVERY"));
        }
        JsonNode adminView = body(mvc.perform(get("/api/admin/orders/{id}", orderId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk()));
        assertThat(adminView.get("adminCancellable").asBoolean()).isFalse();
        assertThat(adminView.get("trackable").asBoolean()).isTrue();
        assertThat(adminView.get("delivery").get("partnerName").asString()).isEqualTo("Ravi");
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(orderEventTypes(orderId)).doesNotContain("OrderCancelled");
    }

    // ---------------------------------------------------------------- lists, stats, trackable

    @Test
    void theDeliveryStatusesAreCountedListedAndTrackable() throws Exception {
        // a day no other test uses (OrderQueriesIntegrationTest picks days from 2031 for about 55 years)
        LocalDate day = LocalDate.of(2100, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(0, 20_000));
        Instant midnight = day.atStartOfDay(INDIA).toInstant();
        UUID customerId = UUID.randomUUID();
        UUID onTheWay = outForDeliveryOrder(customerId);
        UUID delivered = outForDeliveryOrder(customerId);
        deliveryListener.on(deliveryCompleted(newEventId(), delivered, partnerOf(delivered), Instant.now()));
        UUID failed = outForDeliveryOrder(customerId);
        deliveryListener.on(deliveryFailed(newEventId(), failed, partnerOf(failed), "CUSTOMER_REFUSED", null,
                Instant.now()));
        UUID confirmed = confirmedOrder(customerId);
        for (UUID orderId : List.of(onTheWay, delivered, failed, confirmed)) {
            jdbc().sql("UPDATE customer_order SET created_at = ? WHERE id = UUID_TO_BIN(?)")
                    .param(midnight.plusSeconds(3_600).atOffset(ZoneOffset.UTC).toLocalDateTime())
                    .param(orderId.toString()).update();
        }

        JsonNode stats = body(mvc.perform(get("/api/admin/stats").param("date", day.toString())
                .with(as(admin, "OPS_ADMIN"))).andExpect(status().isOk()));
        assertThat(fields(stats.get("byStatus"))).containsExactly("CREATED", "AWAITING_PAYMENT", "CONFIRMED",
                "ACCEPTED", "PREPARING", "READY_FOR_PICKUP", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED",
                "REJECTED", "CANCELLED");
        assertThat(stats.get("orders").asLong()).isEqualTo(4);
        assertThat(stats.get("byStatus").get("OUT_FOR_DELIVERY").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("DELIVERED").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("DELIVERY_FAILED").asLong()).isEqualTo(1);
        assertThat(stats.get("byStatus").get("CONFIRMED").asLong()).isEqualTo(1);

        String c = "?customerId=" + customerId;
        for (Map.Entry<String, UUID> filter : Map.of("OUT_FOR_DELIVERY", onTheWay, "DELIVERED", delivered,
                "DELIVERY_FAILED", failed).entrySet()) {
            JsonNode page = body(mvc.perform(get("/api/admin/orders" + c + "&status=" + filter.getKey())
                    .with(as(admin, "SUPPORT"))).andExpect(status().isOk()));
            assertThat(page.get("content").valueStream().map(o -> o.get("id").asString()))
                    .containsExactly(filter.getValue().toString());
            assertThat(page.get("content").get(0).get("adminCancellable").asBoolean()).isFalse();
            JsonNode mine = body(mvc.perform(get("/api/orders?status=" + filter.getKey()).with(customer(customerId)))
                    .andExpect(status().isOk()));
            assertThat(mine.get("content").valueStream().map(o -> o.get("id").asString()))
                    .containsExactly(filter.getValue().toString());
        }

        Map<UUID, Boolean> trackable = new LinkedHashMap<>();
        trackable.put(confirmed, false);
        trackable.put(onTheWay, true);
        trackable.put(delivered, false);
        trackable.put(failed, false);
        trackable.put(acceptedOrder(customerId), true);
        trackable.put(newRestaurantOrder(customerId), false);
        trackable.forEach((orderId, expected) -> {
            try {
                assertThat(order(customerId, orderId).get("trackable").asBoolean()).as(orderId.toString())
                        .isEqualTo(expected);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        // the new fields sit between rejection and timeline
        List<String> viewFields = fields(order(customerId, onTheWay));
        assertThat(viewFields.subList(viewFields.indexOf("rejection"), viewFields.indexOf("timeline") + 1))
                .containsExactly("rejection", "delivery", "trackable", "timeline");
    }

    // ---------------------------------------------------------------- skipped, unknown, broken, outage

    @Test
    void theOtherDeliveryEventsAreSkippedWithoutTouchingTheDatabase() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = readyOrder(customerId);
        JsonNode before = order(customerId, orderId);
        UUID partnerId = UUID.randomUUID();
        List<ConsumerRecord<String, String>> skipped = List.of(
                deliveryEvent("DeliveryOfferCreated", newEventId(), orderId, Map.of(
                        "offerId", UUID.randomUUID().toString(), "partnerId", partnerId.toString(),
                        "storeName", "Spice Route", "distanceKm", 1.2, "tripKm", 3.4,
                        "expiresAt", Instant.now().toString())),
                deliveryEvent("DeliveryOfferClosed", newEventId(), orderId, Map.of(
                        "offerId", UUID.randomUUID().toString(), "partnerId", partnerId.toString(),
                        "outcome", "EXPIRED", "closedAt", Instant.now().toString())),
                deliveryEvent("DeliveryAssignmentFailed", newEventId(), orderId, Map.of(
                        "attempts", 4, "radiusKm", 10.0, "failedAt", Instant.now().toString())),
                deliveryEvent("DeliveryCancelled", newEventId(), orderId, Map.of(
                        "cancelledAt", Instant.now().toString())),
                record("delivery.events", "DeliveryPartner", "DeliveryPartnerApproved", newEventId(),
                        partnerId.toString(), "{\"partnerId\":\"" + partnerId + "\"}"),
                record("delivery.events", "DeliveryPartner", "DeliveryPartnerRegistered", newEventId(),
                        partnerId.toString(), "not even json"),
                record("delivery.events", "DeliveryPartner", "DeliveryPartnerSuspended", newEventId(),
                        partnerId.toString(), "{\"reason\":\"x\"}"));

        for (ConsumerRecord<String, String> record : skipped) {
            deliveryListener.on(record);
            assertThat(processed(eventId(record), CONSUMER)).as(record.headers().toString()).isZero();
        }
        assertThat(order(customerId, orderId)).isEqualTo(before);
    }

    @Test
    void aDeliveryEventForAnUnknownOrderIsMarkedAndIgnored() {
        UUID unknown = UUID.randomUUID();
        List<ConsumerRecord<String, String>> events = List.of(
                partnerAssigned(newEventId(), unknown, UUID.randomUUID(), "Ravi", Instant.now()),
                partnerUnassigned(newEventId(), unknown, UUID.randomUUID()),
                deliveryPickedUp(newEventId(), unknown, UUID.randomUUID(), Instant.now()),
                deliveryCompleted(newEventId(), unknown, UUID.randomUUID(), Instant.now()),
                deliveryFailed(newEventId(), unknown, UUID.randomUUID(), "OTHER", "x", Instant.now()));
        for (ConsumerRecord<String, String> record : events) {
            deliveryListener.on(record);
            assertThat(processed(eventId(record), CONSUMER)).isEqualTo(1);
        }
        assertThat(orderEventTypes(unknown)).isEmpty();
    }

    @Test
    void brokenDeliveryEventsAreInvalidAndChangeNothing() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = readyOrder(customerId);
        JsonNode before = order(customerId, orderId);
        String noOrder = newEventId();

        assertThatThrownBy(() -> deliveryListener.on(deliveryEvent("DeliveryPickedUp", noOrder, null, Map.of(
                "partnerId", UUID.randomUUID().toString(), "pickedUpAt", Instant.now().toString()))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> deliveryListener.on(deliveryEvent("DeliveryPartnerAssigned", newEventId(), orderId,
                Map.of("partnerName", "Ravi", "assignedAt", Instant.now().toString()))))
                .isInstanceOf(InvalidEventException.class);                                  // no partnerId
        assertThatThrownBy(() -> deliveryListener.on(deliveryEvent("DeliveryFailed", newEventId(), orderId,
                Map.of("partnerId", UUID.randomUUID().toString(), "failedAt", Instant.now().toString()))))
                .isInstanceOf(InvalidEventException.class);                                  // no reason
        assertThatThrownBy(() -> deliveryListener.on(record("delivery.events", "Delivery", "DeliveryCompleted",
                newEventId(), orderId.toString(), "not json")))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> deliveryListener.on(record("delivery.events", "Delivery", "DeliveryPickedUp",
                newEventId(), orderId.toString(),
                "{\"orderId\":\"not-a-uuid\",\"partnerId\":\"" + UUID.randomUUID() + "\"}")))
                .isInstanceOf(InvalidEventException.class);
        ConsumerRecord<String, String> headerless = new ConsumerRecord<>("delivery.events", 0, 0L,
                orderId.toString(), "{\"orderId\":\"" + orderId + "\"}");
        assertThatThrownBy(() -> deliveryListener.on(headerless)).isInstanceOf(InvalidEventException.class);

        assertThat(processed(noOrder, CONSUMER)).isZero();
        assertThat(order(customerId, orderId)).isEqualTo(before);
    }

    /** A lock wait timeout while applying a pickup is transient: not marked, the redelivery applies it. */
    @Test
    void aLockWaitTimeoutOnAPickupIsTransientAndTheRedeliveryAppliesIt() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = readyOrder(customerId);
        String eventId = newEventId();
        ConsumerRecord<String, String> pickedUp = deliveryPickedUp(eventId, orderId, UUID.randomUUID(),
                Instant.now());

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT status FROM customer_order WHERE id = UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, orderId.toString());
                try (ResultSet row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }
            assertThatThrownBy(() -> deliveryListener.on(pickedUp))
                    .satisfies(e -> assertThat(TransientFailures.isTransient(e)).as(e.toString()).isTrue());
            blocker.rollback();
        }
        assertThat(processed(eventId, CONSUMER)).isZero();
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("READY_FOR_PICKUP");

        deliveryListener.on(pickedUp);                                              // the redelivery
        assertThat(order(customerId, orderId).get("status").asString()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(processed(eventId, CONSUMER)).isEqualTo(1);
        assertThat(orderEvents(orderId, "OrderOutForDelivery")).hasSize(1);
    }
}
