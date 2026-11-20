package com.grocery.catalogquery;

import co.elastic.clients.json.JsonData;
import com.grocery.catalogquery.TestEvents.Cat;
import com.grocery.catalogquery.index.IndexDefinition;
import com.grocery.common.kafka.InvalidEventException;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.grocery.catalogquery.TestEvents.availability;
import static com.grocery.catalogquery.TestEvents.price;
import static com.grocery.catalogquery.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4: price (pricing.events) and stock (inventory.events) live in the product documents next to the catalog
 * snapshot, each part with its own version (docs/api/phase-4-inventory-and-pricing.md §6, §8).
 */
class PriceAndStockSearchIntegrationTest extends AbstractIntegrationTest {

    private final List<Cat> vegetables = List.of(Cat.of("Fruits & Vegetables"), Cat.of("Fresh Vegetables"));
    private final String vendor = id();

    private String index(String name) {
        String productId = id();
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, name, 1, vegetables));
        return productId;
    }

    private void priced(String productId, String mrp, String sellingPrice, long version) {
        publishPricingEvent("PriceChanged", productId, price(productId, vendor, mrp, sellingPrice, version));
    }

    private void stock(String productId, boolean inStock, long version) {
        publishInventoryEvent("ProductAvailabilityChanged", productId, availability(productId, vendor, inStock, version));
    }

    private JsonNode search(String... params) throws Exception {
        var request = get("/api/search/products");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private List<String> names(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.get("content").forEach(card -> names.add(card.get("name").asString()));
        return names;
    }

    private JsonObject stored(String productId) throws Exception {
        return elasticsearch.get(g -> g.index("products").id(productId), JsonData.class)
                .source().toJson(elasticsearch._jsonpMapper()).asJsonObject();
    }

    @Test
    void aProductWithoutPriceOrStockIsVisibleButNotBuyable() throws Exception {
        String productId = index("Tomato");
        refresh();

        JsonNode card = search("q", "tomato").get("content").get(0);
        assertThat(card.get("price").isNull()).isTrue();
        assertThat(card.get("inStock").asBoolean()).isFalse();
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").isEmpty())
                .andExpect(jsonPath("$.inStock").value(false));
    }

    @Test
    void priceAndStockEventsShowUpOnCardsAndTheProductPage() throws Exception {
        String productId = index("Tomato");
        priced(productId, "60.00", "49.00", 1);
        stock(productId, true, 4);
        refresh();

        String response = mvc.perform(get("/api/search/products").param("q", "tomato"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].price.mrp").value(60.0))
                .andExpect(jsonPath("$.content[0].price.sellingPrice").value(49.0))
                .andExpect(jsonPath("$.content[0].price.currency").value("INR"))
                .andExpect(jsonPath("$.content[0].price.discountPercent").value(18))
                .andExpect(jsonPath("$.content[0].inStock").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains("\"mrp\":60.00").contains("\"sellingPrice\":49.00");   // always 2 decimals

        // the price and stock parts leave every catalog field alone
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tomato"))
                .andExpect(jsonPath("$.brand.name").value("Farm Fresh"))
                .andExpect(jsonPath("$.vendor.id").value(vendor))
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.tags[0]").value("organic"))
                .andExpect(jsonPath("$.price.sellingPrice").value(49.0))
                .andExpect(jsonPath("$.price.discountPercent").value(18))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.categoryPath.length()").value(2));
        JsonObject source = stored(productId);
        assertThat(source.getBoolean("visible")).isTrue();
        assertThat(source.getInt("productVersion")).isEqualTo(1);

        // no discount when the selling price equals the MRP; sold out again
        priced(productId, "60.00", "60.00", 2);
        stock(productId, false, 5);
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(jsonPath("$.price.sellingPrice").value(60.0))
                .andExpect(jsonPath("$.price.discountPercent").value(0))
                .andExpect(jsonPath("$.inStock").value(false));
    }

    @Test
    void everyPartIgnoresOlderAndDuplicateEventsOfItsOwnKind() throws Exception {
        String productId = index("Tomato");
        priced(productId, "60.00", "49.00", 5);
        priced(productId, "60.00", "30.00", 4);        // replayed older price
        priced(productId, "60.00", "49.00", 5);        // duplicate
        stock(productId, true, 9);
        stock(productId, false, 8);                    // replayed older stock event
        refresh();

        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(jsonPath("$.price.sellingPrice").value(49.0))
                .andExpect(jsonPath("$.inStock").value(true));

        priced(productId, "60.00", "45.00", 6);
        stock(productId, false, 10);
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(jsonPath("$.price.sellingPrice").value(45.0))
                .andExpect(jsonPath("$.inStock").value(false));

        JsonObject source = stored(productId);
        assertThat(source.getInt("productVersion")).isEqualTo(1);
        assertThat(source.getInt("priceVersion")).isEqualTo(6);
        assertThat(source.getInt("stockVersion")).isEqualTo(10);
    }

    @Test
    void aNewCatalogSnapshotKeepsPriceAndStockAndDropsFieldsThatBecameNull() throws Exception {
        String productId = index("Tomato");
        priced(productId, "60.00", "49.00", 1);
        stock(productId, true, 1);

        Map<String, Object> renamed = product(productId, vendor, "Tomato Hybrid", 2, vegetables);
        renamed.put("brand", null);                    // the vendor removed the brand
        renamed.put("barcode", "8901234567890");
        publishCatalogEvent("ProductUpserted", productId, renamed);
        // a replayed older snapshot must not bring the old name or the brand back
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Tomato", 1, vegetables));
        refresh();

        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tomato Hybrid"))
                .andExpect(jsonPath("$.brand").isEmpty())
                .andExpect(jsonPath("$.barcode").value("8901234567890"))
                .andExpect(jsonPath("$.price.sellingPrice").value(49.0))
                .andExpect(jsonPath("$.inStock").value(true));
        JsonObject source = stored(productId);
        assertThat(source.containsKey("brandId")).isFalse();
        assertThat(source.containsKey("brandFacet")).isFalse();
        assertThat(source.getInt("productVersion")).isEqualTo(2);

        // unpublishing hides the product; price and stock survive for when it is published again
        Map<String, Object> unpublished = product(productId, vendor, "Tomato Hybrid", 3, vegetables);
        unpublished.put("status", "UNPUBLISHED");
        publishCatalogEvent("ProductUpserted", productId, unpublished);
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", productId)).andExpect(status().isNotFound());
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Tomato Hybrid", 4, vegetables));
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.sellingPrice").value(49.0))
                .andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    void priceAndStockThatArriveBeforeTheProductWaitInAHiddenPlaceholder() throws Exception {
        String productId = id();
        priced(productId, "120.00", "99.00", 1);
        stock(productId, true, 2);
        refresh();

        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        assertThat(search().get("totalElements").asLong()).isZero();
        assertThat(search("inStock", "true").get("totalElements").asLong()).isZero();
        assertThat(search("minPrice", "0").get("totalElements").asLong()).isZero();
        JsonObject placeholder = stored(productId);
        assertThat(placeholder.getBoolean("visible")).isFalse();
        assertThat(placeholder.containsKey("name")).isFalse();
        assertThat(placeholder.containsKey("productVersion")).isFalse();
        assertThat(placeholder.getInt("priceVersion")).isEqualTo(1);
        assertThat(placeholder.getInt("stockVersion")).isEqualTo(2);

        // the vendor priced a DRAFT: the snapshot completes the placeholder but it stays hidden …
        Map<String, Object> draft = product(productId, vendor, "Basmati Rice", 1, vegetables);
        draft.put("status", "DRAFT");
        draft.put("publishedAt", null);
        publishCatalogEvent("ProductUpserted", productId, draft);
        refresh();
        assertThat(search().get("totalElements").asLong()).isZero();

        // … until it is published, and then it has its price and stock at once
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Basmati Rice", 2, vegetables));
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Basmati Rice"))
                .andExpect(jsonPath("$.price.sellingPrice").value(99.0))
                .andExpect(jsonPath("$.price.discountPercent").value(18))
                .andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    void inStockFilterPriceRangeAndPriceSorting() throws Exception {
        String apple = index("Apple");
        String banana = index("Banana");
        String carrot = index("Carrot");
        index("Dates");                                 // never priced, never stocked
        priced(apple, "200.00", "180.00", 1);
        priced(banana, "60.00", "48.50", 1);
        priced(carrot, "90.00", "90.00", 1);
        stock(apple, true, 1);
        stock(banana, false, 1);
        stock(carrot, true, 1);
        refresh();

        assertThat(names(search("sort", "price_asc"))).containsExactly("Banana", "Carrot", "Apple", "Dates");
        assertThat(names(search("sort", "PRICE_DESC"))).containsExactly("Apple", "Carrot", "Banana", "Dates");
        assertThat(names(search("inStock", "true", "sort", "name_asc"))).containsExactly("Apple", "Carrot");
        assertThat(names(search("inStock", "false", "sort", "name_asc"))).hasSize(4);       // false = no filter
        assertThat(names(search("minPrice", "48.50", "maxPrice", "90", "sort", "name_asc")))
                .containsExactly("Banana", "Carrot");
        assertThat(names(search("minPrice", "100"))).containsExactly("Apple");
        assertThat(names(search("maxPrice", "50"))).containsExactly("Banana");
        assertThat(names(search("maxPrice", "50", "inStock", "true"))).isEmpty();
        assertThat(search("minPrice", "0").get("totalElements").asLong()).isEqualTo(3);      // unpriced never match
    }

    @Test
    void invalidPriceParametersAreRejected() throws Exception {
        for (String[] params : List.of(new String[]{"minPrice", "-1"}, new String[]{"maxPrice", "-0.01"},
                new String[]{"minPrice", "abc"}, new String[]{"inStock", "maybe"})) {
            mvc.perform(get("/api/search/products").param(params[0], params[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }
        mvc.perform(get("/api/search/products").param("minPrice", "100").param("maxPrice", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void malformedEventsAreInvalidAndOtherEventTypesAreIgnored() throws Exception {
        String productId = index("Tomato");
        Map<String, Object> withoutVersion = price(productId, vendor, "60.00", "49.00", 1);
        withoutVersion.remove("priceVersion");
        assertThatThrownBy(() -> publishPricingEvent("PriceChanged", productId, withoutVersion))
                .isInstanceOf(InvalidEventException.class);
        Map<String, Object> zero = price(productId, vendor, "60.00", "0.00", 1);
        assertThatThrownBy(() -> publishPricingEvent("PriceChanged", productId, zero))
                .isInstanceOf(InvalidEventException.class);
        Map<String, Object> withoutFlag = availability(productId, vendor, true, 1);
        withoutFlag.remove("inStock");
        assertThatThrownBy(() -> publishInventoryEvent("ProductAvailabilityChanged", productId, withoutFlag))
                .isInstanceOf(InvalidEventException.class);

        publishPricingEvent("PriceChangeRequested", productId, Map.of("requestId", id(), "productId", productId));
        publishInventoryEvent("StockAdjusted", productId, Map.of("productId", productId, "delta", 5));
        publishInventoryEvent("LowStockDetected", productId, Map.of("productId", productId, "available", 2));
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(jsonPath("$.price").isEmpty())
                .andExpect(jsonPath("$.inStock").value(false));
    }

    @Test
    void anIndexCreatedBeforePhase4GetsThePriceAndStockFieldsAdded() throws Exception {
        // products_v1 as Phase 3 created it: strict mapping without the price and stock fields
        ObjectNode definition = (ObjectNode) json.readTree(indexManager.definitionJson(IndexDefinition.PRODUCTS));
        ObjectNode properties = (ObjectNode) definition.at("/mappings/properties");
        for (String field : List.of("mrp", "sellingPrice", "currency", "discountPercent", "priceVersion",
                "inStock", "stockVersion")) {
            properties.remove(field);
        }
        elasticsearch.indices().delete(d -> d.index("products_v1"));
        elasticsearch.indices().create(c -> c.index("products_v1")
                .withJson(new StringReader(json.writeValueAsString(definition)))
                .aliases("products", a -> a.isWriteIndex(true)));
        assertThat(elasticsearch.indices().getMapping(m -> m.index("products_v1")).get("products_v1").mappings()
                .properties()).doesNotContainKey("sellingPrice");

        indexManager.recheck(IndexDefinition.PRODUCTS);         // what a restart of the new release does

        assertThat(elasticsearch.indices().getMapping(m -> m.index("products_v1")).get("products_v1").mappings()
                .properties()).containsKeys("sellingPrice", "mrp", "priceVersion", "inStock", "stockVersion");
        String productId = index("Tomato");
        priced(productId, "60.00", "49.00", 1);
        stock(productId, true, 1);
        refresh();
        assertThat(names(search("inStock", "true", "maxPrice", "50"))).containsExactly("Tomato");

        indexManager.recheck(IndexDefinition.PRODUCTS);         // applying it again is harmless
        assertThat(names(search("sort", "price_desc"))).containsExactly("Tomato");
    }

    @Test
    void afterTheIndexIsLostTheThreeRepublishesRebuildEveryPart() throws Exception {
        String tomato = index("Tomato");
        String onion = index("Onion");
        priced(tomato, "60.00", "49.00", 3);
        stock(tomato, true, 7);
        priced(onion, "40.00", "35.00", 2);
        stock(onion, true, 4);
        elasticsearch.indices().delete(d -> d.index("products_v1"));

        // republish in the documented order: catalog → pricing → inventory (the versions are the current ones) …
        publishCatalogEvent("ProductUpserted", tomato, product(tomato, vendor, "Tomato", 1, vegetables));
        priced(tomato, "60.00", "49.00", 3);
        stock(tomato, true, 7);
        // … although any order gives the same documents: the onion's parts arrive the other way round
        stock(onion, true, 4);
        priced(onion, "40.00", "35.00", 2);
        publishCatalogEvent("ProductUpserted", onion, product(onion, vendor, "Onion", 1, vegetables));
        refresh();

        assertThat(elasticsearch.indices().getAlias(a -> a.name("products")).aliases()).containsOnlyKeys("products_v1");
        assertThat(elasticsearch.indices().getMapping(m -> m.index("products_v1")).get("products_v1").mappings()
                .dynamic().jsonValue()).isEqualTo("strict");
        mvc.perform(get("/api/catalog/products/{id}", tomato))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tomato"))
                .andExpect(jsonPath("$.price.mrp").value(60.0))
                .andExpect(jsonPath("$.price.sellingPrice").value(49.0))
                .andExpect(jsonPath("$.inStock").value(true));
        assertThat(names(search("inStock", "true", "sort", "price_asc"))).containsExactly("Onion", "Tomato");
        JsonObject source = stored(onion);
        assertThat(source.getInt("productVersion")).isEqualTo(1);
        assertThat(source.getInt("priceVersion")).isEqualTo(2);
        assertThat(source.getInt("stockVersion")).isEqualTo(4);
        assertThat(source.getBoolean("visible")).isTrue();
    }

    @Test
    void theThreePartsCanBeWrittenConcurrentlyAndOutOfOrder() throws Exception {
        String productId = id();
        List<Long> scrambled = List.of(3L, 1L, 5L, 2L, 4L);
        List<Callable<Void>> writers = List.of(
                () -> {
                    for (long v : scrambled) {
                        publishCatalogEvent("ProductUpserted", productId,
                                product(productId, vendor, "Tomato v" + v, v, vegetables));
                    }
                    return null;
                },
                () -> {
                    for (long v : scrambled) {
                        priced(productId, "100.00", (90 - v) + ".00", v);
                    }
                    return null;
                },
                () -> {
                    for (long v : scrambled) {
                        stock(productId, v % 2 == 1, v);
                    }
                    return null;
                });
        ExecutorService pool = Executors.newFixedThreadPool(writers.size());
        try {
            for (Future<Void> done : pool.invokeAll(writers)) {
                done.get(1, TimeUnit.MINUTES);           // rethrows a writer's failure
            }
        } finally {
            pool.shutdownNow();
        }
        refresh();

        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tomato v5"))
                .andExpect(jsonPath("$.sku").value("SKU-5"))
                .andExpect(jsonPath("$.price.sellingPrice").value(85.0))
                .andExpect(jsonPath("$.price.discountPercent").value(15))
                .andExpect(jsonPath("$.inStock").value(true));
        JsonObject source = stored(productId);
        assertThat(source.getInt("productVersion")).isEqualTo(5);
        assertThat(source.getInt("priceVersion")).isEqualTo(5);
        assertThat(source.getInt("stockVersion")).isEqualTo(5);
    }
}
