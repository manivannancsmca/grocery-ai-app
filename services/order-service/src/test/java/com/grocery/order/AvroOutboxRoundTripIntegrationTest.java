package com.grocery.order;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistrySupport;
import io.confluent.kafka.schemaregistry.client.SchemaMetadata;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0011: every order event the real business operations write to the outbox is also stored as Avro in
 * {@code payload_avro} (Confluent wire format, schema from grocery-event-schemas, subject
 * {@code order.events-com.grocery.events.order.<EventType>}), and decoding it the way every consumer does
 * (grocery-common's {@link AvroEventDeserializer}) gives back the JSON in {@code payload}.
 */
class AvroOutboxRoundTripIntegrationTest extends AbstractIntegrationTest {

    private static final List<String> PHASE_6_EVENTS = List.of("OrderCreated", "OrderAwaitingPayment",
            "OrderConfirmed", "OrderRejected", "OrderCancelled");
    private static final List<String> PHASE_8_EVENTS = List.of("OrderAccepted", "OrderPreparing",
            "OrderReadyForPickup");
    private static final List<String> PHASE_9_EVENTS = List.of("OrderOutForDelivery", "OrderDelivered",
            "OrderDeliveryFailed");
    private static final List<String> PHASE_10_EVENTS = List.of("OrderStuckDetected");
    private static final List<String> PHASE_12_EVENTS = List.of("ReturnRequested", "ReturnApproved", "ReturnRejected",
            "ReturnEscalated");
    /** Schemas 1.8.0: admin settings saves (written here by PlatformSettingsIntegrationTest). */
    private static final List<String> SETTINGS_EVENTS = List.of("PlatformSettingChanged");
    private static final List<String> ORDER_EVENTS = Stream.of(PHASE_6_EVENTS, PHASE_8_EVENTS, PHASE_9_EVENTS,
                    PHASE_10_EVENTS, PHASE_12_EVENTS, SETTINGS_EVENTS)
            .flatMap(List::stream).toList();
    private static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z");

    private final JsonAvroConverter converter = new JsonAvroConverter();
    /** The application's own in-memory registry (same mock:// scope), as a consumer would reach it. */
    private final GroceryAvroProperties avro = avroProperties();
    private final SchemaRegistryClient registry = SchemaRegistrySupport.newClient(avro);
    private final AvroEventDeserializer deserializer = new AvroEventDeserializer(registry, avro, converter);

    private record OutboxRow(String id, String topic, String aggregateId, String eventType, int eventVersion,
                             String payload, byte[] payloadAvro) {
    }

    private static GroceryAvroProperties avroProperties() {
        GroceryAvroProperties properties = new GroceryAvroProperties();
        properties.setSchemaRegistryUrl(SCHEMA_REGISTRY_URL);
        return properties;
    }

    @Test
    void everyOrderEventIsPublishedAsAvroThatDecodesToItsJsonPayload() throws Exception {
        // grocery: CREATED -> AWAITING_PAYMENT (stock reserved) -> CONFIRMED (paid, with the address) -> admin cancel
        UUID paid = awaitingPaymentGroceryOrder(UUID.randomUUID());
        paymentListener.on(paymentCaptured(newEventId(), paid, false));
        mvc.perform(post("/api/admin/orders/{id}/cancel", paid).with(as(UUID.randomUUID(), "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Store closed for the day\"}"))
                .andExpect(status().isOk());
        // restaurant: CREATED + AWAITING_PAYMENT in one transaction, then cancelled by the customer with a note
        UUID restaurantCustomer = UUID.randomUUID();
        UUID restaurant = newRestaurantOrder(restaurantCustomer);
        mvc.perform(post("/api/orders/{id}/cancel", restaurant).with(customer(restaurantCustomer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Ordered by mistake\"}"))
                .andExpect(status().isOk());
        // grocery: out of stock -> REJECTED with its unavailable items
        UUID rejectedCustomer = UUID.randomUUID();
        UUID rejected = newGroceryOrder(rejectedCustomer);
        UUID rice = UUID.fromString(order(rejectedCustomer, rejected).get("items").get(0).get("itemId").asString());
        Map<UUID, int[]> unavailable = new LinkedHashMap<>();
        unavailable.put(rice, new int[]{2, 1});
        inventoryListener.on(stockReservationFailed(newEventId(), rejected, unavailable));
        // grocery: the payment window closed -> cancelled by the system (no actor, no note)
        UUID expired = awaitingPaymentGroceryOrder(UUID.randomUUID());
        paymentListener.on(paymentExpired(newEventId(), expired));

        assertThat(orderEventTypes(paid))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed", "OrderCancelled");
        assertThat(orderEventTypes(restaurant))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");
        assertThat(orderEventTypes(rejected)).containsExactly("OrderCreated", "OrderRejected");
        assertThat(orderEventTypes(expired))
                .containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderCancelled");

        // every row in the table: these orders' events, and whatever the other test classes of this context wrote
        List<OutboxRow> all = outboxRows();
        List<String> orders = Stream.of(paid, restaurant, rejected, expired).map(UUID::toString).toList();
        assertThat(all).filteredOn(row -> orders.contains(row.aggregateId()))
                .hasSize(12)
                .extracting(OutboxRow::eventType).containsAll(PHASE_6_EVENTS);
        all.forEach(this::assertAvroIsTheJsonPayload);
    }

    /**
     * Phase 8 (grocery-event-schemas 1.1.0): the three new order events, OrderConfirmed with its new {@code subtotal}
     * and {@code note}, and OrderCancelled with the new reasons, actor and previous statuses.
     */
    @Test
    void theStoreEventsAndTheEvolvedOrderEventsArePublishedAsAvro() throws Exception {
        // restaurant, with a delivery note: CONFIRMED -> ACCEPTED -> PREPARING -> READY_FOR_PICKUP -> admin cancel
        UUID noteCustomer = UUID.randomUUID();
        defaultAddress(noteCustomer);
        restaurantCart(noteCustomer);
        UUID served = id(body(placeRequest(noteCustomer, newKey(),
                placeBody(null, RESTAURANT_TOTAL, "No onions, extra raita")).andExpect(status().isCreated())));
        paymentListener.on(paymentCaptured(newEventId(), served, false));
        UUID restaurantId = storeIdOf(served);
        restaurantListener.on(storeAccepted(RESTAURANT, newEventId(), served, restaurantId, 20,
                Instant.parse("2026-11-11T12:00:00.123456789Z")));
        restaurantListener.on(storePreparing(RESTAURANT, newEventId(), served, restaurantId, Instant.now()));
        restaurantListener.on(storeReady(RESTAURANT, newEventId(), served, restaurantId, Instant.now()));
        mvc.perform(post("/api/admin/orders/{id}/cancel", served).with(as(UUID.randomUUID(), "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Rider strike\"}"))
                .andExpect(status().isOk());
        // grocery: the vendor rejects with a note (STORE_REJECTED by STORE)
        UUID rejected = confirmedGroceryOrder(UUID.randomUUID());
        vendorListener.on(storeRejected(VENDOR, newEventId(), rejected, storeIdOf(rejected), "OUT_OF_STOCK",
                "Toor dal is over", false, UUID.randomUUID()));
        // restaurant: nobody accepted in time (STORE_NO_RESPONSE by SYSTEM)
        UUID noResponse = confirmedOrder(UUID.randomUUID());
        restaurantListener.on(storeRejected(RESTAURANT, newEventId(), noResponse, storeIdOf(noResponse),
                "NO_RESPONSE", null, true, null));

        assertThat(orderEventTypes(served)).containsExactly("OrderCreated", "OrderAwaitingPayment", "OrderConfirmed",
                "OrderAccepted", "OrderPreparing", "OrderReadyForPickup", "OrderCancelled");
        assertThat(orderEventTypes(rejected)).containsExactly("OrderCreated", "OrderAwaitingPayment",
                "OrderConfirmed", "OrderCancelled");
        assertThat(orderEventTypes(noResponse)).containsExactly("OrderCreated", "OrderAwaitingPayment",
                "OrderConfirmed", "OrderCancelled");

        List<String> orders = Stream.of(served, rejected, noResponse).map(UUID::toString).toList();
        List<OutboxRow> rows = outboxRows().stream().filter(row -> orders.contains(row.aggregateId())).toList();
        assertThat(rows).hasSize(15).extracting(OutboxRow::eventType).containsAll(PHASE_8_EVENTS)
                .contains("OrderConfirmed", "OrderCancelled");
        rows.forEach(this::assertAvroIsTheJsonPayload);

        // what consumers read back from the Avro bytes
        JsonNode confirmed = decoded(rows, served, "OrderConfirmed");
        assertThat(confirmed.get("subtotal").decimalValue()).isEqualByComparingTo("329.00");
        assertThat(confirmed.get("note").asString()).isEqualTo("No onions, extra raita");
        assertThat(decoded(rows, rejected, "OrderConfirmed").get("note").isNull()).isTrue();
        JsonNode accepted = decoded(rows, served, "OrderAccepted");
        assertThat(accepted.get("readyInMinutes").asInt()).isEqualTo(20);
        assertThat(Instant.parse(accepted.get("acceptedAt").asString()))
                .isEqualTo(Instant.parse("2026-11-11T12:00:00.123456Z"));
        assertThat(Instant.parse(accepted.get("readyBy").asString()))
                .isEqualTo(Instant.parse("2026-11-11T12:20:00.123456Z"));
        assertThat(decoded(rows, served, "OrderReadyForPickup").get("orderType").asString()).isEqualTo("RESTAURANT");
        JsonNode adminCancelled = decoded(rows, served, "OrderCancelled");
        assertThat(adminCancelled.get("previousStatus").asString()).isEqualTo("READY_FOR_PICKUP");
        assertThat(adminCancelled.get("reason").asString()).isEqualTo("ADMIN_CANCELLED");
        JsonNode storeRejected = decoded(rows, rejected, "OrderCancelled");
        assertThat(storeRejected.get("reason").asString()).isEqualTo("STORE_REJECTED");
        assertThat(storeRejected.get("cancelledBy").asString()).isEqualTo("STORE");
        assertThat(storeRejected.get("note").asString()).isEqualTo("OUT_OF_STOCK: Toor dal is over");
        JsonNode autoRejected = decoded(rows, noResponse, "OrderCancelled");
        assertThat(autoRejected.get("reason").asString()).isEqualTo("STORE_NO_RESPONSE");
        assertThat(autoRejected.get("cancelledBy").asString()).isEqualTo("SYSTEM");
        assertThat(autoRejected.get("actorUserId").isNull()).isTrue();
    }

    /**
     * Phase 9 (grocery-event-schemas 1.2.0): the three delivery order events, and OrderConfirmed v3 with its optional
     * {@code storeLocation} — a record when the store has coordinates, null when it has none.
     */
    @Test
    void theDeliveryEventsAndOrderConfirmedV3ArePublishedAsAvro() throws Exception {
        // restaurant: picked up -> delivered
        UUID delivered = outForDeliveryOrder(UUID.randomUUID());
        UUID partner = partnerOf(delivered);
        deliveryListener.on(deliveryCompleted(newEventId(), delivered, partner,
                Instant.parse("2026-11-11T13:00:00.123456789Z")));
        // restaurant: picked up -> failed, with a note
        UUID failed = outForDeliveryOrder(UUID.randomUUID());
        deliveryListener.on(deliveryFailed(newEventId(), failed, partnerOf(failed), "OTHER", "Gate locked",
                Instant.now()));
        // grocery: picked up -> failed without a note
        UUID groceryFailed = confirmedGroceryOrder(UUID.randomUUID());
        UUID vendorId = storeIdOf(groceryFailed);
        vendorListener.on(storeAccepted(VENDOR, newEventId(), groceryFailed, vendorId, 15, Instant.now()));
        vendorListener.on(storeReady(VENDOR, newEventId(), groceryFailed, vendorId, Instant.now()));
        UUID groceryPartner = UUID.randomUUID();
        deliveryListener.on(deliveryPickedUp(newEventId(), groceryFailed, groceryPartner, Instant.now()));
        deliveryListener.on(deliveryFailed(newEventId(), groceryFailed, groceryPartner, "CUSTOMER_REFUSED", null,
                Instant.now()));
        // a store without coordinates: OrderConfirmed without storeLocation
        UUID unlocatedCustomer = UUID.randomUUID();
        defaultAddress(unlocatedCustomer);
        CART.carts.put(unlocatedCustomer, restaurantCart(unlocatedCustomer).at(null, null, null));
        UUID unlocated = id(body(place(unlocatedCustomer, newKey(), RESTAURANT_TOTAL)
                .andExpect(status().isCreated())));
        paymentListener.on(paymentCaptured(newEventId(), unlocated, false));

        assertThat(orderEventTypes(delivered)).endsWith("OrderReadyForPickup", "OrderOutForDelivery",
                "OrderDelivered");
        assertThat(orderEventTypes(failed)).endsWith("OrderOutForDelivery", "OrderDeliveryFailed");
        assertThat(orderEventTypes(groceryFailed)).containsExactly("OrderCreated", "OrderAwaitingPayment",
                "OrderConfirmed", "OrderAccepted", "OrderReadyForPickup", "OrderOutForDelivery",
                "OrderDeliveryFailed");

        List<String> orders = Stream.of(delivered, failed, groceryFailed, unlocated).map(UUID::toString).toList();
        List<OutboxRow> rows = outboxRows().stream().filter(row -> orders.contains(row.aggregateId())).toList();
        assertThat(rows).extracting(OutboxRow::eventType).containsAll(PHASE_9_EVENTS).contains("OrderConfirmed");
        rows.forEach(this::assertAvroIsTheJsonPayload);

        // what consumers read back from the Avro bytes
        JsonNode location = decoded(rows, delivered, "OrderConfirmed").get("storeLocation");
        assertThat(location.get("name").asString()).isEqualTo("Spice Route");
        assertThat(location.get("city").asString()).isEqualTo(StubCart.STORE_CITY);
        assertThat(location.get("latitude").asDouble()).isEqualTo(StubCart.STORE_LATITUDE);
        assertThat(location.get("longitude").asDouble()).isEqualTo(StubCart.STORE_LONGITUDE);
        assertThat(decoded(rows, unlocated, "OrderConfirmed").get("storeLocation").isNull()).isTrue();
        JsonNode outForDelivery = decoded(rows, delivered, "OrderOutForDelivery");
        assertThat(outForDelivery.get("partnerId").asString()).isEqualTo(partner.toString());
        assertThat(outForDelivery.get("orderType").asString()).isEqualTo("RESTAURANT");
        JsonNode done = decoded(rows, delivered, "OrderDelivered");
        assertThat(Instant.parse(done.get("deliveredAt").asString()))
                .isEqualTo(Instant.parse("2026-11-11T13:00:00.123456Z"));
        JsonNode withNote = decoded(rows, failed, "OrderDeliveryFailed");
        assertThat(withNote.get("reason").asString()).isEqualTo("OTHER");
        assertThat(withNote.get("note").asString()).isEqualTo("Gate locked");
        JsonNode withoutNote = decoded(rows, groceryFailed, "OrderDeliveryFailed");
        assertThat(withoutNote.get("reason").asString()).isEqualTo("CUSTOMER_REFUSED");
        assertThat(withoutNote.get("note").isNull()).isTrue();
        assertThat(withoutNote.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(withoutNote.get("partnerId").asString()).isEqualTo(groceryPartner.toString());
    }

    /**
     * Phase 10 (grocery-event-schemas 1.3.0): OrderStuckDetected from the real watchdog — for a grocery order stuck in
     * CREATED and a restaurant order stuck in READY_FOR_PICKUP — matches its schema field for field (uuid strings,
     * timestamp-micros) and decodes to the JSON payload.
     */
    @Test
    void orderStuckDetectedIsPublishedAsAvro() throws Exception {
        Instant createdAt = Instant.parse("2026-11-11T09:00:00.123456Z");
        UUID stockCheck = newGroceryOrder(UUID.randomUUID());
        movedBack(stockCheck, "created_at", "CREATED", createdAt);
        UUID pickup = readyOrder(UUID.randomUUID());
        movedBack(pickup, "ready_at", "READY_FOR_PICKUP", Instant.now().minusSeconds(3600));

        watchdog.run();

        List<String> orders = Stream.of(stockCheck, pickup).map(UUID::toString).toList();
        List<OutboxRow> rows = outboxRows().stream()
                .filter(row -> orders.contains(row.aggregateId()) && row.eventType().equals("OrderStuckDetected"))
                .toList();
        assertThat(rows).hasSize(2);
        rows.forEach(this::assertAvroIsTheJsonPayload);

        JsonNode created = decoded(rows, stockCheck, "OrderStuckDetected");
        assertThat(created.get("orderId").asString()).isEqualTo(stockCheck.toString());
        assertThat(created.get("orderType").asString()).isEqualTo("GROCERY");
        assertThat(created.get("status").asString()).isEqualTo("CREATED");
        assertThat(created.get("reason").asString()).isEqualTo("STOCK_CHECK_OVERDUE");
        assertThat(Instant.parse(created.get("since").asString())).isEqualTo(createdAt);
        assertThat(Instant.parse(created.get("detectedAt").asString())).isAfter(createdAt);
        JsonNode ready = decoded(rows, pickup, "OrderStuckDetected");
        assertThat(ready.get("orderType").asString()).isEqualTo("RESTAURANT");
        assertThat(ready.get("status").asString()).isEqualTo("READY_FOR_PICKUP");
        assertThat(ready.get("reason").asString()).isEqualTo("PICKUP_OVERDUE");
        assertThat(ready.get("customerId").asString()).isNotBlank();
        assertThat(ready.get("storeId").asString()).isEqualTo(storeIdOf(pickup).toString());
    }

    /**
     * Phase 12 (grocery-event-schemas 1.5.0): OrderCreated / OrderConfirmed with a coupon (and without one: the three
     * fields null; 1.7.0 adds the coupon's name and funding), and the four return events from the real flows —
     * request, store approval, store rejection, escalation — match their schemas field for field and decode to the
     * JSON payload.
     */
    @Test
    void theCouponFieldsAndTheReturnEventsArePublishedAsAvro() throws Exception {
        // a grocery order with a 20.00 coupon and free delivery: 245.00 - 20.00 - 25.00 = 200.00, delivered
        UUID couponCustomer = UUID.randomUUID();
        UUID withCoupon = deliver(confirmedGroceryOrderWithCoupon(couponCustomer, "20.00", true));
        List<UUID> items = itemIds(couponCustomer, withCoupon);
        // returned (1 rice): approved by the vendor with restock
        JsonNode requested = body(requestReturn(couponCustomer, withCoupon, Map.of(items.getFirst(), 1), "DAMAGED",
                "Torn bag").andExpect(status().isCreated()));
        mvc.perform(post("/api/store/returns/{id}/approve", requested.get("id").asString())
                        .with(storeUser(UUID.randomUUID(), "VENDOR", storeIdOf(withCoupon)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"restock\":true}"))
                .andExpect(status().isOk());
        // a restaurant order without a coupon: one return rejected, a second one escalated
        UUID restaurantCustomer = UUID.randomUUID();
        UUID restaurant = deliveredOrder(restaurantCustomer);
        UUID biryani = itemIds(restaurantCustomer, restaurant).getFirst();
        JsonNode rejected = body(requestReturn(restaurantCustomer, restaurant, Map.of(biryani, 1), "QUALITY", null)
                .andExpect(status().isCreated()));
        mvc.perform(post("/api/store/returns/{id}/reject", rejected.get("id").asString())
                        .with(storeUser(UUID.randomUUID(), "RESTAURANT_OWNER", storeIdOf(restaurant)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Looked fine at pickup\"}"))
                .andExpect(status().isOk());
        JsonNode escalated = body(requestReturn(restaurantCustomer, restaurant, Map.of(biryani, 1), "OTHER", null)
                .andExpect(status().isCreated()));
        jdbc().sql("UPDATE order_return SET decide_by = ? WHERE id = UUID_TO_BIN(?)")
                .param(Instant.now().minusSeconds(60).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime())
                .param(escalated.get("id").asString()).update();
        escalation.escalateDue();

        assertThat(orderEventTypes(withCoupon)).contains("OrderCreated", "OrderConfirmed", "OrderDelivered")
                .endsWith("ReturnRequested", "ReturnApproved");
        assertThat(orderEventTypes(restaurant))
                .endsWith("OrderDelivered", "ReturnRequested", "ReturnRejected", "ReturnRequested", "ReturnEscalated");

        List<String> orders = Stream.of(withCoupon, restaurant).map(UUID::toString).toList();
        List<OutboxRow> rows = outboxRows().stream().filter(row -> orders.contains(row.aggregateId())).toList();
        assertThat(rows).extracting(OutboxRow::eventType).containsAll(PHASE_12_EVENTS);
        rows.forEach(this::assertAvroIsTheJsonPayload);

        // what consumers read back from the Avro bytes
        JsonNode created = decoded(rows, withCoupon, "OrderCreated");
        assertThat(created.get("couponCode").asString()).startsWith("C");
        assertThat(created.get("discount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(created.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(created.get("total").decimalValue()).isEqualByComparingTo("200.00");
        JsonNode confirmed = decoded(rows, withCoupon, "OrderConfirmed");
        assertThat(confirmed.get("couponCode").asString()).isEqualTo(created.get("couponCode").asString());
        assertThat(confirmed.get("discount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(confirmed.get("deliveryFeeDiscount").decimalValue()).isEqualByComparingTo("25.00");
        // 1.7.0: the coupon's name and funding
        assertThat(created.get("couponName").asString()).isEqualTo("Offer " + created.get("couponCode").asString());
        assertThat(created.get("couponFundedBy").asString()).isEqualTo("PLATFORM");
        assertThat(confirmed.get("couponFundedBy").asString()).isEqualTo("PLATFORM");
        assertThat(decoded(rows, restaurant, "OrderCreated").get("couponCode").isNull()).isTrue();
        assertThat(decoded(rows, restaurant, "OrderConfirmed").get("discount").isNull()).isTrue();
        assertThat(decoded(rows, restaurant, "OrderConfirmed").get("couponFundedBy").isNull()).isTrue();

        // 1 of 2 rice (100.00) with 20.00 off 220.00: 100 x 1/2 x 200/220 = 45.45
        JsonNode request = decoded(rows, withCoupon, "ReturnRequested");
        assertThat(request.get("returnNumber").asString()).matches("RT-[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(request.get("reason").asString()).isEqualTo("DAMAGED");
        assertThat(request.get("note").asString()).isEqualTo("Torn bag");
        assertThat(request.get("refundAmount").decimalValue()).isEqualByComparingTo("45.45");
        assertThat(request.get("items").get(0).get("quantity").asInt()).isEqualTo(1);
        assertThat(request.get("items").get(0).get("name").asString()).isEqualTo("Basmati Rice");
        JsonNode approved = decoded(rows, withCoupon, "ReturnApproved");
        assertThat(approved.get("restock").asBoolean()).isTrue();
        assertThat(approved.get("decidedByRole").asString()).isEqualTo("STORE");
        assertThat(approved.get("refundAmount").decimalValue()).isEqualByComparingTo("45.45");
        assertThat(approved.get("orderType").asString()).isEqualTo("GROCERY");
        JsonNode rejection = decoded(rows, restaurant, "ReturnRejected");
        assertThat(rejection.get("comment").asString()).isEqualTo("Looked fine at pickup");
        assertThat(rejection.get("decidedByRole").asString()).isEqualTo("STORE");
        JsonNode escalatedEvent = decoded(rows, restaurant, "ReturnEscalated");
        assertThat(escalatedEvent.get("returnId").asString()).isEqualTo(escalated.get("id").asString());
        assertThat(escalatedEvent.get("refundAmount").decimalValue()).isEqualByComparingTo("249.00");
        assertThat(Instant.parse(escalatedEvent.get("escalatedAt").asString()))
                .isAfter(Instant.parse(escalatedEvent.get("requestedAt").asString()));
    }

    /** The one {@code eventType} row of {@code orderId}, decoded from {@code payload_avro} as a consumer would. */
    private JsonNode decoded(List<OutboxRow> rows, UUID orderId, String eventType) {
        OutboxRow row = rows.stream()
                .filter(r -> r.aggregateId().equals(orderId.toString()) && r.eventType().equals(eventType))
                .findFirst().orElseThrow();
        return converter.readExact(deserializer.deserialize(row.topic(), row.payloadAvro()));
    }

    private List<OutboxRow> outboxRows() {
        return jdbc().sql("SELECT id, topic, aggregate_id, event_type, event_version, payload, payload_avro "
                        + "FROM outbox_event ORDER BY occurred_at, id")
                .query((rs, n) -> new OutboxRow(rs.getString("id"), rs.getString("topic"),
                        rs.getString("aggregate_id"), rs.getString("event_type"), rs.getInt("event_version"),
                        rs.getString("payload"), rs.getBytes("payload_avro")))
                .list();
    }

    private void assertAvroIsTheJsonPayload(OutboxRow row) {
        String what = row.eventType() + " " + row.id();
        assertThat(row.topic()).isEqualTo("order.events");
        assertThat(row.eventType()).isIn(ORDER_EVENTS);
        assertThat(row.eventVersion()).isEqualTo(1);
        byte[] avro = row.payloadAvro();
        assertThat(avro).as("payload_avro of %s", what).isNotNull();
        assertThat(avro.length).as("length of %s", what).isGreaterThan(5);
        assertThat(avro[0]).as("magic byte of %s", what).isZero();

        // the schema id in the wire format is the one registered under <topic>-<record full name>
        int schemaId = ByteBuffer.wrap(avro, 1, 4).getInt();
        String recordName = "com.grocery.events.order." + row.eventType();
        SchemaMetadata registered;
        try {
            registered = registry.getLatestSchemaMetadata("order.events-" + recordName);
        } catch (Exception e) {
            throw new AssertionError("No subject order.events-" + recordName + " in the registry", e);
        }
        assertThat(registered.getId()).as("schema id of %s", what).isEqualTo(schemaId);
        assertThat(new Schema.Parser().parse(registered.getSchema()).getFullName()).isEqualTo(recordName);

        String decoded = deserializer.deserialize(row.topic(), avro);
        assertThat(canonical(converter.readExact(decoded)))
                .as("decoded payload_avro of %s:%n%s%nvs payload:%n%s", what, decoded, row.payload())
                .isEqualTo(canonical(converter.readExact(row.payload())));
    }

    /**
     * Comparable form of a JSON tree: objects as sorted maps (MySQL's JSON column reorders keys), numbers by value
     * (MySQL and Avro decimals may differ in trailing zeros), ISO instants truncated to microseconds (Avro's
     * timestamp-micros), everything else as is.
     */
    private static Object canonical(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> fields = new TreeMap<>();
            node.properties().forEach(field -> fields.put(field.getKey(), canonical(field.getValue())));
            return fields;
        }
        if (node.isArray()) {
            return node.valueStream().map(AvroOutboxRoundTripIntegrationTest::canonical).toList();
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String text = node.asString();
        if (ISO_INSTANT.matcher(text).matches()) {
            try {
                return Instant.parse(text).truncatedTo(ChronoUnit.MICROS);
            } catch (DateTimeParseException e) {
                return text;
            }
        }
        return text;
    }
}
