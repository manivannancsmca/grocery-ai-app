package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.inventory.service.ReservationService;
import com.grocery.inventory.service.ReservationService.ReservationLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Vendor adjustments: rules, errors, ledger and events (contract §2, §8). */
class StockAdjustmentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ReservationService reservations;

    private ResultActions adjustJson(UUID productId, String body) throws Exception {
        return mvc.perform(post("/api/vendor/inventory/{id}/adjustments", productId).with(vendor(vendorId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions bulk(UUID vendorId, String items) throws Exception {
        return mvc.perform(post("/api/vendor/inventory/adjustments/bulk").with(vendor(vendorId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[" + items + "]}"));
    }

    private static String item(UUID productId, String type, int quantity, String reason) {
        return "{\"productId\":\"%s\",\"type\":\"%s\",\"quantity\":%d,\"reason\":\"%s\"}"
                .formatted(productId, type, quantity, reason);
    }

    private List<String> availability(UUID productId) {
        return events(productId, "ProductAvailabilityChanged").stream()
                .map(e -> e.get("inStock").asBoolean() + "@" + e.get("stockVersion").asLong())
                .toList();
    }

    @Test
    void adjustmentsAlwaysPublishStockAdjustedAndTransitionEventsOnlyOnTransitions() throws Exception {
        UUID productId = newProduct(vendorId, "Tomato");

        JsonNode view = body(adjustJson(productId,
                "{\"type\":\"INCREASE\",\"quantity\":10,\"reason\":\"RESTOCK\",\"note\":\"Morning delivery\"}")
                .andExpect(status().isOk()));
        assertThat(view.get("onHand").asInt()).isEqualTo(10);
        assertThat(view.get("available").asInt()).isEqualTo(10);
        assertThat(view.get("inStock").asBoolean()).isTrue();
        assertThat(view.get("lowStock").asBoolean()).isFalse();
        JsonNode adjusted = events(productId, "StockAdjusted").getLast();
        assertThat(adjusted.get("productId").asString()).isEqualTo(productId.toString());
        assertThat(adjusted.get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(adjusted.get("movementId").asString()).isNotBlank();
        assertThat(adjusted.get("type").asString()).isEqualTo("INCREASE");
        assertThat(adjusted.get("delta").asInt()).isEqualTo(10);
        assertThat(adjusted.get("onHand").asInt()).isEqualTo(10);
        assertThat(adjusted.get("reserved").asInt()).isZero();
        assertThat(adjusted.get("available").asInt()).isEqualTo(10);
        assertThat(adjusted.get("reason").asString()).isEqualTo("RESTOCK");
        assertThat(adjusted.get("actorUserId").asString()).isEqualTo(ownerOf(vendorId).toString());
        assertThat(adjusted.get("stockVersion").asLong()).isEqualTo(1);
        assertThat(availability(productId)).containsExactly("true@1");

        adjust(vendorId, productId, "INCREASE", 5, "RETURN");                        // 15, still in stock
        assertThat(availability(productId)).containsExactly("true@1");
        assertThat(events(productId, "LowStockDetected")).isEmpty();

        adjust(vendorId, productId, "DECREASE", 12, "DAMAGED");                      // 3: crosses threshold 5
        JsonNode low = events(productId, "LowStockDetected").getLast();
        assertThat(low.get("productId").asString()).isEqualTo(productId.toString());
        assertThat(low.get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(low.get("productName").asString()).isEqualTo("Tomato");
        assertThat(low.get("available").asInt()).isEqualTo(3);
        assertThat(low.get("threshold").asInt()).isEqualTo(5);

        adjust(vendorId, productId, "DECREASE", 3, "EXPIRED");                       // 0: out of stock
        assertThat(availability(productId)).containsExactly("true@1", "false@4");
        assertThat(events(productId, "LowStockDetected")).hasSize(1);               // was already low

        adjust(vendorId, productId, "SET", 20, "STOCK_COUNT");                       // back in stock
        assertThat(availability(productId)).containsExactly("true@1", "false@4", "true@5");

        JsonNode afterSet = adjust(vendorId, productId, "SET", 2, "STOCK_COUNT");    // 20 → 2: low again
        assertThat(afterSet.get("lowStock").asBoolean()).isTrue();
        assertThat(events(productId, "LowStockDetected")).hasSize(2);
        assertThat(events(productId, "LowStockDetected").getLast().get("available").asInt()).isEqualTo(2);

        adjust(vendorId, productId, "SET", 2, "STOCK_COUNT");                        // same count: delta 0
        List<JsonNode> stockAdjusted = events(productId, "StockAdjusted");
        assertThat(stockAdjusted).extracting(e -> e.get("stockVersion").asLong())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(stockAdjusted).extracting(e -> e.get("delta").asInt())
                .containsExactly(10, 5, -12, -3, 20, -18, 0);
        assertThat(availability(productId)).hasSize(3);
        assertThat(stockVersion(productId)).isEqualTo(7);

        // The ledger, newest first
        JsonNode movements = body(mvc.perform(get("/api/vendor/inventory/{id}/movements", productId)
                .with(vendor(vendorId))).andExpect(status().isOk()));
        assertThat(movements.get("totalElements").asLong()).isEqualTo(7);
        JsonNode newest = movements.get("content").get(0);
        assertThat(newest.get("type").asString()).isEqualTo("SET");
        assertThat(newest.get("quantityDelta").asInt()).isZero();
        assertThat(newest.get("onHandAfter").asInt()).isEqualTo(2);
        JsonNode oldest = movements.get("content").get(6);
        assertThat(oldest.get("id").asString()).isEqualTo(adjusted.get("movementId").asString());
        assertThat(oldest.get("type").asString()).isEqualTo("INCREASE");
        assertThat(oldest.get("quantityDelta").asInt()).isEqualTo(10);
        assertThat(oldest.get("onHandAfter").asInt()).isEqualTo(10);
        assertThat(oldest.get("reservedAfter").asInt()).isZero();
        assertThat(oldest.get("reason").asString()).isEqualTo("RESTOCK");
        assertThat(oldest.get("note").asString()).isEqualTo("Morning delivery");
        assertThat(oldest.get("reference").isNull()).isTrue();
        assertThat(oldest.get("actorUserId").asString()).isEqualTo(ownerOf(vendorId).toString());
        assertThat(oldest.get("createdAt").isString()).isTrue();
        mvc.perform(get("/api/vendor/inventory/{id}/movements", productId).param("size", "2").param("page", "1")
                        .with(vendor(vendorId)))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].quantityDelta").value(20))
                .andExpect(jsonPath("$.totalPages").value(4));
    }

    @Test
    void invalidAdjustmentsAreRejectedWithFieldErrors() throws Exception {
        UUID productId = newProduct(vendorId, "Potato");
        adjustJson(productId, "{\"type\":\"INCREASE\",\"quantity\":0,\"reason\":\"RESTOCK\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        adjustJson(productId, "{\"type\":\"DECREASE\",\"quantity\":100001,\"reason\":\"DAMAGED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        adjustJson(productId, "{\"type\":\"SET\",\"quantity\":1000001,\"reason\":\"STOCK_COUNT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
        adjustJson(productId, "{\"type\":\"ADD\",\"quantity\":1,\"reason\":\"RESTOCK\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"));
        adjustJson(productId, "{\"type\":\"INCREASE\",\"quantity\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        adjustJson(productId, "{\"type\":\"INCREASE\",\"quantity\":1,\"reason\":\"RESTOCK\",\"note\":\"%s\"}"
                .formatted("x".repeat(301)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        assertThat(stockVersion(productId)).isZero();
        assertThat(movementCount(productId)).isZero();

        adjustJson(productId, "{\"type\":\"SET\",\"quantity\":0,\"reason\":\"STOCK_COUNT\",\"note\":\"  \"}")
                .andExpect(status().isOk());                                          // SET 0 is a valid count
        adjustJson(productId, "{\"type\":\"INCREASE\",\"quantity\":100000,\"reason\":\"RESTOCK\"}")
                .andExpect(status().isOk());
    }

    @Test
    void stockNeverGoesBelowReservedOrAboveTheLimit() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Rice 5 kg", 10);
        reservations.reserve(Ids.newId(), List.of(new ReservationLine(productId, 4)), Duration.ofMinutes(15));
        long version = stockVersion(productId);

        adjustRequest(vendorId, productId, "DECREASE", 7, "DAMAGED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.available").value(6))
                .andExpect(jsonPath("$.reserved").value(4));
        adjustRequest(vendorId, productId, "SET", 3, "STOCK_COUNT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STOCK_BELOW_RESERVED"))
                .andExpect(jsonPath("$.available").value(6))
                .andExpect(jsonPath("$.reserved").value(4));
        assertThat(stockVersion(productId)).isEqualTo(version);
        assertThat(onHand(productId)).isEqualTo(10);

        adjust(vendorId, productId, "SET", 4, "STOCK_COUNT");                        // exactly the reserved stock
        JsonNode view = stock(vendorId, productId);
        assertThat(view.get("available").asInt()).isZero();
        assertThat(view.get("inStock").asBoolean()).isFalse();

        adjust(vendorId, productId, "SET", 1_000_000, "STOCK_COUNT");
        adjustRequest(vendorId, productId, "INCREASE", 1, "RESTOCK")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("STOCK_LIMIT_EXCEEDED"));
        assertThat(onHand(productId)).isEqualTo(1_000_000);
        adjust(vendorId, productId, "DECREASE", 100_000, "CORRECTION");
        assertThat(stock(vendorId, productId).get("available").asInt()).isEqualTo(899_996);
    }

    @Test
    void bulkAdjustmentsAreAllOrNothing() throws Exception {
        UUID apples = newProductWithStock(vendorId, "Apples", 5);
        UUID bananas = newProduct(vendorId, "Bananas");
        UUID cherries = newProduct(vendorId, "Cherries");
        long applesEvents = events(apples, "StockAdjusted").size();

        bulk(vendorId, String.join(",", item(apples, "INCREASE", 5, "RESTOCK"),
                item(bananas, "DECREASE", 1, "DAMAGED"), item(cherries, "SET", 9, "STOCK_COUNT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.productId").value(bananas.toString()))
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.reserved").value(0));
        assertThat(onHand(apples)).isEqualTo(5);                                     // nothing changed
        assertThat(onHand(cherries)).isZero();
        assertThat(events(apples, "StockAdjusted")).hasSize((int) applesEvents);
        assertThat(movementCount(cherries)).isZero();

        JsonNode views = body(bulk(vendorId, String.join(",", item(cherries, "SET", 9, "STOCK_COUNT"),
                item(apples, "INCREASE", 5, "RESTOCK"), item(bananas, "INCREASE", 2, "RESTOCK")))
                .andExpect(status().isOk()));
        assertThat(views).extracting(v -> v.get("productName").asString())
                .containsExactly("Cherries", "Apples", "Bananas");                    // request order
        assertThat(views).extracting(v -> v.get("onHand").asInt()).containsExactly(9, 10, 2);
        assertThat(events(cherries, "StockAdjusted").getLast().get("type").asString()).isEqualTo("SET");
        assertThat(events(bananas, "ProductAvailabilityChanged")).hasSize(1);
        assertThat(events(bananas, "LowStockDetected")).isEmpty();                  // 0 → 2 is not a drop

        // Each product at most once, 1–100 items, type-dependent quantity ranges
        bulk(vendorId, String.join(",", item(apples, "INCREASE", 1, "RESTOCK"), item(apples, "DECREASE", 1, "DAMAGED")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("items[1].productId"));
        bulk(vendorId, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        bulk(vendorId, IntStream.range(0, 101).mapToObj(i -> item(Ids.newId(), "INCREASE", 1, "RESTOCK"))
                .collect(Collectors.joining(",")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        bulk(vendorId, String.join(",", item(apples, "INCREASE", 1, "RESTOCK"), item(bananas, "INCREASE", 0, "RESTOCK")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[1].quantity"));

        // Another vendor's (or an unknown) product: 404 naming the item, nothing changed
        UUID foreign = newProductWithStock(UUID.randomUUID(), "Foreign", 3);
        bulk(vendorId, String.join(",", item(apples, "INCREASE", 1, "RESTOCK"), item(foreign, "DECREASE", 1, "DAMAGED")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.productId").value(foreign.toString()));
        // A limit violation on the last item also rolls back the items before it
        UUID dates = newProduct(vendorId, "Dates");
        adjust(vendorId, dates, "SET", 999_999, "STOCK_COUNT");
        bulk(vendorId, String.join(",", item(apples, "INCREASE", 1, "RESTOCK"),
                item(cherries, "SET", 1_000_000, "STOCK_COUNT"), item(dates, "INCREASE", 2, "RESTOCK")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("STOCK_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.productId").value(dates.toString()));
        assertThat(onHand(dates)).isEqualTo(999_999);
        assertThat(onHand(apples)).isEqualTo(10);
        assertThat(onHand(cherries)).isEqualTo(9);
        assertThat(onHand(bananas)).isEqualTo(2);
        assertThat(onHand(foreign)).isEqualTo(3);
    }

    @Test
    void listsAreSortedByNameSearchableFilterableAndSummarised() throws Exception {
        newProductWithStock(vendorId, "Apple", 20);
        UUID banana = newProductWithStock(vendorId, "banana", 3);                   // low
        newProduct(vendorId, "Cherry");                                             // out (and low)
        UUID dates = newProductWithStock(vendorId, "Dates", 2);
        upsert(dates, vendorId, "Dates", null, "ARCHIVED", 2);                      // hidden
        UUID eggplant = Ids.newId();
        upsert(eggplant, vendorId, "Eggplant", "EGG-1", "UNPUBLISHED", 1);
        adjust(vendorId, eggplant, "INCREASE", 4, "RESTOCK");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/vendor/inventory/{id}/threshold", eggplant).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lowStockThreshold\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStockThreshold").value(0))
                .andExpect(jsonPath("$.lowStock").value(false));
        newProductWithStock(UUID.randomUUID(), "Apple of another vendor", 5);

        assertThat(names(null, null)).containsExactly("Apple", "banana", "Cherry", "Eggplant");
        assertThat(names(null, "IN_STOCK")).containsExactly("Apple", "banana", "Eggplant");
        assertThat(names(null, "OUT_OF_STOCK")).containsExactly("Cherry");
        assertThat(names(null, "LOW_STOCK")).containsExactly("banana", "Cherry");
        assertThat(names("an", null)).containsExactly("banana", "Eggplant");
        assertThat(names("APP", null)).containsExactly("Apple");
        assertThat(names("egg-1", null)).containsExactly("Eggplant");               // SKU equals
        assertThat(names("EGG", "LOW_STOCK")).isEmpty();
        assertThat(names("%", null)).isEmpty();                                     // wildcards are literal
        mvc.perform(get("/api/vendor/inventory").param("filter", "ALMOST_OUT").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/inventory").param("size", "2").param("page", "1").with(vendor(vendorId)))
                .andExpect(jsonPath("$.content[0].productName").value("Cherry"))
                .andExpect(jsonPath("$.content[1].productName").value("Eggplant"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2));

        mvc.perform(get("/api/vendor/inventory/summary").with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(4))
                .andExpect(jsonPath("$.inStock").value(3))
                .andExpect(jsonPath("$.lowStock").value(1))                         // banana; Cherry is out
                .andExpect(jsonPath("$.outOfStock").value(1));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/vendor/inventory/{id}/threshold", banana).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lowStockThreshold\":100001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lowStockThreshold"));
        long version = stockVersion(banana);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/vendor/inventory/{id}/threshold", banana).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lowStockThreshold\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStock").value(false));
        assertThat(stockVersion(banana)).isEqualTo(version);                       // not a stock change
        mvc.perform(get("/api/vendor/inventory/summary").with(vendor(vendorId)))
                .andExpect(jsonPath("$.lowStock").value(0));
        mvc.perform(get("/api/vendor/inventory/summary").with(vendor(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(0))
                .andExpect(jsonPath("$.inStock").value(0));
    }

    private List<String> names(String q, String filter) throws Exception {
        var request = get("/api/vendor/inventory").with(vendor(vendorId));
        if (q != null) {
            request.param("q", q);
        }
        if (filter != null) {
            request.param("filter", filter);
        }
        JsonNode page = body(mvc.perform(request).andExpect(status().isOk()));
        return StreamSupport.stream(page.get("content").spliterator(), false)
                .map(v -> v.get("productName").asString()).toList();
    }
}
