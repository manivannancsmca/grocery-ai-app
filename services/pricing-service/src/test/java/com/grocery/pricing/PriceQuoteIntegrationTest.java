package com.grocery.pricing;

import com.grocery.common.id.Ids;
import com.grocery.pricing.cache.CachedPrice;
import com.grocery.pricing.cache.PriceQuoteCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /internal/prices/quote and the Redis read-through cache (price:v1:<productId>, compare-and-set by version). */
class PriceQuoteIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    PriceQuoteCache cache;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final UUID vendorId = UUID.randomUUID();
    private final UUID caller = UUID.randomUUID();

    private ResultActions quote(UUID... productIds) throws Exception {
        String ids = Arrays.stream(productIds).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return mvc.perform(post("/internal/prices/quote").with(as(caller, "CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[" + ids + "]}"));
    }

    private JsonNode cached(UUID productId) {
        String value = redis.opsForValue().get(PriceQuoteCache.key(productId));
        return value == null ? null : json.readTree(value);
    }

    private static CachedPrice entry(UUID productId, UUID vendorId, String sellingPrice, long priceVersion,
                                     long productVersion) {
        return new CachedPrice(productId, vendorId, new BigDecimal("100.00"), new BigDecimal(sellingPrice), "INR",
                priceVersion, productVersion, false);
    }

    @Test
    void aMissIsReadFromMysqlAndFillsTheCacheThenHitsAreServedFromIt() throws Exception {
        UUID priced = pricedProduct(vendorId, "Atta", "60.00", "49.00");
        UUID unpriced = product(vendorId, "Besan");
        UUID unknown = Ids.newId();
        redis.delete(PriceQuoteCache.key(priced));        // the price change already filled it; start cold

        quote(priced, unpriced, unknown, priced)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(priced.toString()))
                .andExpect(jsonPath("$.items[0].vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.items[0].mrp").value(60.00))
                .andExpect(jsonPath("$.items[0].sellingPrice").value(49.00))
                .andExpect(jsonPath("$.items[0].currency").value("INR"))
                .andExpect(jsonPath("$.items[0].priceVersion").value(1))
                .andExpect(jsonPath("$.missing.length()").value(2))
                .andExpect(jsonPath("$.missing[0]").value(unpriced.toString()))
                .andExpect(jsonPath("$.missing[1]").value(unknown.toString()));

        JsonNode filled = cached(priced);
        assertThat(filled).isNotNull();
        assertThat(filled.get("priceVersion").asLong()).isEqualTo(1);
        assertThat(filled.get("sellingPrice").decimalValue()).isEqualByComparingTo("49.00");
        assertThat(cached(unpriced)).isNull();
        Long ttl = redis.getExpire(PriceQuoteCache.key(priced));
        assertThat(ttl).isBetween(500L, 600L);                              // 10 minutes

        // A hit is served from Redis: a marked entry (same version) shows up in the quote
        redis.opsForValue().set(PriceQuoteCache.key(priced),
                json.writeValueAsString(entry(priced, vendorId, "48.88", 1, 1)));
        quote(priced).andExpect(jsonPath("$.items[0].sellingPrice").value(48.88));
    }

    @Test
    void aLowerVersionNeverReplacesAHigherOne() throws Exception {
        UUID productId = Ids.newId();
        String key = PriceQuoteCache.key(productId);

        cache.putAllIfNewer(List.of(entry(productId, vendorId, "50.00", 3, 1)));
        cache.putAllIfNewer(List.of(entry(productId, vendorId, "40.00", 2, 9)));      // older price: ignored
        cache.putAllIfNewer(List.of(entry(productId, vendorId, "41.00", 3, 1)));      // same versions: ignored
        assertThat(cached(productId).get("sellingPrice").decimalValue()).isEqualByComparingTo("50.00");

        cache.putAfterCommit(entry(productId, vendorId, "55.00", 4, 1));              // newer price wins
        assertThat(cached(productId).get("priceVersion").asLong()).isEqualTo(4);
        cache.putAllIfNewer(List.of(new CachedPrice(productId, vendorId, new BigDecimal("100.00"),
                new BigDecimal("55.00"), "INR", 4, 2, true)));                        // same price, archived
        assertThat(cached(productId).get("archived").asBoolean()).isTrue();
        cache.putAllIfNewer(List.of(entry(productId, vendorId, "55.00", 4, 1)));      // stale reader: ignored
        assertThat(cached(productId).get("archived").asBoolean()).isTrue();

        redis.opsForValue().set(key, "not json");                                     // unreadable: replaced
        cache.putAllIfNewer(List.of(entry(productId, vendorId, "60.00", 1, 1)));
        assertThat(cached(productId).get("priceVersion").asLong()).isEqualTo(1);
    }

    @Test
    void aSlowReaderCannotPutAnOldPriceBackAfterAChange() throws Exception {
        UUID productId = pricedProduct(vendorId, "Sugar", "50.00", "45.00");
        putPrice(vendorId, productId, "50.00", "47.00").andExpect(status().isOk());     // version 2

        // A quote that read MySQL before the change committed writes version 1 late: ignored
        cache.putAllIfNewer(List.of(entry(productId, vendorId, "45.00", 1, 1)));
        quote(productId)
                .andExpect(jsonPath("$.items[0].sellingPrice").value(47.00))
                .andExpect(jsonPath("$.items[0].priceVersion").value(2));
    }

    @Test
    void aPriceChangeUpdatesTheCacheAfterCommitOnly() throws Exception {
        UUID productId = pricedProduct(vendorId, "Salt", "30.00", "25.00");
        quote(productId).andExpect(jsonPath("$.items[0].priceVersion").value(1));
        assertThat(cached(productId).get("priceVersion").asLong()).isEqualTo(1);

        putPrice(vendorId, productId, "30.00", "27.00").andExpect(status().isOk());
        JsonNode updated = cached(productId);
        assertThat(updated.get("priceVersion").asLong()).isEqualTo(2);
        assertThat(updated.get("sellingPrice").decimalValue()).isEqualByComparingTo("27.00");
        quote(productId).andExpect(jsonPath("$.items[0].sellingPrice").value(27.00));

        // An approval updates it too; a pending request does not
        String requestId = pendingRequest(vendorId, productId, "30.00", "10.00");
        assertThat(cached(productId).get("priceVersion").asLong()).isEqualTo(2);
        approve(requestId).andExpect(status().isOk());
        assertThat(cached(productId).get("priceVersion").asLong()).isEqualTo(3);
        assertThat(cached(productId).get("sellingPrice").decimalValue()).isEqualByComparingTo("10.00");

        // A write registered in a transaction that rolls back never reaches Redis
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            cache.putAfterCommit(entry(productId, vendorId, "1.00", 99, 99));
            status.setRollbackOnly();
        });
        assertThat(cached(productId).get("priceVersion").asLong()).isEqualTo(3);
    }

    @Test
    void archivedProductsAreQuotedAsMissing() throws Exception {
        UUID productId = pricedProduct(vendorId, "Jaggery", "80.00", "70.00");
        quote(productId).andExpect(jsonPath("$.items.length()").value(1));

        upsertProduct(productId, vendorId, "Jaggery", null, "ARCHIVED", 2);
        assertThat(cached(productId).get("archived").asBoolean()).isTrue();
        quote(productId)
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.missing[0]").value(productId.toString()));

        redis.delete(PriceQuoteCache.key(productId));                       // and straight from MySQL
        quote(productId).andExpect(jsonPath("$.missing[0]").value(productId.toString()));
    }

    @Test
    void theQuoteIsAnInternalEndpointForAnyAuthenticatedCaller() throws Exception {
        UUID productId = pricedProduct(vendorId, "Rava", "40.00", "36.00");
        String body = "{\"productIds\":[\"" + productId + "\"]}";

        // Zero trust: /internal/** still needs a valid JWT
        mvc.perform(post("/internal/prices/quote").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        // ...but no particular role: a plain customer token (forwarded by cart-service) is accepted
        mvc.perform(post("/internal/prices/quote").with(as(UUID.randomUUID(), "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.items[0].sellingPrice").value(36.00));

        // Phase 4's path is gone
        mvc.perform(post("/api/prices/quote").with(as(UUID.randomUUID(), "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRequestNeedsOneToOneHundredProductsAndAnAuthenticatedCaller() throws Exception {
        mvc.perform(post("/internal/prices/quote").with(as(caller, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/internal/prices/quote").with(as(caller, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        UUID[] tooMany = IntStream.range(0, 101).mapToObj(i -> Ids.newId()).toArray(UUID[]::new);
        quote(tooMany).andExpect(status().isBadRequest());
        UUID[] hundred = Arrays.copyOf(tooMany, 100);
        quote(hundred).andExpect(status().isOk()).andExpect(jsonPath("$.missing.length()").value(100));

        mvc.perform(post("/internal/prices/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[\"" + Ids.newId() + "\"]}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/prices/quote").with(as(UUID.randomUUID(), "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[\"" + Ids.newId() + "\"]}"))
                .andExpect(status().isOk());
    }
}
