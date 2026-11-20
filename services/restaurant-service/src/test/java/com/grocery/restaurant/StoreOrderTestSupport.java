package com.grocery.restaurant;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.restaurant.config.FulfillmentProperties;
import com.grocery.restaurant.event.OrderEventsListener;
import com.grocery.restaurant.repository.RestaurantRepository;
import com.grocery.restaurant.service.AutoRejectPoller;
import com.grocery.restaurant.service.RestaurantOnboardingService;
import com.grocery.restaurant.service.StoreOrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Helpers for the store order tests: restaurants known to this service, order.events records handed to the Kafka
 * listener the way Debezium delivers them (routing data in the headers), the outbox and the database. The database is
 * shared by all test classes: tests use their own random restaurants and orders, never fixed ids.
 */
public abstract class StoreOrderTestSupport extends AbstractIntegrationTest {

    protected static final String GROCERY = "GROCERY";
    protected static final String RESTAURANT = "RESTAURANT";
    protected static final String AGGREGATE = "RestaurantOrder";

    @Autowired
    protected OrderEventsListener orderListener;
    @Autowired
    protected StoreOrderService storeOrders;
    @Autowired
    protected FulfillmentProperties fulfillment;
    @Autowired
    protected RestaurantOnboardingService onboarding;
    @Autowired
    protected RestaurantRepository restaurants;
    @Autowired
    protected DataSource dataSource;

    /** A restaurant known to this service and its owner (the user with the restaurant_id claim). */
    protected record Store(UUID restaurantId, UUID ownerUserId) {
    }

    /** One order line: {@code unitPrice} as text, e.g. "50.00". */
    protected record Line(UUID itemId, String name, int quantity, String unitPrice) {

