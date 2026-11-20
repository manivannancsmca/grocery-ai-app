package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.inventory.service.ReservationFailedException;
import com.grocery.inventory.service.ReservationService;
import com.grocery.inventory.service.ReservationService.ReservationLine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** catalog.events → product_ref + stock row (the listener is called directly, as Kafka would). */
class CatalogProjectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ReservationService reservations;

    private long processedCount(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = 'inventory-service.catalog-events'")
                .param(eventId).query(Long.class).single();
    }

    private boolean known(UUID productId) {
        return jdbc().sql("SELECT COUNT(*) FROM product_ref WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single() == 1;
    }

    @Test
    void productUpsertedCreatesTheProductCopyAndAnEmptyStockRow() throws Exception {
        UUID productId = Ids.newId();
        String eventId = Ids.newId().toString();
        catalogListener.on(productUpserted(eventId, productId, vendorId, "Tomato", "TOM-500", "DRAFT", 1));

        mvc.perform(get("/api/vendor/inventory/{id}", productId).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.productName").value("Tomato"))
                .andExpect(jsonPath("$.unitLabel").value("500 g"))
                .andExpect(jsonPath("$.sku").value("TOM-500"))
                .andExpect(jsonPath("$.productStatus").value("DRAFT"))
                .andExpect(jsonPath("$.onHand").value(0))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.lowStockThreshold").value(5))
                .andExpect(jsonPath("$.lowStock").value(true))
                .andExpect(jsonPath("$.inStock").value(false))
                .andExpect(jsonPath("$.updatedAt").isString());
        assertThat(stockVersion(productId)).isZero();
        assertThat(processedCount(eventId)).isEqualTo(1);
        // Tracking a product changes no stock: nothing is published
        assertThat(jdbc().sql("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?").param(productId.toString())
                .query(Long.class).single()).isZero();
    }

    @Test
    void onlyNewerProductVersionsUpdateTheCopy() throws Exception {
        UUID productId = Ids.newId();
        upsert(productId, vendorId, "Onion", "ONI-1", "DRAFT", 1);
        adjust(vendorId, productId, "INCREASE", 7, "RESTOCK");
        upsert(productId, vendorId, "Red Onion", "ONI-2", "PUBLISHED", 3);
        upsert(productId, vendorId, "Old Onion", "ONI-OLD", "UNPUBLISHED", 2);     // stale: arrives late
        upsert(productId, vendorId, "Old Onion", "ONI-OLD", "UNPUBLISHED", 3);     // equal: also ignored

        JsonNode view = stock(vendorId, productId);
        assertThat(view.get("productName").asString()).isEqualTo("Red Onion");
        assertThat(view.get("sku").asString()).isEqualTo("ONI-2");
        assertThat(view.get("productStatus").asString()).isEqualTo("PUBLISHED");
        assertThat(view.get("onHand").asInt()).isEqualTo(7);                         // stock untouched by snapshots
        assertThat(jdbc().sql("SELECT product_version FROM product_ref WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void aRedeliveredEventIsProcessedOnce() throws Exception {
        UUID productId = Ids.newId();
        String eventId = Ids.newId().toString();
        catalogListener.on(productUpserted(eventId, productId, vendorId, "Garlic", null, "PUBLISHED", 1));
        // Same event id again (even with other content): skipped by the processed_event guard
        catalogListener.on(productUpserted(eventId, productId, vendorId, "Changed", null, "PUBLISHED", 2));

        assertThat(stock(vendorId, productId).get("productName").asString()).isEqualTo("Garlic");
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM stock_item WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void otherEventTypesAreIgnoredAndBrokenEventsRejected() {
        UUID productId = Ids.newId();
        catalogListener.on(record("CategoryChanged", Ids.newId().toString(), productId.toString(),
                "{\"categoryId\":\"%s\",\"name\":\"Fruits\",\"categoryVersion\":1}".formatted(productId)));
        assertThat(known(productId)).isFalse();

        assertThatThrownBy(() -> catalogListener.on(record("ProductUpserted", Ids.newId().toString(),
                productId.toString(), "{\"productId\":\"%s\",\"name\":\"No vendor\"}".formatted(productId))))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> catalogListener.on(productUpserted(Ids.newId().toString(), productId, vendorId,
                "Odd", null, "DELETED", 1)))
                .isInstanceOf(InvalidEventException.class);
        assertThat(known(productId)).isFalse();
    }

    @Test
    void unknownProductsAreNotFound() throws Exception {
        UUID unknown = Ids.newId();
        mvc.perform(get("/api/vendor/inventory/{id}", unknown).with(vendor(vendorId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        adjustRequest(vendorId, unknown, "INCREASE", 1, "RESTOCK")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("few seconds")));
    }

    @Test
    void archivedProductsAreReadOnlyAndLeftOutOfListsAndSummary() throws Exception {
        UUID kept = newProductWithStock(vendorId, "Apples", 4);
        UUID archived = newProductWithStock(vendorId, "Plums", 6);
        long versionBefore = stockVersion(archived);
        upsert(archived, vendorId, "Plums", null, "ARCHIVED", 2);

        adjustRequest(vendorId, archived, "INCREASE", 1, "RESTOCK")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_ARCHIVED"));
        adjustRequest(vendorId, archived, "SET", 0, "STOCK_COUNT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_ARCHIVED"));
        mvc.perform(put("/api/vendor/inventory/{id}/threshold", archived).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lowStockThreshold\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_ARCHIVED"));
        mvc.perform(post("/api/vendor/inventory/adjustments/bulk").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"%s","type":"INCREASE","quantity":1,"reason":"RESTOCK"},
                                          {"productId":"%s","type":"DECREASE","quantity":1,"reason":"DAMAGED"}]}"""
                                .formatted(kept, archived)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_ARCHIVED"))
                .andExpect(jsonPath("$.productId").value(archived.toString()));
        assertThatThrownBy(() -> reservations.reserve(Ids.newId(), List.of(new ReservationLine(archived, 1)),
                Duration.ofMinutes(15)))
                .isInstanceOfSatisfying(ReservationFailedException.class, e ->
                        assertThat(e.unavailableItems()).singleElement().satisfies(item -> {
                            assertThat(item.productId()).isEqualTo(archived);
                            assertThat(item.available()).isZero();
                        }));
        assertThat(stockVersion(archived)).isEqualTo(versionBefore);
        assertThat(onHand(kept)).isEqualTo(4);                                       // bulk rolled back

        // Kept and readable on its own, but not listed or counted
        assertThat(stock(vendorId, archived).get("productStatus").asString()).isEqualTo("ARCHIVED");
        JsonNode list = body(mvc.perform(get("/api/vendor/inventory").with(vendor(vendorId))).andExpect(status().isOk()));
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        assertThat(list.get("content").get(0).get("productId").asString()).isEqualTo(kept.toString());
        mvc.perform(get("/api/vendor/inventory/summary").with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(1))
                .andExpect(jsonPath("$.inStock").value(1));
    }
}
