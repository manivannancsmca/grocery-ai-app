package com.grocery.review;

import com.grocery.common.id.Ids;
import com.grocery.review.event.OrderEventsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real MySQL 8.4 in a throw-away container; Kafka listeners off — tests hand {@code order.events} records to the
 * listener directly (routing data in the headers, as Debezium delivers them). Outbox payloads are also encoded as Avro
 * against an in-memory Schema Registry. Requests are authenticated with Spring Security's test JWT support, so the real
 * security chain and {@code @PreAuthorize} rules run. The database is shared by all test classes: every test uses fresh
 * random ids.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // the concurrency tests queue more threads than the pool has connections
        "spring.datasource.hikari.connection-timeout=30000",
        // Avro outbox payloads (ADR-0011) against an in-memory Schema Registry that registers the schemas itself
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class AbstractIntegrationTest {

    /** Confluent's in-memory Schema Registry, one scope per service (shared by every client of this JVM). */
    public static final String SCHEMA_REGISTRY_URL = "mock://review-service";

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
    protected OrderEventsListener orderListener;
    @Autowired
    protected TestClock clock;

    /** "Asha Sharma", publicly "Asha S.". */
    protected final UUID customer = UUID.randomUUID();
    protected final UUID otherCustomer = UUID.randomUUID();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---- callers ------------------------------------------------------------------------------------------

    /** A request as {@code userId} with {@code name} and {@code roles} (claims like identity-service's). */
    protected static JwtRequestPostProcessor as(UUID userId, String name, Map<String, Object> extraClaims,
                                                String... roles) {
        List<String> roleList = Arrays.asList(roles);
        return jwt()
                .jwt(token -> {
                    token.subject(userId.toString())
                            .claim("email", "user-" + userId.toString().substring(0, 8) + "@example.com")
                            .claim("roles", roleList);
                    if (name != null) {
                        token.claim("name", name);
                    }
                    extraClaims.forEach(token::claim);
                })
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    protected JwtRequestPostProcessor customer(UUID userId) {
        return as(userId, userId.equals(customer) ? "Asha Sharma" : "Ravi Kumar", Map.of(), "CUSTOMER");
    }

    protected static JwtRequestPostProcessor vendor(UUID userId, UUID vendorId) {
        return as(userId, "Vendor Owner", vendorId == null ? Map.of() : Map.of("vendor_id", vendorId.toString()),
                "VENDOR");
    }

    protected static JwtRequestPostProcessor restaurantOwner(UUID userId, UUID restaurantId) {
        return as(userId, "Restaurant Owner", Map.of("restaurant_id", restaurantId.toString()), "RESTAURANT_OWNER");
    }

    protected static JwtRequestPostProcessor admin(UUID userId, String role) {
        return as(userId, "Admin " + role, Map.of(), role);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---- order.events -------------------------------------------------------------------------------------

    protected record Item(UUID itemId, String type, String name) {
    }

    /** An order as order-service describes it: a GROCERY order with products or a RESTAURANT order with dishes. */
    protected record Order(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                           String storeName, List<Item> items, UUID partnerId) {

        public Item item(int index) {
            return items.get(index);
        }

        public String storeType() {
            return "GROCERY".equals(orderType) ? "VENDOR" : "RESTAURANT";
        }
    }

    protected static Order groceryOrder(UUID customerId, String... products) {
        UUID orderId = Ids.newId();
        List<Item> items = new ArrayList<>();
        for (String name : products) {
            items.add(new Item(UUID.randomUUID(), "PRODUCT", name));
        }
        return new Order(orderId, orderNumber(orderId), "GROCERY", customerId, UUID.randomUUID(), "Green Basket",
                items, UUID.randomUUID());
    }

    protected static Order restaurantOrder(UUID customerId, String... dishes) {
        UUID orderId = Ids.newId();
        List<Item> items = new ArrayList<>();
        for (String name : dishes) {
            items.add(new Item(UUID.randomUUID(), "MENU_ITEM", name));
        }
        return new Order(orderId, orderNumber(orderId), "RESTAURANT", customerId, UUID.randomUUID(), "Spice Route",
                items, UUID.randomUUID());
    }

    protected static String orderNumber(UUID orderId) {
        return "GA-" + orderId.toString().replace("-", "").substring(20).toUpperCase();
    }

    /** A record as Debezium's outbox router delivers it: routing data in the headers, the payload as the value. */
    protected ConsumerRecord<String, String> orderEvent(String eventType, String eventId, UUID orderId,
                                                        Map<String, Object> payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0L, orderId.toString(),
                json.writeValueAsString(payload));
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Order".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** OrderConfirmed with every field of the 1.5.0 schema, as order-service publishes it. */
    protected Map<String, Object> orderConfirmedPayload(Order order) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", order.orderId().toString());
        p.put("orderNumber", order.orderNumber());
        p.put("orderType", order.orderType());
        p.put("customerId", order.customerId().toString());
        p.put("storeId", order.storeId().toString());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Item item : order.items()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("itemId", item.itemId().toString());
            line.put("type", item.type());
            line.put("name", item.name());
            line.put("quantity", 2);
            line.put("unitPrice", new BigDecimal("49.50"));
            line.put("lineTotal", new BigDecimal("99.00"));
            lines.add(line);
        }
        p.put("items", lines);
        p.put("total", new BigDecimal("99.00").multiply(BigDecimal.valueOf(lines.size())).add(new BigDecimal("30.00")));
        p.put("deliveryAddress", null);
        p.put("confirmedAt", clock.instant().minusSeconds(3600).toString());
        p.put("subtotal", new BigDecimal("99.00").multiply(BigDecimal.valueOf(lines.size())));
        p.put("note", null);
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("name", order.storeName());
        location.put("city", "Bengaluru");
        location.put("latitude", 12.9716);
        location.put("longitude", 77.5946);
        p.put("storeLocation", location);
        p.put("couponCode", null);
        p.put("discount", null);
        p.put("deliveryFeeDiscount", null);
        return p;
    }

    protected Map<String, Object> orderDeliveredPayload(Order order, Instant deliveredAt) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("orderId", order.orderId().toString());
        p.put("orderNumber", order.orderNumber());
        p.put("orderType", order.orderType());
        p.put("customerId", order.customerId().toString());
        p.put("storeId", order.storeId().toString());
        p.put("partnerId", order.partnerId().toString());
        p.put("deliveredAt", deliveredAt.toString());
        return p;
    }

    protected void confirm(Order order) {
        orderListener.on(orderEvent("OrderConfirmed", newEventId(), order.orderId(), orderConfirmedPayload(order)));
    }

    protected void deliver(Order order) {
        deliver(order, clock.instant());
    }

    protected void deliver(Order order, Instant deliveredAt) {
        orderListener.on(orderEvent("OrderDelivered", newEventId(), order.orderId(),
                orderDeliveredPayload(order, deliveredAt)));
    }

    /** A confirmed and delivered GROCERY order of {@code customerId} with these products. */
    protected Order deliveredGroceryOrder(UUID customerId, String... products) {
        Order order = groceryOrder(customerId, products);
        confirm(order);
        deliver(order);
        return order;
    }

    protected Order deliveredRestaurantOrder(UUID customerId, String... dishes) {
        Order order = restaurantOrder(customerId, dishes);
        confirm(order);
        deliver(order);
        return order;
    }

    protected static String newEventId() {
        return Ids.newId().toString();
    }

    // ---- review API ---------------------------------------------------------------------------------------

    protected ResultActions submit(UUID customerId, UUID orderId, String targetType, UUID targetId, int rating,
                                   String title, String comment) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", orderId.toString());
        request.put("targetType", targetType);
        request.put("targetId", targetId.toString());
        request.put("rating", rating);
        request.put("title", title);
        request.put("comment", comment);
        return mvc.perform(post("/api/reviews").with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    /** Submits and expects 201; returns the ReviewView. */
    protected JsonNode review(UUID customerId, UUID orderId, String targetType, UUID targetId, int rating,
                              String title, String comment) throws Exception {
        return body(submit(customerId, orderId, targetType, targetId, rating, title, comment)
                .andExpect(status().isCreated()));
    }

    /** A published review of the order's first item. */
    protected JsonNode reviewFirstItem(Order order, int rating) throws Exception {
        Item item = order.item(0);
        return review(order.customerId(), order.orderId(), item.type(), item.itemId(), rating, "Fresh", "Good quality");
    }

    protected ResultActions edit(UUID customerId, UUID reviewId, int rating, String title, String comment)
            throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("rating", rating);
        request.put("title", title);
        request.put("comment", comment);
        return mvc.perform(put("/api/reviews/{id}", reviewId).with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    protected ResultActions moderate(UUID reviewId, String action, String reason, String role) throws Exception {
        var request = post("/api/admin/reviews/{id}/" + action, reviewId).with(admin(UUID.randomUUID(), role));
        if (reason != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("reason", reason)));
        }
        return mvc.perform(request);
    }

    protected static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    // ---- database -----------------------------------------------------------------------------------------

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /** Payloads of an aggregate's outbox events of one type, oldest first. */
    protected List<JsonNode> events(UUID aggregateId, String eventType) {
        return jdbc()
                .sql("SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ? ORDER BY occurred_at, id")
                .param(aggregateId.toString()).param(eventType)
                .query(String.class).list().stream()
                .map(json::readTree)
                .toList();
    }

    protected JsonNode lastEvent(UUID aggregateId, String eventType) {
        List<JsonNode> found = events(aggregateId, eventType);
        if (found.isEmpty()) {
            throw new AssertionError("No " + eventType + " for " + aggregateId);
        }
        return found.getLast();
    }

    /** Event types of one aggregate, oldest first. */
    protected List<String> eventTypes(UUID aggregateId) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY occurred_at, id")
                .param(aggregateId.toString()).query(String.class).list();
    }

    protected String statusOf(UUID reviewId) {
        return jdbc().sql("SELECT status FROM review WHERE id = UUID_TO_BIN(?)")
                .param(reviewId.toString()).query(String.class).single();
    }
}