        BigDecimal lineTotal() {
            return new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity));
        }
    }

    protected static Line line(UUID itemId, String name, int quantity, String unitPrice) {
        return new Line(itemId, name, quantity, unitPrice);
    }

    /** Veg Biryani 2 × 50.00 + Dal Makhani 1 × 120.00: subtotal 220.00, total 245.00, 3 units. */
    protected static List<Line> defaultLines() {
        return List.of(line(Ids.newId(), "Veg Biryani", 2, "50.00"), line(Ids.newId(), "Dal Makhani", 1, "120.00"));
    }

    protected Store newStore() {
        UUID owner = UUID.randomUUID();
        onboarding.onRestaurantOwnerRegistered(newEventId(), owner, "Spice Route", "owner-" + owner + "@example.com",
                null);
        return new Store(restaurants.findByOwnerUserId(owner).orElseThrow().getId(), owner);
    }

    protected static JwtRequestPostProcessor owner(Store store) {
        return as(store.ownerUserId(), store.restaurantId(), "RESTAURANT_OWNER");
    }

    /** The same caller as {@link #owner}, for calling services directly. */
    protected static CurrentUser ownerUser(Store store) {
        return new CurrentUser(store.ownerUserId(), "owner@example.com", "Owner", Set.of("RESTAURANT_OWNER"), null,
                store.restaurantId());
    }

    protected static String newEventId() {
        return Ids.newId().toString();
    }

    protected static String orderNumber(UUID orderId) {
        return "GA-" + orderId.toString().replace("-", "").substring(24).toUpperCase();
    }

    // ---- order.events -------------------------------------------------------------------------------------

    /** A record as Debezium's outbox router delivers it: routing data in the headers, the payload as the value. */
    protected static ConsumerRecord<String, String> record(String topic, String aggregateType, String eventType,
                                                           String eventId, String key, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, key, payload);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", aggregateType.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    protected ConsumerRecord<String, String> orderEvent(String eventType, String eventId, UUID orderId,
                                                        Map<String, Object> payload) {
        return record("order.events", "Order", eventType, eventId, orderId.toString(), json.writeValueAsString(payload));
    }

    /**
     * A full Phase 8 OrderConfirmed (contract §4: with {@code subtotal} and {@code note}): total = subtotal + 25.00
     * delivery fee; the address of "Ravi Kumar" in Indiranagar (line 2), Bengaluru.
     */
    protected Map<String, Object> confirmedPayload(UUID orderId, UUID storeId, String orderType, List<Line> lines) {
        BigDecimal subtotal = lines.stream().map(Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("label", "Home");
        address.put("recipientName", "Ravi Kumar");
        address.put("phone", "+919876543210");
        address.put("line1", "42 HAL 2nd Stage");
        address.put("line2", "Indiranagar");
        address.put("landmark", null);
        address.put("city", "Bengaluru");
        address.put("state", "Karnataka");
        address.put("postalCode", "560038");
        address.put("latitude", 12.9719);
        address.put("longitude", 77.6412);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", orderNumber(orderId));
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", storeId.toString());
        payload.put("items", lines.stream().map(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId", line.itemId().toString());
            item.put("type", GROCERY.equals(orderType) ? "PRODUCT" : "MENU_ITEM");
            item.put("name", line.name());
            item.put("quantity", line.quantity());
            item.put("unitPrice", new BigDecimal(line.unitPrice()));
            item.put("lineTotal", line.lineTotal().setScale(2));
            return item;
        }).toList());
        payload.put("total", subtotal.add(new BigDecimal("25.00")));
        payload.put("deliveryAddress", address);
        payload.put("confirmedAt", "2026-11-11T10:05:00.123456Z");
        payload.put("subtotal", subtotal);
        payload.put("note", "Ring the bell twice");
        return payload;
    }

    protected ConsumerRecord<String, String> orderConfirmed(String eventId, Map<String, Object> payload) {
        return orderEvent("OrderConfirmed", eventId, UUID.fromString((String) payload.get("orderId")), payload);
    }

    /** A paid RESTAURANT order of {@code store} delivered to the listener; returns the order id. */
    protected UUID confirm(Store store, List<Line> lines) {
        UUID orderId = Ids.newId();
        orderListener.on(orderConfirmed(newEventId(), confirmedPayload(orderId, store.restaurantId(), RESTAURANT,
                lines)));
        return orderId;
    }

    protected UUID confirm(Store store) {
        return confirm(store, defaultLines());
    }

    protected Map<String, Object> cancelledPayload(UUID orderId, UUID storeId, String orderType, String previousStatus,
                                                   String reason, String cancelledBy, String cancelledAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", orderNumber(orderId));
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", storeId.toString());
        payload.put("previousStatus", previousStatus);
        payload.put("reason", reason);
        payload.put("cancelledBy", cancelledBy);
        payload.put("actorUserId", null);
        payload.put("note", null);
        payload.put("cancelledAt", cancelledAt);
        return payload;
    }

    /** The customer cancelled the order (OrderCancelled CUSTOMER_CANCELLED by CUSTOMER at {@code cancelledAt}). */
    protected void cancel(String eventId, Store store, UUID orderId, String cancelledAt) {
        orderListener.on(orderEvent("OrderCancelled", eventId, orderId, cancelledPayload(orderId, store.restaurantId(),
                RESTAURANT, "CONFIRMED", "CUSTOMER_CANCELLED", "CUSTOMER", cancelledAt)));
    }

    /** Phase 9: OrderOutForDelivery as order-service publishes it ({@code pickedUpAt} may be null). */
    protected Map<String, Object> outForDeliveryPayload(UUID orderId, UUID storeId, String orderType,
                                                        String pickedUpAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", orderNumber(orderId));
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", storeId.toString());
        payload.put("partnerId", UUID.randomUUID().toString());
        payload.put("pickedUpAt", pickedUpAt);
        return payload;
    }

    /** The delivery partner picked the RESTAURANT order up (OrderOutForDelivery at {@code pickedUpAt}). */
    protected void pickUp(String eventId, Store store, UUID orderId, String pickedUpAt) {
        orderListener.on(orderEvent("OrderOutForDelivery", eventId, orderId,
                outForDeliveryPayload(orderId, store.restaurantId(), RESTAURANT, pickedUpAt)));
    }

    // ---- pollers ------------------------------------------------------------------------------------------

    /** An auto-reject poller whose clock runs {@code ahead} of the service's clock. */
    protected AutoRejectPoller pollerAhead(Duration ahead) {
        return new AutoRejectPoller(storeOrders, fulfillment, Clock.offset(clock, ahead));
    }

    protected AutoRejectPoller pollerAt(Instant now) {
        return new AutoRejectPoller(storeOrders, fulfillment, Clock.fixed(now, ZoneOffset.UTC));
    }

    // ---- API ----------------------------------------------------------------------------------------------

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- database -----------------------------------------------------------------------------------------

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** The store order's status, or null if there is none. */
    protected String orderStatus(UUID orderId) {
        return jdbc().sql("SELECT status FROM restaurant_order WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).optional().orElse(null);
    }

    protected Map<String, Object> orderRow(UUID orderId) {
        return jdbc().sql("SELECT * FROM restaurant_order WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query().singleRow();
    }

    /** A DATETIME(6) column of the store order as an instant (stored in UTC), or null. */
    protected Instant instantColumn(UUID orderId, String column) {
        String text = jdbc().sql("SELECT DATE_FORMAT(" + column + ", '%Y-%m-%dT%H:%i:%s.%fZ') FROM restaurant_order "
                        + "WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).optional().orElse(null);
        return text == null ? null : Instant.parse(text);
    }

    protected long orderCount(UUID orderId) {
        return jdbc().sql("SELECT COUNT(*) FROM restaurant_order WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(Long.class).single();
    }

    /** The statuses of the order's history, oldest first. */
    protected List<String> history(UUID orderId) {
        return jdbc().sql("SELECT status FROM restaurant_order_status_history WHERE order_id = UUID_TO_BIN(?) "
                        + "ORDER BY seq")
                .param(orderId.toString()).query(String.class).list();
    }

    /** Every outbox event about one store order (aggregate RestaurantOrder), as "eventType", oldest first. */
    protected List<String> storeEventTypes(UUID orderId) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_type = ? AND aggregate_id = ? "
                        + "ORDER BY occurred_at, id")
                .param(AGGREGATE).param(orderId.toString()).query(String.class).list();
    }

    /** The payload of the order's (single) outbox event of one type. */
    protected JsonNode storeEvent(UUID orderId, String eventType) {
        return json.readTree(jdbc().sql("SELECT payload FROM outbox_event WHERE aggregate_type = ? AND aggregate_id = ? "
                        + "AND event_type = ?")
                .param(AGGREGATE).param(orderId.toString()).param(eventType).query(String.class).single());
    }

    /** The routing columns of the order's (single) outbox event of one type. */
    protected Map<String, Object> outboxRow(UUID orderId, String eventType) {
        return jdbc().sql("SELECT topic, aggregate_type, aggregate_id FROM outbox_event WHERE aggregate_id = ? "
                        + "AND event_type = ?")
                .param(orderId.toString()).param(eventType).query().singleRow();
    }

    /** Whether the order-event consumer has marked {@code eventId} processed (0 or 1). */
    protected long processed(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? "
                        + "AND consumer = 'restaurant-service.order-events'")
                .param(eventId).query(Long.class).single();
    }

    /** The field names of a JSON object. */
    protected static List<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
