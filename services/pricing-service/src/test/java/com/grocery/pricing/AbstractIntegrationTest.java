package com.grocery.pricing;

import com.grocery.common.id.Ids;
import com.grocery.pricing.event.CatalogEventsListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Real MySQL 8.4 and Redis in throw-away containers, shared by all test classes. Requests are authenticated with
 * Spring Security's test JWT support (same claims identity issues), so the real security chain and @PreAuthorize
 * rules run. Kafka listeners are off; catalog events are fed to the listener directly. Tests use random ids only.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // ADR-0011: the in-memory Schema Registry; tests register the central schemas themselves
        "grocery.events.avro.schema-registry-url=mock://pricing-service",
        "grocery.events.avro.auto-register-schemas=true",
        // Phase 12: the coupon sweeper is run by hand in the tests
        "grocery.pricing.coupons.sweeper-enabled=false"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected CatalogEventsListener catalogEvents;
    @Autowired
    protected StringRedisTemplate redis;

    protected final UUID admin = UUID.randomUUID();

    /** A request authenticated as {@code userId} holding {@code roles}. */
    protected static JwtRequestPostProcessor as(UUID userId, String... roles) {
        return as(userId, null, roles);
    }

    /** Same, with the {@code vendor_id} claim identity adds while the user's vendor is APPROVED. */
    protected static JwtRequestPostProcessor as(UUID userId, UUID vendorId, String... roles) {
        return as(userId, vendorId, null, roles);
    }

    /** Same, with the {@code vendor_id} / {@code restaurant_id} claims (null = absent). */
    protected static JwtRequestPostProcessor as(UUID userId, UUID vendorId, UUID restaurantId, String... roles) {
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
                    if (restaurantId != null) {
                        token.claim("restaurant_id", restaurantId.toString());
                    }
                })
                .authorities(roleList.stream()
                        .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    /** An approved restaurant's owner (user id derived from the restaurant id). */
    protected static JwtRequestPostProcessor restaurantOwner(UUID restaurantId) {
        return as(vendorUser(restaurantId), null, restaurantId, "RESTAURANT_OWNER");
    }

    protected static JwtRequestPostProcessor customer(UUID customerId) {
        return as(customerId, "CUSTOMER");
    }

    /** An approved vendor's owner (user id derived from the vendor id for brevity). */
    protected static JwtRequestPostProcessor vendor(UUID vendorId) {
        return as(vendorUser(vendorId), vendorId, "VENDOR");
    }

    protected static UUID vendorUser(UUID vendorId) {
        return UUID.nameUUIDFromBytes(vendorId.toString().getBytes(StandardCharsets.UTF_8));
    }

    protected JwtRequestPostProcessor opsAdmin() {
        return as(admin, "OPS_ADMIN");
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---- catalog events ----

    /** A ProductUpserted record as the Debezium outbox router delivers it. */
    protected static ConsumerRecord<String, String> productUpserted(String eventId, UUID productId, UUID vendorId,
                                                                    String name, String sku, String status,
                                                                    long version) {
        String payload = """
                {"productId":"%s","vendorId":"%s","vendorName":"Green Basket","vendorActive":true,"name":"%s",
                 "description":null,"brand":null,"category":{"id":"%s","name":"Vegetables"},"categoryPath":[],
                 "categoryActive":true,"unitValue":500,"unitOfMeasure":"G","unitLabel":"500 g","sku":%s,
                 "barcode":null,"foodType":"VEG","tags":[],"attributes":[],"imageUrls":[],"status":"%s",
                 "publishedAt":null,"updatedAt":"2026-11-11T10:15:30Z","productVersion":%d}"""
                .formatted(productId, vendorId, name, UUID.randomUUID(), sku == null ? "null" : "\"" + sku + "\"",
                        status, version);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("catalog.events", 0, 0L, productId.toString(), payload);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", "ProductUpserted".getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Product".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** Feeds a ProductUpserted snapshot to the catalog listener (new event id). */
    protected void upsertProduct(UUID productId, UUID vendorId, String name, String sku, String status, long version) {
        catalogEvents.on(productUpserted(Ids.newId().toString(), productId, vendorId, name, sku, status, version));
    }

    /** A new PUBLISHED product of this vendor, known to pricing (version 1). */
    protected UUID product(UUID vendorId, String name) {
        UUID productId = Ids.newId();
        upsertProduct(productId, vendorId, name, null, "PUBLISHED", 1);
        return productId;
    }

    // ---- prices ----

    protected ResultActions putPrice(UUID vendorId, UUID productId, String mrp, String sellingPrice) throws Exception {
        return putPrice(vendorId, productId, "{\"mrp\":%s,\"sellingPrice\":%s}".formatted(mrp, sellingPrice));
    }

    protected ResultActions putPrice(UUID vendorId, UUID productId, String body) throws Exception {
        return mvc.perform(put("/api/vendor/prices/{id}", productId).with(vendor(vendorId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** A product of this vendor with a live first price. */
    protected UUID pricedProduct(UUID vendorId, String name, String mrp, String sellingPrice) throws Exception {
        UUID productId = product(vendorId, name);
        putPrice(vendorId, productId, mrp, sellingPrice)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        return productId;
    }

    /** Creates a PENDING price-change request (a change beyond the threshold) and returns its id. */
    protected String pendingRequest(UUID vendorId, UUID productId, String mrp, String sellingPrice) throws Exception {
        return body(putPrice(vendorId, productId, mrp, sellingPrice)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted()))
                .get("pendingRequest").get("id").asString();
    }

    protected ResultActions approve(String requestId, String... roles) throws Exception {
        return mvc.perform(post("/api/admin/price-change-requests/{id}/approve", requestId)
                .with(as(admin, roles.length == 0 ? new String[]{"OPS_ADMIN"} : roles)));
    }

    // ---- outbox / database ----

    /** Payloads of a product's outbox events of one type, oldest first. */
    protected List<JsonNode> events(UUID productId, String eventType) {
        return JdbcClient.create(dataSource)
                .sql("""
                        SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                        ORDER BY occurred_at, id""")
                .param(productId.toString()).param(eventType)
                .query(String.class).list().stream()
                .map(json::readTree)
                .toList();
    }

    protected List<JsonNode> priceChangedEvents(UUID productId) {
        return events(productId, "PriceChanged");
    }

    protected JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    // ---- coupons (Phase 12) ----

    /** A unique, valid coupon code with the given prefix (≤ 20 characters). */
    protected static String code(String prefix) {
        return (prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)).toUpperCase();
    }

    /** POST of a coupon at {@code basePath} (admin, vendor or restaurant) with a JSON body. */
    protected ResultActions createCoupon(String basePath, RequestPostProcessor caller, String body) throws Exception {
        return mvc.perform(post(basePath).with(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Creates an admin coupon and returns its view; {@code fields} is JSON members without braces. */
    protected JsonNode adminCoupon(String fields) throws Exception {
        return body(createCoupon("/api/admin/coupons", opsAdmin(), "{" + fields + "}")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()));
    }

    /** JSON members of a coupon valid from an hour ago for a week. */
    protected static String validWindow() {
        java.time.Instant now = java.time.Instant.now();
        return "\"validFrom\":\"%s\",\"validUntil\":\"%s\"".formatted(now.minusSeconds(3600),
                now.plusSeconds(7 * 24 * 3600));
    }

    protected ResultActions evaluate(UUID customerId, String body) throws Exception {
        return mvc.perform(post("/internal/coupons/evaluate").with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions redeem(UUID customerId, String body) throws Exception {
        return mvc.perform(post("/internal/coupons/redeem").with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** An evaluate body for a grocery cart of {@code storeId}. */
    protected static String evaluation(String code, UUID customerId, UUID storeId, String orderType, String subtotal,
                                       String deliveryFee) {
        return """
                {"code":"%s","customerId":"%s","storeId":"%s","orderType":"%s","subtotal":%s,"deliveryFee":%s}"""
                .formatted(code, customerId, storeId, orderType, subtotal, deliveryFee);
    }

    /** A redeem body. */
    protected static String redemption(String code, UUID orderId, UUID customerId, UUID storeId, String orderType,
                                       String subtotal, String deliveryFee) {
        return """
                {"code":"%s","orderId":"%s","customerId":"%s","storeId":"%s","orderType":"%s","subtotal":%s,\
                "deliveryFee":%s}""".formatted(code, orderId, customerId, storeId, orderType, subtotal, deliveryFee);
    }

    /** The status of an order's redemption, or null. */
    protected String redemptionStatus(UUID orderId) {
        return jdbc().sql("SELECT status FROM coupon_redemption WHERE order_id = UUID_TO_BIN(?)")
                .param(orderId.toString()).query(String.class).optional().orElse(null);
    }

    /** An order.events record as Debezium's outbox router delivers it. */
    protected static ConsumerRecord<String, String> orderEvent(String eventType, String eventId, UUID orderId) {
        String payload = """
                {"orderId":"%s","orderNumber":"GA-%s","orderType":"GROCERY","customerId":"%s","storeId":"%s"}"""
                .formatted(orderId, orderId.toString().substring(0, 8).toUpperCase(), UUID.randomUUID(),
                        UUID.randomUUID());
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.events", 0, 0L, orderId.toString(),
                payload);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventVersion", "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregateType", "Order".getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
