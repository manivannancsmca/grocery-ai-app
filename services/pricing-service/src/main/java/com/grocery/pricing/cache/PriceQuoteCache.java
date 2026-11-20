package com.grocery.pricing.cache;

import com.grocery.pricing.config.PricingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Redis read-through cache behind the price quote: {@code price:v1:<productId>} → {@link CachedPrice} JSON,
 * expiring after {@code grocery.pricing.quote-cache-ttl}.
 *
 * <h2>Only newer entries win</h2>
 * Every write — filling a miss after reading MySQL, and the update after a price change commits — goes through
 * one atomic compare-and-set Lua script ({@code redis/price-put-if-newer.lua}) that replaces an entry only by a
 * higher {@code priceVersion} (or, for the same price, a higher {@code productVersion}: the archive tombstone).
 * A reader that loaded MySQL just before a change committed therefore can never overwrite the newer price,
 * whatever order the two writes reach Redis in.
 *
 * <h2>Redis is optional</h2>
 * No Redis error or timeout ever fails a request: reads fall back to MySQL and writes are skipped. The first
 * failure is logged as a warning (then debug only) and quote reads bypass Redis for {@link #RETRY_AFTER}, so an
 * unreachable Redis costs at most one timeout every few seconds; the first successful call logs the recovery.
 * After-commit writes are always attempted. If one fails anyway, the old entry can be served until it expires
 * (TTL) — the quote's {@code priceVersion} lets callers detect that.
 */
@Component
public class PriceQuoteCache {

    public static final String KEY_PREFIX = "price:v1:";
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(PriceQuoteCache.class);
    private static final RedisScript<Long> PUT_IF_NEWER =
            RedisScript.of(new ClassPathResource("redis/price-put-if-newer.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final String ttlMillis;
    private final AtomicBoolean degraded = new AtomicBoolean();
    private volatile long bypassUntilNanos;

    public PriceQuoteCache(StringRedisTemplate redis, JsonMapper jsonMapper, PricingProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.ttlMillis = Long.toString(properties.quoteCacheTtl().toMillis());
    }

    public static String key(UUID productId) {
        return KEY_PREFIX + productId;
    }

    /** The cached entries among {@code productIds} (one MGET). Empty when Redis is unavailable. */
    public Map<UUID, CachedPrice> getAll(List<UUID> productIds) {
        if (productIds.isEmpty() || bypassed()) {
            return Map.of();
        }
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(productIds.stream().map(PriceQuoteCache::key).toList());
            recovered();
        } catch (RuntimeException e) {
            failed("read", e);
            return Map.of();
        }
        Map<UUID, CachedPrice> found = new HashMap<>();
        for (int i = 0; values != null && i < productIds.size(); i++) {
            CachedPrice entry = parse(values.get(i));
            if (entry != null && productIds.get(i).equals(entry.productId())) {
                found.put(entry.productId(), entry);
            }
        }
        return found;
    }

    /** Fills misses read from MySQL (compare-and-set). Skipped while Redis is known to be unavailable. */
    public void putAllIfNewer(Collection<CachedPrice> entries) {
        if (!entries.isEmpty() && !bypassed()) {
            write(entries);
        }
    }

    /**
     * Writes the entry (compare-and-set) after the surrounding transaction commits, so a change that rolls back
     * never reaches the cache. Without a transaction it is written at once. Never throws.
     */
    public void putAfterCommit(CachedPrice entry) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(List.of(entry));
                }
            });
        } else {
            write(List.of(entry));
        }
    }

    private void write(Collection<CachedPrice> entries) {
        try {
            List<String> keys = new ArrayList<>(entries.size());
            List<String> args = new ArrayList<>(1 + entries.size() * 3);
            args.add(ttlMillis);
            for (CachedPrice entry : entries) {
                keys.add(key(entry.productId()));
                args.add(jsonMapper.writeValueAsString(entry));
                args.add(Long.toString(entry.priceVersion()));
                args.add(Long.toString(entry.productVersion()));
            }
            redis.execute(PUT_IF_NEWER, keys, args.toArray());
            recovered();
        } catch (RuntimeException e) {
            failed("write", e);
        }
    }

    private CachedPrice parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return jsonMapper.readValue(value, CachedPrice.class);
        } catch (JacksonException e) {
            log.debug("Ignoring unreadable quote cache entry: {}", e.getMessage());
            return null;     // treated as a miss; the fill replaces it
        }
    }

    private boolean bypassed() {
        return degraded.get() && System.nanoTime() - bypassUntilNanos < 0;
    }

    private void failed(String operation, RuntimeException e) {
        bypassUntilNanos = System.nanoTime() + RETRY_AFTER.toNanos();
        if (degraded.compareAndSet(false, true)) {
            log.warn("Redis quote cache unavailable ({} failed: {}); quotes read MySQL until it is back",
                    operation, e.getMessage());
        } else {
            log.debug("Redis quote cache still unavailable ({} failed: {})", operation, e.getMessage());
        }
    }

    private void recovered() {
        if (degraded.compareAndSet(true, false)) {
            log.info("Redis quote cache is available again");
        }
    }
}
