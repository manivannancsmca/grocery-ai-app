package com.grocery.cart.store;

import com.grocery.cart.config.CartProperties;
import com.grocery.cart.model.StoredCart;
import com.grocery.cart.web.CartErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * The carts in Redis: one hash per customer, {@code cart:v1:<customerId>} with the fields {@code version} (an
 * integer that goes up with every write) and {@code data} (the {@link StoredCart} as JSON), expiring 30 days
 * after the last write ({@code grocery.cart.ttl}).
 *
 * <p>Every write is an atomic compare-and-set on {@code version} ({@code redis/cart-compare-and-set.lua}): it
 * succeeds only if nobody wrote since the caller read the cart. Redis being down or slow is not hidden — the cart
 * lives nowhere else — every Redis error becomes 503 {@code CART_UNAVAILABLE}.
 */
@Component
public class CartStore {

    public static final String KEY_PREFIX = "cart:v1:";
    static final String VERSION = "version";
    static final String DATA = "data";

    private static final Logger log = LoggerFactory.getLogger(CartStore.class);
    private static final RedisScript<Long> COMPARE_AND_SET =
            RedisScript.of(new ClassPathResource("redis/cart-compare-and-set.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final String ttlMillis;

    public CartStore(StringRedisTemplate redis, JsonMapper jsonMapper, CartProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttlMillis = Long.toString(properties.getTtl().toMillis());
    }

    public static String key(UUID customerId) {
        return KEY_PREFIX + customerId;
    }

    /** The customer's cart and its version (version 0 and an empty cart when there is none). */
    public VersionedCart load(UUID customerId) {
        List<Object> values;
        try {
            values = redis.opsForHash().multiGet(key(customerId), List.of(VERSION, DATA));
        } catch (RuntimeException e) {
            throw unavailable("read", e);
        }
        String version = values == null || values.isEmpty() ? null : (String) values.get(0);
        String data = values == null || values.size() < 2 ? null : (String) values.get(1);
        return new VersionedCart(parseVersion(version), parse(customerId, data));
    }

    /**
     * Writes {@code cart} if the stored version is still {@code expectedVersion} (and refreshes the TTL).
     *
     * @return false when someone else wrote the cart first
     */
    public boolean compareAndSet(UUID customerId, long expectedVersion, StoredCart cart) {
        String json = jsonMapper.writeValueAsString(cart);
        Long result;
        try {
            result = redis.execute(COMPARE_AND_SET, List.of(key(customerId)),
                    Long.toString(expectedVersion), json, ttlMillis);
        } catch (RuntimeException e) {
            throw unavailable("write", e);
        }
        return result != null && result > 0;
    }

    private StoredCart parse(UUID customerId, String data) {
        if (data == null || data.isBlank()) {
            return StoredCart.EMPTY;
        }
        try {
            return jsonMapper.readValue(data, StoredCart.class);
        } catch (JacksonException e) {
            // Never expected; the next write replaces it (its version is still compared).
            log.error("Unreadable cart of customer {} treated as empty: {}", customerId, e.getMessage());
            return StoredCart.EMPTY;
        }
    }

    private static long parseVersion(String version) {
        if (version == null) {
            return 0;
        }
        try {
            return Long.parseLong(version.trim());
        } catch (NumberFormatException e) {
            return 0;       // the Lua script reads an unreadable version as 0 too
        }
    }

    private static RuntimeException unavailable(String operation, RuntimeException e) {
        log.warn("Redis cart {} failed: {}", operation, e.toString());
        return CartErrors.cartUnavailable(e);
    }

    /** A cart as read, with the version a write must compare against. */
    public record VersionedCart(long version, StoredCart cart) {
    }
}
