package com.grocery.pricing;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** catalog.events → product_ref projection (the listener is called directly). */
class CatalogProjectionIntegrationTest extends AbstractIntegrationTest {

    private final UUID vendorId = UUID.randomUUID();

    private Map<String, Object> productRef(UUID productId) {
        return jdbc().sql("""
                        SELECT name, sku, status, product_version, vendor_name, unit_label FROM product_ref
                        WHERE product_id = UUID_TO_BIN(?)""")
                .param(productId.toString()).query().singleRow();
    }

    @Test
    void createsTheProductAndAppliesOnlyNewerSnapshots() throws Exception {
        UUID productId = Ids.newId();
        upsertProduct(productId, vendorId, "Tomato", "TOM-500", "DRAFT", 3);
        assertThat(productRef(productId)).containsEntry("name", "Tomato").containsEntry("sku", "TOM-500")
                .containsEntry("status", "DRAFT").containsEntry("product_version", 3L)
                .containsEntry("vendor_name", "Green Basket").containsEntry("unit_label", "500 g");

        upsertProduct(productId, vendorId, "Tomato Hybrid", "TOM-500", "PUBLISHED", 5);
        upsertProduct(productId, vendorId, "Stale Tomato", "OLD", "UNPUBLISHED", 4);     // older: ignored
        upsertProduct(productId, vendorId, "Replayed Tomato", "OLD", "UNPUBLISHED", 5);  // same version: ignored
        assertThat(productRef(productId)).containsEntry("name", "Tomato Hybrid").containsEntry("status", "PUBLISHED")
                .containsEntry("product_version", 5L);

        mvc.perform(get("/api/vendor/prices/{id}", productId).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("Tomato Hybrid"))
                .andExpect(jsonPath("$.unitLabel").value("500 g"))
                .andExpect(jsonPath("$.sku").value("TOM-500"))
                .andExpect(jsonPath("$.productStatus").value("PUBLISHED"))
                .andExpect(jsonPath("$.price").isEmpty())
                .andExpect(jsonPath("$.pendingRequest").isEmpty())
                .andExpect(jsonPath("$.approvalThresholdPercent").value(20))
                .andExpect(jsonPath("$.outcome").doesNotExist());
    }

    @Test
    void aRedeliveredEventIsProcessedOnce() {
        UUID productId = Ids.newId();
        String eventId = Ids.newId().toString();
        catalogEvents.on(productUpserted(eventId, productId, vendorId, "Apples", null, "PUBLISHED", 1));
        // Same event id again, even with (artificially) different content: the guard skips it
        catalogEvents.on(productUpserted(eventId, productId, vendorId, "Bananas", null, "PUBLISHED", 2));

        assertThat(productRef(productId)).containsEntry("name", "Apples").containsEntry("product_version", 1L);
        Long marks = jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = ?")
                .param(eventId).param("pricing-service.catalog-events").query(Long.class).single();
        assertThat(marks).isEqualTo(1);
    }

    @Test
    void archivingAProductCancelsItsPendingRequestAndFreezesThePrice() throws Exception {
        UUID productId = pricedProduct(vendorId, "Paneer", "100.00", "90.00");
        String requestId = pendingRequest(vendorId, productId, "100.00", "50.00");

        upsertProduct(productId, vendorId, "Paneer", null, "ARCHIVED", 2);

        JsonNode request = body(mvc.perform(get("/api/admin/price-change-requests/{id}", requestId)
                .with(as(admin, "SUPPORT"))).andExpect(status().isOk()));
        assertThat(request.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(request.get("decisionReason").asString()).isEqualTo("Product archived");
        assertThat(request.get("decidedBy").isNull()).isTrue();
        assertThat(request.get("decidedAt").isNull()).isFalse();

        List<JsonNode> cancelled = events(productId, "PriceChangeCancelled");
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.getFirst().get("requestId").asString()).isEqualTo(requestId);
        assertThat(cancelled.getFirst().get("productId").asString()).isEqualTo(productId.toString());
        assertThat(cancelled.getFirst().get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(cancelled.getFirst().get("cancelledBy").isNull()).isTrue();
        assertThat(cancelled.getFirst().get("reason").asString()).isEqualTo("Product archived");

        // Read-only from now on, left out of lists, and a later (re)delivery cancels nothing more
        putPrice(vendorId, productId, "100.00", "95.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_ARCHIVED"));
        mvc.perform(get("/api/vendor/prices").param("q", "Paneer").with(vendor(vendorId)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/vendor/prices/{id}", productId).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productStatus").value("ARCHIVED"))
                .andExpect(jsonPath("$.pendingRequest").isEmpty());
        upsertProduct(productId, vendorId, "Paneer", null, "ARCHIVED", 3);
        assertThat(events(productId, "PriceChangeCancelled")).hasSize(1);
        assertThat(priceChangedEvents(productId)).hasSize(1);
    }

    @Test
    void unknownProductsAreNotFound() throws Exception {
        UUID unknown = Ids.newId();
        putPrice(vendorId, unknown, "10.00", "9.00")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/vendor/prices/{id}", unknown).with(vendor(vendorId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void otherEventTypesAreIgnoredAndBrokenSnapshotsAreRejected() {
        UUID productId = Ids.newId();
        ConsumerRecord<String, String> category = productUpserted(Ids.newId().toString(), productId, vendorId,
                "Ignored", null, "PUBLISHED", 1);
        category.headers().remove("eventType");
        category.headers().add("eventType", "CategoryChanged".getBytes(StandardCharsets.UTF_8));
        catalogEvents.on(category);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM product_ref WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single()).isZero();

        assertThatThrownBy(() -> catalogEvents.on(productUpserted(Ids.newId().toString(), productId, vendorId,
                "Bad", null, "SOLD_OUT", 1))).isInstanceOf(InvalidEventException.class);
        ConsumerRecord<String, String> noId = new ConsumerRecord<>("catalog.events", 0, 0L, "k",
                "{\"name\":\"No id\",\"status\":\"DRAFT\",\"productVersion\":1}");
        noId.headers().add("eventId", Ids.newId().toString().getBytes(StandardCharsets.UTF_8));
        noId.headers().add("eventType", "ProductUpserted".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> catalogEvents.on(noId)).isInstanceOf(InvalidEventException.class);
    }
}
