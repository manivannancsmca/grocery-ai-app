package com.grocery.vendor;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.InvalidEventException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** catalog.events → product_ref (consumer vendor-service.catalog-events), the way pricing / inventory keep theirs. */
class ProductProjectionIntegrationTest extends PurchasingTestSupport {

    private Map<String, Object> row(UUID productId) {
        return jdbc().sql("SELECT BIN_TO_UUID(vendor_id) AS vendor_id, name, unit_label, sku, status, product_version "
                        + "FROM product_ref WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query().singleRow();
    }

    private long processedCatalog(String eventId) {
        return jdbc().sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? "
                        + "AND consumer = 'vendor-service.catalog-events'")
                .param(eventId).query(Long.class).single();
    }

    @Test
    void productUpsertedCreatesAndUpdatesTheCopyOnlyWithNewerVersions() {
        UUID vendorId = UUID.randomUUID();
        UUID productId = Ids.newId();
        String first = newEventId();
        catalogListener.on(productUpserted(first, productPayload(productId, vendorId, "Rice", "DRAFT", 1)));

        assertThat(row(productId)).containsEntry("vendor_id", vendorId.toString()).containsEntry("name", "Rice")
                .containsEntry("unit_label", "1 kg").containsEntry("status", "DRAFT");
        assertThat(((Number) row(productId).get("product_version")).longValue()).isEqualTo(1);
        assertThat(processedCatalog(first)).isEqualTo(1);

        upsert(productId, vendorId, "Basmati Rice", "PUBLISHED", 3);
        assertThat(row(productId)).containsEntry("name", "Basmati Rice").containsEntry("status", "PUBLISHED");

        // stale (version 2 < 3) and equal versions never win
        upsert(productId, vendorId, "Stale Rice", "UNPUBLISHED", 2);
        upsert(productId, vendorId, "Same Rice", "UNPUBLISHED", 3);
        assertThat(row(productId)).containsEntry("name", "Basmati Rice").containsEntry("status", "PUBLISHED");

        // a redelivery of an event already handled changes nothing, even with a newer payload
        catalogListener.on(productUpserted(first, productPayload(productId, vendorId, "Replayed", "ARCHIVED", 9)));
        assertThat(row(productId)).containsEntry("name", "Basmati Rice");

        upsert(productId, vendorId, "Basmati Rice", "ARCHIVED", 4);
        assertThat(row(productId)).containsEntry("status", "ARCHIVED");
    }

    @Test
    void otherEventTypesAreIgnoredAndBrokenSnapshotsAreRejected() {
        String categoryEvent = newEventId();
        catalogListener.on(record("catalog.events", "Category", "CategoryChanged", categoryEvent,
                UUID.randomUUID().toString(), "{\"categoryId\":\"" + UUID.randomUUID() + "\"}"));
        assertThat(processedCatalog(categoryEvent)).isZero();

        UUID productId = Ids.newId();
        Map<String, Object> noVersion = productPayload(productId, UUID.randomUUID(), "Rice", "PUBLISHED", 1);
        noVersion.remove("productVersion");
        assertThatThrownBy(() -> catalogListener.on(productUpserted(newEventId(), noVersion)))
                .isInstanceOf(InvalidEventException.class);
        Map<String, Object> badStatus = productPayload(productId, UUID.randomUUID(), "Rice", "DELETED", 1);
        assertThatThrownBy(() -> catalogListener.on(productUpserted(newEventId(), badStatus)))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("DELETED");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM product_ref WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single()).isZero();
    }
}
