package com.grocery.cart;

import com.grocery.cart.store.CartStore;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Real Redis in a throw-away container and one stub HTTP server for all six downstream services, shared by all
 * test classes (one Spring context). Requests are authenticated with Spring Security's test JWT support (same
 * claims identity issues; token value "token-&lt;userId&gt;", which the stub uses as "me"), so the real security
 * chain and @PreAuthorize rules run. Tests use random ids only; every test starts with all dependencies up, no
 * recorded calls and closed circuit breakers. No Kafka broker: tests hand order.events records to the listener
 * directly, as Kafka would.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // order.events values are Avro (ADR-0011): decoded against an in-memory Schema Registry
        "grocery.events.avro.schema-registry-url=" + AbstractIntegrationTest.SCHEMA_REGISTRY_URL,
        "grocery.events.avro.auto-register-schemas=true"
})
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    /** Confluent's in-memory Schema Registry, one scope per service (shared by every client of this JVM). */
    public static final String SCHEMA_REGISTRY_URL = "mock://cart-service";

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine")).withExposedPorts(6379);
    static final StubServices STUB = StubServices.start();

    /** Stores and restaurants are here; an address at (STORE_LAT + d, STORE_LNG) is d × 111.19 km away. */
    static final double STORE_LAT = 12.9716;
    static final double STORE_LNG = 77.5946;

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        for (String service : List.of("pricing", "inventory", "catalog-query", "vendor", "restaurant", "customer")) {
            registry.add("grocery.cart.services." + service, STUB::baseUrl);
        }
        registry.add("grocery.cart.http.read-timeout", () -> "700ms");
        // small breaker windows so a test can open one with a few calls
        registry.add("grocery.cart.circuit-breaker.sliding-window-size", () -> "4");
        registry.add("grocery.cart.circuit-breaker.minimum-number-of-calls", () -> "4");
        registry.add("grocery.cart.circuit-breaker.wait-duration-in-open-state", () -> "60s");
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JsonMapper json;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected Resilience4JCircuitBreakerFactory breakers;

    @BeforeEach
    protected void resetStubAndBreakers() {
        STUB.reset();
        resetBreakers();
    }

    /** Closes every circuit breaker and forgets its recorded calls. */
    protected void resetBreakers() {
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

    // ---------------------------------------------------------------- stub data

    protected static UUID vendor(String name) {
        UUID id = UUID.randomUUID();
        STUB.vendors.put(id, new StubServices.Vendor(id, name, true, STORE_LAT, STORE_LNG));
        return id;
    }

    protected static UUID product(UUID vendorId, String name, String mrp, String sellingPrice, Integer available) {
        UUID id = UUID.randomUUID();
        String vendorName = STUB.vendors.containsKey(vendorId) ? STUB.vendors.get(vendorId).name() : "Some Store";
        STUB.products.put(id, new StubServices.Product(id, vendorId, vendorName, name, "1 kg", true,
                mrp == null ? null : new BigDecimal(mrp), sellingPrice == null ? null : new BigDecimal(sellingPrice),
                available));
        return id;
    }

    protected static void setPrice(UUID productId, String mrp, String sellingPrice) {
        STUB.products.computeIfPresent(productId, (id, p) -> p.withPrice(mrp, sellingPrice));
    }

    protected static void setStock(UUID productId, Integer available) {
        STUB.products.computeIfPresent(productId, (id, p) -> p.withStock(available));
    }

    protected static void setVisible(UUID productId, boolean visible) {
        STUB.products.computeIfPresent(productId, (id, p) -> p.withVisible(visible));
    }

    protected static UUID restaurant(String name) {
        UUID id = UUID.randomUUID();
        STUB.restaurants.put(id, new StubServices.Restaurant(id, name, "APPROVED", true, true, STORE_LAT, STORE_LNG,
                new ConcurrentHashMap<>()));
        return id;
    }

    protected static void setRestaurant(UUID restaurantId, String status, boolean acceptingOrders, boolean openNow) {
        STUB.restaurants.computeIfPresent(restaurantId, (id, r) -> r.with(status, acceptingOrders, openNow));
    }

    protected static UUID menuItem(UUID restaurantId, String name, String price) {
        UUID id = UUID.randomUUID();
        STUB.restaurants.get(restaurantId).menu()
                .put(id, new StubServices.MenuItem(id, name, new BigDecimal(price), "NON_VEG", true));
        return id;
    }

    protected static void setMenuItem(UUID restaurantId, UUID itemId, String price, boolean available) {
        STUB.restaurants.get(restaurantId).menu().computeIfPresent(itemId,
                (id, item) -> new StubServices.MenuItem(id, item.name(), new BigDecimal(price), item.foodType(),
                        available));
    }

    /** An address of {@code customerId} {@code km} km (straight line, due north) from the stores; null = no pin. */
    protected static UUID address(UUID customerId, Double northKm, boolean isDefault) {
        UUID id = UUID.randomUUID();
        Double latitude = northKm == null ? null : STORE_LAT + northKm / 111.19492664;
        Double longitude = northKm == null ? null : STORE_LNG;
        STUB.addresses.compute(customerId, (c, list) -> {
            List<StubServices.Address> updated = list == null ? new ArrayList<>() : new ArrayList<>(list);
            updated.add(new StubServices.Address(id, "Home", latitude, longitude, isDefault));
            return updated;
        });
        return id;
    }

    /**
     * Phase 12: a coupon pricing-service knows (unique code with this prefix). {@code scope} PLATFORM / STORE,
     * {@code storeType} GROCERY / RESTAURANT / ANY, {@code discountType} PERCENT / FLAT / FREE_DELIVERY.
     */
    protected static String coupon(String prefix, String scope, UUID storeId, String storeType, String discountType,
                                   String value, String maxDiscount, String minOrderSubtotal) {
        String code = (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase();
        STUB.coupons.put(code, new StubServices.Coupon(code, scope, storeId, storeType, discountType,
                value == null ? null : new BigDecimal(value), maxDiscount == null ? null : new BigDecimal(maxDiscount),
                new BigDecimal(minOrderSubtotal), null));
        return code;
    }

    /** From now on pricing-service answers {@code valid: false} with this reason for the coupon (null: normal). */
    protected static void setCouponReason(String code, String reason) {
        STUB.coupons.computeIfPresent(code, (c, coupon) -> coupon.withForcedReason(reason));
    }

    // ---------------------------------------------------------------- requests

    protected ResultActions applyCoupon(UUID customerId, String code) throws Exception {
        return mvc.perform(put("/api/cart/coupon").with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code", code))));
    }

    protected ResultActions removeCoupon(UUID customerId) throws Exception {
        return mvc.perform(delete("/api/cart/coupon").with(customer(customerId)));
    }

    protected ResultActions addProduct(UUID customerId, UUID productId, int quantity) throws Exception {
        return postJson("/api/cart/items", customerId,
                Map.of("type", "PRODUCT", "productId", productId, "quantity", quantity));
    }

    protected ResultActions addProductReplacing(UUID customerId, UUID productId, int quantity) throws Exception {
        return postJson("/api/cart/items", customerId,
                Map.of("type", "PRODUCT", "productId", productId, "quantity", quantity, "replaceCart", true));
    }

    protected ResultActions addMenuItem(UUID customerId, UUID restaurantId, UUID itemId, int quantity)
            throws Exception {
        return postJson("/api/cart/items", customerId, Map.of("type", "MENU_ITEM", "restaurantId", restaurantId,
                "menuItemId", itemId, "quantity", quantity));
    }

    protected ResultActions postJson(String path, UUID customerId, Object body) throws Exception {
        return mvc.perform(post(path).with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions setQuantity(UUID customerId, UUID itemId, int quantity) throws Exception {
        return mvc.perform(put("/api/cart/items/{id}", itemId).with(customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":" + quantity + "}"));
    }

    protected ResultActions removeItem(UUID customerId, UUID itemId) throws Exception {
        return mvc.perform(delete("/api/cart/items/{id}", itemId).with(customer(customerId)));
    }

    protected ResultActions clearCart(UUID customerId) throws Exception {
        return mvc.perform(delete("/api/cart").with(customer(customerId)));
    }

    protected ResultActions getCart(UUID customerId) throws Exception {
        return mvc.perform(get("/api/cart").with(customer(customerId)));
    }

    protected ResultActions preview(UUID customerId, UUID addressId) throws Exception {
        var request = get("/api/cart/preview").with(customer(customerId));
        if (addressId != null) {
            request = request.param("addressId", addressId.toString());
        }
        return mvc.perform(request);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected Long ttlSeconds(UUID customerId) {
        return redis.getExpire(CartStore.key(customerId));
    }
}
