package com.grocery.order;

import com.grocery.common.id.Ids;
import com.grocery.order.event.DeliveryEventsListener;
import com.grocery.order.event.InventoryEventsListener;
import com.grocery.order.event.PaymentEventsListener;
import com.grocery.order.event.RestaurantEventsListener;
import com.grocery.order.event.VendorEventsListener;
import com.grocery.order.service.OrderNumbers;
import com.grocery.order.service.ReturnEscalationPoller;
import com.grocery.order.service.StuckOrderWatchdog;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MySQL 8.4 in a throw-away container and an in-process stub of cart-service ({@link StubCart}), shared by all
 * test classes (one Spring context). Requests are authenticated with Spring Security's test JWT support (same claims
 * identity issues; token value "token-&lt;userId&gt;", which the stub uses as "me"), so the real security chain and
 * @PreAuthorize rules run. Kafka listeners do not start: inventory and payment events are handed to the listeners
 * directly, as Kafka would, with the routing data in the record headers. Tests use random ids only; every test starts
 * with the cart up, no recorded calls and closed circuit breakers. The stuck-order watchdog is not scheduled: tests run
 * it themselves ({@link #watchdog}) after moving their orders' times back with SQL ({@link #movedBack}).
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "grocery.order.watchdog.enabled=false",
        "grocery.order.returns.escalation-enabled=false",
        // Phase 13 gauges: refreshed once at start; tests refresh them themselves (OrderMetricsIntegrationTest)
        "grocery.order.metrics.refresh-interval=1h",
        // the concurrency tests queue more threads than the pool has connections
        "spring.datasource.hikari.connection-timeout=30000",
        // a row-lock wait ends after 5 s instead of 50 s (the outage test waits for one on purpose)
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 5",
        // Avro outbox payloads (ADR-0011) against an in-memory Schema Registry that registers the schemas itself
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    /** Confluent's in-memory Schema Registry, one scope per service (shared by every client of this JVM). */
    public static final String SCHEMA_REGISTRY_URL = "mock://order-service";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final StubCart CART = StubCart.start();
    /** Phase 12: pricing-service's coupon redemption. */
    static final StubPricing PRICING = StubPricing.start();

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("grocery.order.services.cart", CART::baseUrl);
        registry.add("grocery.order.services.pricing", PRICING::baseUrl);
        registry.add("grocery.order.http.read-timeout", () -> "700ms");
        // small breaker window so a test can open it with a few calls
        registry.add("grocery.order.circuit-breaker.sliding-window-size", () -> "4");
        registry.add("grocery.order.circuit-breaker.minimum-number-of-calls", () -> "4");
        registry.add("grocery.order.circuit-breaker.wait-duration-in-open-state", () -> "60s");
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected InventoryEventsListener inventoryListener;
    @Autowired
    protected PaymentEventsListener paymentListener;
    @Autowired
    protected VendorEventsListener vendorListener;
    @Autowired
    protected RestaurantEventsListener restaurantListener;
    @Autowired
    protected DeliveryEventsListener deliveryListener;
    @Autowired
    protected Resilience4JCircuitBreakerFactory breakers;
    @Autowired
    protected StuckOrderWatchdog watchdog;
    @Autowired
    protected ReturnEscalationPoller escalation;
    /** The real generator; one test makes it return a taken number first. */
    @MockitoSpyBean
    protected OrderNumbers orderNumbers;

    @BeforeEach
    protected void resetStubAndBreakers() {
        CART.reset();
        PRICING.reset();
        breakers.getCircuitBreakerRegistry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    // ---------------------------------------------------------------- callers

    /** A request authenticated as {@code userId} holding {@code roles}; its bearer token is "token-&lt;userId&gt;". */
    protected static JwtRequestPostProcessor as(UUID userId, String... roles) {
        List<String> roleList = Arrays.asList(roles);
        return jwt()
                .jwt(token -> token.tokenValue("token-" + userId)
                        .subject(userId.toString())
                        .claim("email", "user-" + userId.toString().substring(0, 8) + "@example.com")
                        .claim("name", "User " + userId.toString().substring(0, 8))
                        .claim("roles", roleList))
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected static JwtRequestPostProcessor customer(UUID customerId) {
        return as(customerId, "CUSTOMER");
    }

    /** A store user (Phase 12 store endpoints): VENDOR with {@code vendor_id}, RESTAURANT_OWNER with {@code restaurant_id}. */
    protected static JwtRequestPostProcessor storeUser(UUID userId, String role, UUID storeId) {
        String claim = "VENDOR".equals(role) ? "vendor_id" : "restaurant_id";
        return jwt()
                .jwt(token -> token.tokenValue("token-" + userId)
                        .subject(userId.toString())
                        .claim("email", "store-" + userId.toString().substring(0, 8) + "@example.com")
                        .claim("name", "Store " + userId.toString().substring(0, 8))
                        .claim("roles", List.of(role))
                        .claim(claim, storeId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    protected static String newKey() {
        return "test-" + UUID.randomUUID();
    }

    // ---------------------------------------------------------------- cart-service data

    /** A default address ("Home") of the customer. */
    protected static UUID defaultAddress(UUID customerId) {
        return address(customerId, "Home", true);
    }

    protected static UUID address(UUID customerId, String label, boolean isDefault) {
        UUID id = UUID.randomUUID();
        CART.addresses.compute(customerId, (c, list) -> {
            List<StubCart.Address> updated = list == null ? new ArrayList<>() : new ArrayList<>(list);
            updated.add(new StubCart.Address(id, label, "Asha Rao", isDefault));
            return updated;
        });
        return id;
    }

    /**
     * Green Basket: 2 × Basmati Rice at 50.00 (MRP 60.00) + 1 × Toor Dal at 120.00 (MRP 150.00) → subtotal 220.00,
     * MRP total 270.00, savings 50.00, delivery 25.00, total {@code 245.00}.
     */
    protected static StubCart.Cart groceryCart(UUID customerId) {
        StubCart.Cart cart = new StubCart.Cart(UUID.randomUUID(), "GROCERY", "Green Basket", List.of(
                new StubCart.Line(UUID.randomUUID(), "PRODUCT", "Basmati Rice", "1 kg", "VEG", 2,
                        new BigDecimal("50.00"), new BigDecimal("60.00")),
                new StubCart.Line(UUID.randomUUID(), "PRODUCT", "Toor Dal", "500 g", "VEG", 1,
                        new BigDecimal("120.00"), new BigDecimal("150.00"))),
                List.of(), Instant.parse("2026-11-11T10:00:00.123Z"));
        CART.carts.put(customerId, cart);
        return cart;
    }

    protected static final BigDecimal GROCERY_TOTAL = new BigDecimal("245.00");

    /** Spice Route: 1 × Chicken Biryani at 249.00 + 2 × Raita at 40.00 → 329.00 + delivery 30.00 = {@code 359.00}. */
    protected static StubCart.Cart restaurantCart(UUID customerId) {
        StubCart.Cart cart = new StubCart.Cart(UUID.randomUUID(), "RESTAURANT", "Spice Route", List.of(
                new StubCart.Line(UUID.randomUUID(), "MENU_ITEM", "Chicken Biryani", null, "NON_VEG", 1,
                        new BigDecimal("249.00"), null),
                new StubCart.Line(UUID.randomUUID(), "MENU_ITEM", "Raita", null, "VEG", 2,
                        new BigDecimal("40.00"), null)),
                List.of(), Instant.parse("2026-11-11T11:00:00.456Z"));
        CART.carts.put(customerId, cart);
        return cart;
    }

    protected static final BigDecimal RESTAURANT_TOTAL = new BigDecimal("359.00");

    // ---------------------------------------------------------------- requests

    protected ResultActions placeRequest(UUID customerId, String key, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/orders").with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    protected static Map<String, Object> placeBody(UUID addressId, BigDecimal expectedTotal, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (addressId != null) {
            body.put("addressId", addressId.toString());
        }
        body.put("expectedTotal", expectedTotal);
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    protected ResultActions place(UUID customerId, String key, BigDecimal expectedTotal) throws Exception {
        return placeRequest(customerId, key, placeBody(null, expectedTotal, null));
    }

    /** A new grocery order of a new customer (with a default address): status CREATED. */
    protected UUID newGroceryOrder(UUID customerId) throws Exception {
        defaultAddress(customerId);
        groceryCart(customerId);
        return id(body(place(customerId, newKey(), GROCERY_TOTAL).andExpect(status().isCreated())));
    }

    /** A new restaurant order: status AWAITING_PAYMENT at once. */
    protected UUID newRestaurantOrder(UUID customerId) throws Exception {
        defaultAddress(customerId);
        restaurantCart(customerId);
        return id(body(place(customerId, newKey(), RESTAURANT_TOTAL).andExpect(status().isCreated())));
    }

    /** A grocery order whose stock was reserved: AWAITING_PAYMENT. */
    protected UUID awaitingPaymentGroceryOrder(UUID customerId) throws Exception {
        UUID orderId = newGroceryOrder(customerId);
        inventoryListener.on(stockReserved(newEventId(), orderId));
        return orderId;
    }

    /** A paid restaurant order: CONFIRMED. */
    protected UUID confirmedOrder(UUID customerId) throws Exception {
        UUID orderId = newRestaurantOrder(customerId);
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));
        return orderId;
    }

    /** A paid grocery order: CONFIRMED. */
    protected UUID confirmedGroceryOrder(UUID customerId) throws Exception {
        UUID orderId = awaitingPaymentGroceryOrder(customerId);
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));
        return orderId;
    }

    /** A paid restaurant order the restaurant accepted (20 min): ACCEPTED. */
    protected UUID acceptedOrder(UUID customerId) throws Exception {
        UUID orderId = confirmedOrder(customerId);
        onStoreEvent(storeAccepted(RESTAURANT, newEventId(), orderId, storeIdOf(orderId), 20, Instant.now()));
        return orderId;
    }

    /** A restaurant order being prepared: PREPARING. */
    protected UUID preparingOrder(UUID customerId) throws Exception {
        UUID orderId = acceptedOrder(customerId);
        onStoreEvent(storePreparing(RESTAURANT, newEventId(), orderId, storeIdOf(orderId), Instant.now()));
        return orderId;
    }

    /** A restaurant order ready for pickup (through PREPARING): READY_FOR_PICKUP. */
    protected UUID readyOrder(UUID customerId) throws Exception {
        UUID orderId = preparingOrder(customerId);
        onStoreEvent(storeReady(RESTAURANT, newEventId(), orderId, storeIdOf(orderId), Instant.now()));
        return orderId;
    }

    protected JsonNode order(UUID customerId, UUID orderId) throws Exception {
        return body(mvc.perform(get("/api/orders/{id}", orderId).with(customer(customerId)))
                .andExpect(status().isOk()));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected static UUID id(JsonNode order) {
        return UUID.fromString(order.get("id").asString());
    }

    // ---------------------------------------------------------------- events (as Debezium's outbox router delivers them)

    protected static String newEventId() {
        return Ids.newId().toString();
    }

    protected static ConsumerRecord<String, String> record(String topic, String aggregateType, String eventType,
                                                           String eventId, String key, String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, key, payload);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", aggregateType.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** An inventory.events record (aggregate Reservation, key orderId). */
    protected ConsumerRecord<String, String> inventoryEvent(String eventType, String eventId, UUID orderId,
                                                            Map<String, Object> payload) {
        return record("inventory.events", "Reservation", eventType, eventId, orderId.toString(),
                json.writeValueAsString(payload));
    }

    /** A payment.events record (aggregate Payment, key orderId). */
    protected ConsumerRecord<String, String> paymentEvent(String eventType, String eventId, UUID orderId,
                                                          Map<String, Object> payload) {
        return record("payment.events", "Payment", eventType, eventId, orderId.toString(),
                json.writeValueAsString(payload));
    }

    protected ConsumerRecord<String, String> stockReserved(String eventId, UUID orderId) {
        return inventoryEvent("StockReserved", eventId, orderId, Map.of(
                "orderId", orderId.toString(), "reservationId", UUID.randomUUID().toString(),
                "items", List.of(Map.of("productId", UUID.randomUUID().toString(), "quantity", 2)),
                "expiresAt", "2026-11-11T10:20:00Z"));
    }

    /** {@code unavailable}: productId → [requested, available]. */
    protected ConsumerRecord<String, String> stockReservationFailed(String eventId, UUID orderId,
                                                                    Map<UUID, int[]> unavailable) {
        return inventoryEvent("StockReservationFailed", eventId, orderId, Map.of(
                "orderId", orderId.toString(),
                "unavailableItems", unavailable.entrySet().stream()
                        .map(e -> Map.of("productId", e.getKey().toString(), "requested", e.getValue()[0],
                                "available", e.getValue()[1]))
                        .toList()));
    }

    protected ConsumerRecord<String, String> stockReservationExpired(String eventId, UUID orderId) {
        return inventoryEvent("StockReservationExpired", eventId, orderId, Map.of(
                "orderId", orderId.toString(), "reservationId", UUID.randomUUID().toString()));
    }

    protected ConsumerRecord<String, String> paymentInitiated(String eventId, UUID orderId, UUID paymentId) {
        return paymentEvent("PaymentInitiated", eventId, orderId, Map.of(
                "orderId", orderId.toString(), "paymentId", paymentId.toString(), "razorpayOrderId", "order_test",
                "amount", 245.00, "currency", "INR", "expiresAt", "2026-11-11T10:15:00Z"));
    }

    protected ConsumerRecord<String, String> paymentCaptured(String eventId, UUID orderId, boolean late) {
        return paymentCaptured(eventId, orderId, UUID.randomUUID(), "2026-11-11T10:05:00.250Z", late);
    }

    protected ConsumerRecord<String, String> paymentCaptured(String eventId, UUID orderId, UUID paymentId,
                                                             String capturedAt, boolean late) {
        return paymentEvent("PaymentCaptured", eventId, orderId, Map.of(
                "orderId", orderId.toString(), "paymentId", paymentId.toString(), "razorpayPaymentId", "pay_test",
                "amount", 245.00, "method", "upi", "capturedAt", capturedAt, "late", late));
    }

    protected ConsumerRecord<String, String> paymentFailed(String eventId, UUID orderId, String code,
                                                           String description) {
        return paymentEvent("PaymentFailed", eventId, orderId, Map.of(
                "orderId", orderId.toString(), "paymentId", UUID.randomUUID().toString(),
                "razorpayPaymentId", "pay_failed", "errorCode", code, "errorDescription", description,
                "retryable", true));
    }

    protected ConsumerRecord<String, String> paymentExpired(String eventId, UUID orderId) {
        return paymentEvent("PaymentExpired", eventId, orderId, Map.of(
                "orderId", orderId.toString(), "paymentId", UUID.randomUUID().toString()));
    }

    protected ConsumerRecord<String, String> refundCompleted(String eventId, UUID orderId, String amount,
                                                             String totalRefunded, boolean fullyRefunded) {
        return paymentEvent("RefundCompleted", eventId, orderId, Map.of(
                "orderId", orderId.toString(), "paymentId", UUID.randomUUID().toString(),
                "refundId", UUID.randomUUID().toString(), "amount", new BigDecimal(amount),
                "reason", "ORDER_CANCELLED", "totalRefunded", new BigDecimal(totalRefunded),
                "fullyRefunded", fullyRefunded));
    }

    // ---------------------------------------------------------------- store events (vendor.events / restaurant.events)

    protected static final String VENDOR = "Vendor";
    protected static final String RESTAURANT = "Restaurant";

    /** Hands a store event to the listener of its topic, as Kafka would. */
    protected void onStoreEvent(ConsumerRecord<String, String> record) {
        if ("vendor.events".equals(record.topic())) {
            vendorListener.on(record);
        } else {
            restaurantListener.on(record);
        }
    }

    /**
     * A {@code <store><eventType>} record (e.g. {@code VendorOrderAccepted}) on the store's topic, aggregate
     * {@code VendorOrder} / {@code RestaurantOrder}, key orderId; the payload has {@code orderId}, {@code orderNumber},
     * {@code vendorId} / {@code restaurantId} = {@code storeId} and {@code fields}.
     */
    protected ConsumerRecord<String, String> storeEvent(String store, String eventType, String eventId, UUID orderId,
                                                        UUID storeId, Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", "GA-STORE000");
        payload.put(VENDOR.equals(store) ? "vendorId" : "restaurantId", storeId.toString());
        payload.putAll(fields);
        return record(VENDOR.equals(store) ? "vendor.events" : "restaurant.events", store + "Order",
                store + eventType, eventId, orderId.toString(), json.writeValueAsString(payload));
    }

    protected ConsumerRecord<String, String> storeAccepted(String store, String eventId, UUID orderId, UUID storeId,
                                                           int readyInMinutes, Instant acceptedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("readyInMinutes", readyInMinutes);
        fields.put("readyBy", acceptedAt.plusSeconds(60L * readyInMinutes).toString());
        fields.put("acceptedAt", acceptedAt.toString());
        fields.put("actorUserId", UUID.randomUUID().toString());
        return storeEvent(store, "OrderAccepted", eventId, orderId, storeId, fields);
    }

    protected ConsumerRecord<String, String> storePreparing(String store, String eventId, UUID orderId, UUID storeId,
                                                            Instant preparingAt) {
        return storeEvent(store, "OrderPreparing", eventId, orderId, storeId, Map.of(
                "preparingAt", preparingAt.toString(), "actorUserId", UUID.randomUUID().toString()));
    }

    protected ConsumerRecord<String, String> storeReady(String store, String eventId, UUID orderId, UUID storeId,
                                                        Instant readyAt) {
        return storeEvent(store, "OrderReadyForPickup", eventId, orderId, storeId, Map.of(
                "readyAt", readyAt.toString(), "actorUserId", UUID.randomUUID().toString()));
    }

    /** {@code note} and {@code actorUserId} may be null (written as JSON null). */
    protected ConsumerRecord<String, String> storeRejected(String store, String eventId, UUID orderId, UUID storeId,
                                                           String reason, String note, boolean auto,
                                                           UUID actorUserId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reason", reason);
        fields.put("note", note);
        fields.put("auto", auto);
        fields.put("rejectedAt", Instant.now().toString());
        fields.put("actorUserId", actorUserId == null ? null : actorUserId.toString());
        return storeEvent(store, "OrderRejected", eventId, orderId, storeId, fields);
    }

    // ---------------------------------------------------------------- delivery events (delivery.events, Phase 9)

    /**
     * A {@code delivery.events} record of {@code eventType} (aggregate Delivery, key orderId); the payload has
     * {@code deliveryId}, {@code orderId}, {@code orderNumber} and {@code fields}.
     */
    protected ConsumerRecord<String, String> deliveryEvent(String eventType, String eventId, UUID orderId,
                                                           Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveryId", UUID.randomUUID().toString());
        payload.put("orderId", orderId == null ? null : orderId.toString());
        payload.put("orderNumber", "GA-DELIVER0");
        payload.putAll(fields);
        return record("delivery.events", "Delivery", eventType, eventId, String.valueOf(orderId),
                json.writeValueAsString(payload));
    }

    /** {@code partnerName} may be null (written as JSON null). */
    protected ConsumerRecord<String, String> partnerAssigned(String eventId, UUID orderId, UUID partnerId,
                                                             String partnerName, Instant assignedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("partnerId", partnerId.toString());
        fields.put("partnerName", partnerName);
        fields.put("vehicleType", "SCOOTER");
        fields.put("manual", false);
        fields.put("assignedAt", assignedAt.toString());
        return deliveryEvent("DeliveryPartnerAssigned", eventId, orderId, fields);
    }

    protected ConsumerRecord<String, String> partnerUnassigned(String eventId, UUID orderId, UUID partnerId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("partnerId", partnerId.toString());
        fields.put("reason", "Partner stuck in traffic");
        fields.put("actorUserId", UUID.randomUUID().toString());
        fields.put("unassignedAt", Instant.now().toString());
        return deliveryEvent("DeliveryPartnerUnassigned", eventId, orderId, fields);
    }

    protected ConsumerRecord<String, String> deliveryPickedUp(String eventId, UUID orderId, UUID partnerId,
                                                              Instant pickedUpAt) {
        return deliveryEvent("DeliveryPickedUp", eventId, orderId, Map.of(
                "partnerId", partnerId.toString(), "pickedUpAt", pickedUpAt.toString()));
    }

    protected ConsumerRecord<String, String> deliveryCompleted(String eventId, UUID orderId, UUID partnerId,
                                                               Instant deliveredAt) {
        return deliveryEvent("DeliveryCompleted", eventId, orderId, Map.of(
                "partnerId", partnerId.toString(), "deliveredAt", deliveredAt.toString()));
    }

    /** {@code note} may be null (written as JSON null). */
    protected ConsumerRecord<String, String> deliveryFailed(String eventId, UUID orderId, UUID partnerId,
                                                            String reason, String note, Instant failedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("partnerId", partnerId.toString());
        fields.put("reason", reason);
        fields.put("note", note);
        fields.put("failedAt", failedAt.toString());
        return deliveryEvent("DeliveryFailed", eventId, orderId, fields);
    }

    /** The order's recorded delivery partner (null when none). */
    protected UUID partnerOf(UUID orderId) {
        List<String> ids = jdbc().sql("SELECT BIN_TO_UUID(partner_id) FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).list();
        return ids.isEmpty() || ids.getFirst() == null ? null : UUID.fromString(ids.getFirst());
    }

    /** A restaurant order ready for pickup with partner "Ravi Kumar" assigned, then picked up: OUT_FOR_DELIVERY. */
    protected UUID outForDeliveryOrder(UUID customerId) throws Exception {
        UUID orderId = readyOrder(customerId);
        UUID partnerId = UUID.randomUUID();
        deliveryListener.on(partnerAssigned(newEventId(), orderId, partnerId, "Ravi Kumar", Instant.now()));
        deliveryListener.on(deliveryPickedUp(newEventId(), orderId, partnerId, Instant.now()));
        return orderId;
    }

    /** A delivered restaurant order (Spice Route, 359.00): DELIVERED. */
    protected UUID deliveredOrder(UUID customerId) throws Exception {
        UUID orderId = outForDeliveryOrder(customerId);
        deliveryListener.on(deliveryCompleted(newEventId(), orderId, partnerOf(orderId), Instant.now()));
        return orderId;
    }

    /** A placed grocery order (Green Basket, 245.00 before any coupon) taken through to DELIVERED. */
    protected UUID deliveredGroceryOrder(UUID customerId) throws Exception {
        return deliver(confirmedGroceryOrder(customerId));
    }

    /** A paid grocery order (CONFIRMED) the vendor accepted and readied, picked up and delivered: DELIVERED. */
    protected UUID deliver(UUID groceryOrderId) {
        UUID vendorId = storeIdOf(groceryOrderId);
        vendorListener.on(storeAccepted(VENDOR, newEventId(), groceryOrderId, vendorId, 15, Instant.now()));
        vendorListener.on(storeReady(VENDOR, newEventId(), groceryOrderId, vendorId, Instant.now()));
        UUID partnerId = UUID.randomUUID();
        deliveryListener.on(deliveryPickedUp(newEventId(), groceryOrderId, partnerId, Instant.now()));
        deliveryListener.on(deliveryCompleted(newEventId(), groceryOrderId, partnerId, Instant.now()));
        return groceryOrderId;
    }

    // ---------------------------------------------------------------- coupons (Phase 12)

    /**
     * Applies coupon {@code code} to the customer's cart and makes pricing-service redeem it the same way:
     * {@code discount} off the items, with {@code freeDelivery} the whole delivery fee off; a platform coupon named
     * {@code "Offer <code>"}.
     */
    protected static void coupon(UUID customerId, String code, String discount, boolean freeDelivery) {
        coupon(customerId, code, discount, freeDelivery, "Offer " + code, "PLATFORM");
    }

    /** As above, with the {@code name} and {@code fundedBy} pricing-service answers (null: not sent). */
    protected static void coupon(UUID customerId, String code, String discount, boolean freeDelivery, String name,
                                 String fundedBy) {
        CART.coupons.put(customerId, new StubCart.Coupon(code, true, new BigDecimal(discount), freeDelivery, null));
        PRICING.offers.put(code, new StubPricing.Offer(new BigDecimal(discount), freeDelivery, name, fundedBy));
    }

    protected static String newCouponCode() {
        return "C" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    /**
     * A paid grocery order placed with a coupon: {@code discount} off the 220.00 items, with {@code freeDelivery} the
     * 25.00 fee off: CONFIRMED.
     */
    protected UUID confirmedGroceryOrderWithCoupon(UUID customerId, String discount, boolean freeDelivery)
            throws Exception {
        defaultAddress(customerId);
        groceryCart(customerId);
        coupon(customerId, newCouponCode(), discount, freeDelivery);
        BigDecimal total = GROCERY_TOTAL.subtract(new BigDecimal(discount))
                .subtract(freeDelivery ? StubCart.GROCERY_FEE : BigDecimal.ZERO);
        UUID orderId = id(body(place(customerId, newKey(), total).andExpect(status().isCreated())));
        inventoryListener.on(stockReserved(newEventId(), orderId));
        paymentListener.on(paymentCaptured(newEventId(), orderId, false));
        return orderId;
    }

    // ---------------------------------------------------------------- returns (Phase 12)

    /** {@code POST /api/orders/{id}/returns} as the customer; {@code lines}: itemId → quantity. */
    protected ResultActions requestReturn(UUID customerId, UUID orderId, Map<UUID, Integer> lines, String reason,
                                          String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", lines.entrySet().stream()
                .map(line -> Map.of("itemId", line.getKey().toString(), "quantity", line.getValue()))
                .toList());
        body.put("reason", reason);
        if (note != null) {
            body.put("note", note);
        }
        return mvc.perform(post("/api/orders/{id}/returns", orderId).with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    /** The order's line item ids, in order. */
    protected List<UUID> itemIds(UUID customerId, UUID orderId) throws Exception {
        return order(customerId, orderId).get("items").valueStream()
                .map(item -> UUID.fromString(item.get("itemId").asString()))
                .toList();
    }

    /** A RefundCompleted for a return (reason RETURN, {@code returnId}). */
    protected ConsumerRecord<String, String> returnRefundCompleted(String eventId, UUID orderId, UUID returnId,
                                                                   String amount, String totalRefunded,
                                                                   boolean fullyRefunded) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("paymentId", UUID.randomUUID().toString());
        payload.put("refundId", UUID.randomUUID().toString());
        payload.put("amount", new BigDecimal(amount));
        payload.put("reason", "RETURN");
        payload.put("totalRefunded", new BigDecimal(totalRefunded));
        payload.put("fullyRefunded", fullyRefunded);
        payload.put("returnId", returnId.toString());
        return paymentEvent("RefundCompleted", eventId, orderId, payload);
    }

    /** The order's store (vendorId / restaurantId). */
    protected UUID storeIdOf(UUID orderId) {
        return UUID.fromString(jdbc().sql("SELECT BIN_TO_UUID(store_id) FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).single());
    }

    // ---------------------------------------------------------------- database

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** The order's outbox event types, oldest first. */
    protected List<String> orderEventTypes(UUID orderId) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_type = 'Order' AND aggregate_id = ? "
                        + "ORDER BY occurred_at, id")
                .param(orderId.toString()).query(String.class).list();
    }

    /** Payloads of the order's outbox events of one type, oldest first. */
    protected List<JsonNode> orderEvents(UUID orderId, String eventType) {
        return jdbc().sql("SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ? "
                        + "ORDER BY occurred_at, id")
                .param(orderId.toString()).param(eventType)
                .query(String.class).list().stream()
                .map(json::readTree)
                .toList();
    }

    protected Map<String, Object> outboxRow(UUID orderId, String eventType) {
        return jdbc().sql("SELECT topic, aggregate_type, aggregate_id, event_version FROM outbox_event "
                        + "WHERE aggregate_id = ? AND event_type = ?")
                .param(orderId.toString()).param(eventType).query().singleRow();
    }

    protected long processed(String eventId, String consumer) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = ?")
                .param(eventId).param(consumer).query(Long.class).single();
    }

    protected long orderCount(UUID customerId) {
        return jdbc().sql("SELECT COUNT(*) FROM customer_order WHERE customer_id = UUID_TO_BIN(?)")
                .param(customerId.toString()).query(Long.class).single();
    }

    protected Map<String, Object> orderRow(UUID orderId) {
        return jdbc().sql("SELECT status, payment_status, version FROM customer_order WHERE id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query().singleRow();
    }

    /**
     * Moves a time of the order back: {@code column} of {@code customer_order} (e.g. {@code created_at},
     * {@code confirmed_at}) to {@code at}, and — when {@code status} is given — the timeline entry of that status too.
     */
    protected void movedBack(UUID orderId, String column, String status, Instant at) {
        jdbc().sql("UPDATE customer_order SET " + column + " = ? WHERE id = UUID_TO_BIN(?)")
                .param(at.atOffset(ZoneOffset.UTC).toLocalDateTime()).param(orderId.toString()).update();
        if (status != null) {
            jdbc().sql("UPDATE order_status_history SET changed_at = ? WHERE order_id = UUID_TO_BIN(?) AND status = ?")
                    .param(at.atOffset(ZoneOffset.UTC).toLocalDateTime()).param(orderId.toString()).param(status)
                    .update();
        }
    }

    /** The order's stuck flags: status → {reason, since, due_at, detected_at, resolved_at}. */
    protected Map<String, Map<String, Object>> stuckFlags(UUID orderId) {
        Map<String, Map<String, Object>> flags = new LinkedHashMap<>();
        jdbc().sql("SELECT status, reason, since, due_at, detected_at, resolved_at FROM stuck_order "
                        + "WHERE order_id = UUID_TO_BIN(?) ORDER BY detected_at, status")
                .param(orderId.toString()).query().listOfRows()
                .forEach(row -> flags.put((String) row.get("status"), row));
        return flags;
    }

    /** The keys of a JSON object, in order. */
    protected static List<String> fields(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
