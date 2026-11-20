package com.grocery.catalogquery;

import com.grocery.catalogquery.TestEvents.Cat;
import com.grocery.common.observability.ConsumedEvents;
import com.grocery.common.observability.GroceryEventMetrics.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.grocery.catalogquery.TestEvents.category;
import static com.grocery.catalogquery.TestEvents.price;
import static com.grocery.catalogquery.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 (grocery-common 0.9.0): this service has no {@code processed_event} table — the search documents are
 * versioned instead — so a stale or duplicate event, which Elasticsearch skips, is reported with
 * {@code ConsumedEvents.markDuplicate()} and counted as {@code grocery_events_consumed_total{outcome="DUPLICATE"}};
 * an event that changed a document stays {@code APPLIED}. The record is opened and finished here exactly as
 * grocery-common's record interceptor does around the listener.
 */
class DuplicateEventsIntegrationTest extends AbstractIntegrationTest {

    private final List<Cat> vegetables = List.of(Cat.of("Fruits & Vegetables"), Cat.of("Fresh Vegetables"));
    private final String vendor = id();

    /** The next test's clean-up (delete by query) only sees refreshed documents: leave none unrefreshed. */
    @AfterEach
    void refreshForTheNextCleanUp() throws IOException {
        refresh();
    }

    /**
     * A product nobody else's search can find: ARCHIVED (indexed, versioned, invisible) with a name of its own, so this
     * class never changes what the search tests of the shared index see.
     */
    private Map<String, Object> archived(String productId, String name, long version) {
        Map<String, Object> product = product(productId, vendor, name, version, vegetables);
        product.put("status", "ARCHIVED");
        return product;
    }

    private static Outcome handled(String eventType, Runnable listener) {
        ConsumedEvents.start("catalog-query.test", eventType);
        try {
            listener.run();
        } catch (RuntimeException e) {
            ConsumedEvents.clear();
            throw e;
        }
        return ConsumedEvents.finish().outcome();
    }

    @Test
    void mergedPartsReportOlderAndRepeatedEventsAsDuplicates() {
        String productId = id();
        String name = "Dupcheck " + productId.substring(0, 8);
        assertThat(handled("ProductUpserted", () -> publishCatalogEvent("ProductUpserted", productId,
                archived(productId, name, 2)))).isEqualTo(Outcome.APPLIED);
        assertThat(handled("ProductUpserted", () -> publishCatalogEvent("ProductUpserted", productId,
                archived(productId, name, 2)))).as("redelivered").isEqualTo(Outcome.DUPLICATE);
        assertThat(handled("ProductUpserted", () -> publishCatalogEvent("ProductUpserted", productId,
                archived(productId, name, 1)))).as("older").isEqualTo(Outcome.DUPLICATE);

        assertThat(handled("PriceChanged", () -> publishPricingEvent("PriceChanged", productId,
                price(productId, vendor, "60.00", "49.00", 5)))).isEqualTo(Outcome.APPLIED);
        assertThat(handled("PriceChanged", () -> publishPricingEvent("PriceChanged", productId,
                price(productId, vendor, "60.00", "49.00", 5)))).isEqualTo(Outcome.DUPLICATE);
        assertThat(handled("PriceChanged", () -> publishPricingEvent("PriceChanged", productId,
                price(productId, vendor, "60.00", "52.00", 6)))).isEqualTo(Outcome.APPLIED);
    }

    @Test
    void externallyVersionedDocumentsReportOlderAndRepeatedEventsAsDuplicates() {
        List<Cat> path = List.of(Cat.of("Dairy " + id()));
        String categoryId = path.getLast().id();
        assertThat(handled("CategoryChanged", () -> publishCatalogEvent("CategoryChanged", categoryId,
                category(path, 1, 3)))).isEqualTo(Outcome.APPLIED);
        assertThat(handled("CategoryChanged", () -> publishCatalogEvent("CategoryChanged", categoryId,
                category(path, 1, 3)))).isEqualTo(Outcome.DUPLICATE);
        assertThat(handled("CategoryChanged", () -> publishCatalogEvent("CategoryChanged", categoryId,
                category(path, 2, 2)))).isEqualTo(Outcome.DUPLICATE);
        assertThat(handled("CategoryChanged", () -> publishCatalogEvent("CategoryChanged", categoryId,
                category(path, 2, 4)))).isEqualTo(Outcome.APPLIED);
    }
}
