package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.inventory.event.CatalogEventsListener;
import com.grocery.inventory.event.OrderEventsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MySQL 8.4 in a throw-away container. Requests are authenticated with Spring Security's test JWT support
 * (same claims identity issues), so the real security chain and @PreAuthorize rules run. Products arrive the
 * way they do in production: a {@code ProductUpserted} record handed to the Kafka listener. The database is
 * shared by all test classes: tests use random vendor and product ids, never fixed ones.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // tests run the expiry poller themselves, with a clock in the future
        "grocery.inventory.expiry.enabled=false",
        // the concurrency tests queue far more threads than the pool has connections
        "spring.datasource.hikari.connection-timeout=30000",
        // ADR-0011: the in-memory Schema Registry; tests register the central schemas themselves
        "grocery.events.avro.schema-registry-url=mock://inventory-service",
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected CatalogEventsListener catalogListener;
    @Autowired
    protected OrderEventsListener orderListener;

    protected final UUID admin = UUID.randomUUID();
    protected final UUID vendorId = UUID.randomUUID();

    /** A request authenticated as {@code userId} holding {@code roles}. */
    protected static JwtRequestPostProcessor as(UUID userId, String... roles) {
        return as(userId, null, roles);
    }

    /** Same, with the {@code vendor_id} claim identity adds while the user's vendor is APPROVED. */
    protected static JwtRequestPostProcessor as(UUID userId, UUID vendorId, String... roles) {
        List<String> roleList = Arrays.asList(roles);
        return jwt()
                .jwt(token -> {
                    token.subject(userId.toString())
                            .claim("email", "user-" + userId.toString().substring(0, 8) + "@example.com")
                            .claim("name", "User " + userId.toString().substring(0, 8))
                            .claim("roles", roleList);
                    if (vendorId != null) {
                        token.claim("vendor_id", vendorId.toString());
                    }
                })
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    /** The owner of an approved vendor (user id derived from the vendor id for brevity). */
    protected static UUID ownerOf(UUID vendorId) {
        return UUID.nameUUIDFromBytes(vendorId.toString().getBytes(StandardCharsets.UTF_8));
    }

    protected static JwtRequestPostProcessor vendor(UUID vendorId) {
        return as(ownerOf(vendorId), vendorId, "VENDOR");
    }

    /** The same caller as {@link #vendor}, for calling services directly. */
    protected static CurrentUser vendorUser(UUID vendorId) {
        return new CurrentUser(ownerOf(vendorId), "vendor@example.com", "Vendor", Set.of("VENDOR"), vendorId, null);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- catalog.events -----------------------------------------------------------------------------------

    /** A ProductUpserted record as Debezium delivers it (full Phase 3 snapshot, routing data in headers). */
    protected ConsumerRecord<String, String> productUpserted(String eventId, UUID productId, UUID vendorId,
                                                             String name, String sku, String status, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId.toString());
        payload.put("vendorId", vendorId.toString());
        payload.put("vendorName", "Green Basket");
        payload.put("vendorActive", true);
        payload.put("name", name);
        payload.put("description", null);
        payload.put("brand", null);
        payload.put("category", Map.of("id", UUID.randomUUID().toString(), "name", "Vegetables"));
        payload.put("categoryPath", List.of());
        payload.put("categoryActive", true);
        payload.put("unitValue", 500);
        payload.put("unitOfMeasure", "G");
        payload.put("unitLabel", "500 g");
        payload.put("sku", sku);
        payload.put("barcode", null);
        payload.put("foodType", "VEG");
        payload.put("tags", List.of());
        payload.put("attributes", List.of());
        payload.put("imageUrls", List.of());
        payload.put("status", status);
        payload.put("publishedAt", "2026-11-11T10:00:00Z");
        payload.put("updatedAt", "2026-11-11T10:00:00Z");
        payload.put("productVersion", version);
        return record("ProductUpserted", eventId, productId.toString(), json.writeValueAsString(payload));
    }

    protected static ConsumerRecord<String, String> record(String eventType, String eventId, String key, String payload) {
        return record("catalog.events", "Product", eventType, eventId, key, payload);
    }

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

    // ---- order.events -------------------------------------------------------------------------------------

    /** An order.events record (aggregate Order, key orderId). */
    protected ConsumerRecord<String, String> orderEvent(String eventType, String eventId, UUID orderId,
                                                        Map<String, Object> payload) {
        return record("order.events", "Order", eventType, eventId, orderId.toString(),
                json.writeValueAsString(payload));
    }

    /** A full OrderCreated (contract §6); {@code lines} = product id → quantity, in order, repeats allowed. */
    protected ConsumerRecord<String, String> orderCreated(String eventId, UUID orderId, String orderType,
                                                          List<Map.Entry<UUID, Integer>> lines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", "GA-" + orderId.toString().substring(0, 8).toUpperCase());
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", vendorId.toString());
        payload.put("storeName", "Green Basket");
        payload.put("items", lines.stream().map(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemId", line.getKey().toString());
            item.put("type", "GROCERY".equals(orderType) ? "PRODUCT" : "MENU_ITEM");
            item.put("name", "Item");
            item.put("quantity", line.getValue());
            item.put("unitPrice", 50.00);
            item.put("lineTotal", 50.00 * line.getValue());
            return item;
        }).toList());
        payload.put("subtotal", 100.00);
        payload.put("deliveryFee", 25.00);
        payload.put("total", 125.00);
        payload.put("currency", "INR");
        payload.put("cartUpdatedAt", "2026-11-11T10:00:00.123Z");
        payload.put("paymentDueAt", "2026-11-11T10:15:00Z");
        payload.put("createdAt", "2026-11-11T10:00:00Z");
        return orderEvent("OrderCreated", eventId, orderId, payload);
    }

    protected ConsumerRecord<String, String> orderConfirmed(String eventId, UUID orderId, String orderType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", "GA-TEST");
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", vendorId.toString());
        payload.put("items", List.of());
        payload.put("total", 125.00);
        payload.put("deliveryAddress", Map.of("city", "Bengaluru"));
        payload.put("confirmedAt", "2026-11-11T10:05:00Z");
        return orderEvent("OrderConfirmed", eventId, orderId, payload);
    }

    protected ConsumerRecord<String, String> orderCancelled(String eventId, UUID orderId, String orderType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", "GA-TEST");
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", vendorId.toString());
        payload.put("previousStatus", "AWAITING_PAYMENT");
        payload.put("reason", "CUSTOMER_CANCELLED");
        payload.put("cancelledBy", "CUSTOMER");
        payload.put("actorUserId", null);
        payload.put("note", null);
        payload.put("cancelledAt", "2026-11-11T10:06:00Z");
        return orderEvent("OrderCancelled", eventId, orderId, payload);
    }

    /** Phase 9: the partner picked the order up (OrderOutForDelivery as order-service publishes it). */
    protected ConsumerRecord<String, String> orderOutForDelivery(String eventId, UUID orderId, String orderType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", "GA-TEST");
        payload.put("orderType", orderType);
        payload.put("customerId", UUID.randomUUID().toString());
        payload.put("storeId", vendorId.toString());
        payload.put("partnerId", UUID.randomUUID().toString());
        payload.put("pickedUpAt", "2026-11-11T10:45:00Z");
        return orderEvent("OrderOutForDelivery", eventId, orderId, payload);
    }

    protected static String newEventId() {
        return Ids.newId().toString();
    }

    /** A reservation's row: status and expires_at (null = held until pickup). */
    protected Map<String, Object> reservationRow(UUID orderId) {
        return jdbc().sql("SELECT status, expires_at FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query().singleRow();
    }

    protected long reservationCount(UUID orderId) {
        return jdbc().sql("SELECT COUNT(*) FROM stock_reservation WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(Long.class).single();
    }

    /** Every outbox event about one order (aggregate Reservation), as "eventType", oldest first. */
    protected List<String> reservationEventTypes(UUID orderId) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_type = 'Reservation' "
                        + "AND aggregate_id = ? ORDER BY occurred_at, id")
                .param(orderId.toString()).query(String.class).list();
    }

    /** Whether the order-event consumer has marked {@code eventId} processed (0 or 1). */
    protected long processed(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = 'inventory-service.order-events'")
                .param(eventId).query(Long.class).single();
    }

    /** The routing columns of an order's (single) outbox event of one type. */
    protected Map<String, Object> outboxRow(UUID orderId, String eventType) {
        return jdbc().sql("SELECT topic, aggregate_type, aggregate_id FROM outbox_event WHERE aggregate_id = ? AND event_type = ?")
                .param(orderId.toString()).param(eventType).query().singleRow();
    }

    protected long movements(UUID orderId, String type) {
        return jdbc().sql("SELECT COUNT(*) FROM stock_movement WHERE reference = ? AND type = ?")
                .param(orderId.toString()).param(type).query(Long.class).single();
    }

    protected void upsert(UUID productId, UUID vendorId, String name, String sku, String status, long version) {
        catalogListener.on(productUpserted(Ids.newId().toString(), productId, vendorId, name, sku, status, version));
    }

    /** A new PUBLISHED product of {@code vendorId} (version 1), known to inventory with 0 on hand. */
    protected UUID newProduct(UUID vendorId, String name) {
        UUID productId = Ids.newId();
        upsert(productId, vendorId, name, null, "PUBLISHED", 1);
        return productId;
    }

    protected UUID newProductWithStock(UUID vendorId, String name, int onHand) throws Exception {
        UUID productId = newProduct(vendorId, name);
        if (onHand > 0) {
            adjust(vendorId, productId, "INCREASE", onHand, "RESTOCK");
        }
        return productId;
    }

    // ---- inventory API ------------------------------------------------------------------------------------

    protected ResultActions adjustRequest(UUID vendorId, UUID productId, String type, int quantity, String reason)
            throws Exception {
        return mvc.perform(post("/api/vendor/inventory/{id}/adjustments", productId).with(vendor(vendorId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"%s\",\"quantity\":%d,\"reason\":\"%s\"}".formatted(type, quantity, reason)));
    }

    protected JsonNode adjust(UUID vendorId, UUID productId, String type, int quantity, String reason) throws Exception {
        return body(adjustRequest(vendorId, productId, type, quantity, reason).andExpect(status().isOk()));
    }

    protected JsonNode stock(UUID vendorId, UUID productId) throws Exception {
        return body(mvc.perform(get("/api/vendor/inventory/{id}", productId).with(vendor(vendorId)))
                .andExpect(status().isOk()));
    }

    // ---- database -----------------------------------------------------------------------------------------

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** Payloads of a product's outbox events of one type, oldest first. */
    protected List<JsonNode> events(UUID productId, String eventType) {
        return jdbc()
                .sql("SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ? ORDER BY occurred_at, id")
                .param(productId.toString()).param(eventType)
                .query(String.class).list().stream()
                .map(json::readTree)
                .toList();
    }

    /** Same, ordered by {@code stockVersion} (for events written by concurrent transactions). */
    protected List<JsonNode> eventsByVersion(UUID productId, String eventType) {
        return events(productId, eventType).stream()
                .sorted(Comparator.comparingLong(e -> e.get("stockVersion").asLong()))
                .toList();
    }

    protected Map<String, Object> stockRow(UUID productId) {
        return jdbc()
                .sql("SELECT on_hand, reserved, stock_version, low_stock_threshold FROM stock_item WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString())
                .query().singleRow();
    }

    protected long stockVersion(UUID productId) {
        return ((Number) stockRow(productId).get("stock_version")).longValue();
    }

    protected int onHand(UUID productId) {
        return ((Number) stockRow(productId).get("on_hand")).intValue();
    }

    protected int reserved(UUID productId) {
        return ((Number) stockRow(productId).get("reserved")).intValue();
    }

    protected long movementCount(UUID productId) {
        return jdbc().sql("SELECT COUNT(*) FROM stock_movement WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single();
    }
}
