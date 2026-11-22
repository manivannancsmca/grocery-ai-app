package com.grocery.catalogquery;

import co.elastic.clients.json.JsonData;
import com.grocery.catalogquery.TestEvents.Cat;
import com.grocery.common.kafka.InvalidEventException;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.grocery.catalogquery.TestEvents.category;
import static com.grocery.catalogquery.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductSearchIntegrationTest extends AbstractIntegrationTest {

    private final Cat fruitsAndVegetables = Cat.of("Fruits & Vegetables");
    private final Cat freshVegetables = Cat.of("Fresh Vegetables");
    private final Cat freshFruits = Cat.of("Fresh Fruits");
    private final Cat dairy = Cat.of("Dairy");
    private final List<Cat> vegetables = List.of(fruitsAndVegetables, freshVegetables);
    private final List<Cat> fruits = List.of(fruitsAndVegetables, freshFruits);
    private final String vendor = id();

    private String index(String name, List<Cat> path) {
        String productId = id();
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, name, 1, path));
        return productId;
    }

    private List<String> names(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.get("content").forEach(card -> names.add(card.get("name").asString()));
        return names;
    }

    private JsonNode search(String... params) throws Exception {
        var request = get("/api/search/products");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    @Test
    void typosAreTolerated() throws Exception {
        index("Tomato", vegetables);
        index("Banana", fruits);
        index("Potato", vegetables);
        refresh();

        assertThat(names(search("q", "tomatos"))).containsExactly("Tomato");
        assertThat(names(search("q", "bananna"))).containsExactly("Banana");
        assertThat(names(search("q", "Tomato"))).containsExactly("Tomato");
    }

    @Test
    void groceryAndIndianSynonymsMatch() throws Exception {
        index("Curd", List.of(dairy));
        index("Lady Finger", vegetables);
        index("Coriander Leaves", vegetables);
        index("Aashirvaad Atta", List.of(Cat.of("Staples")));
        index("Paneer", List.of(dairy));
        refresh();

        assertThat(names(search("q", "yogurt"))).containsExactly("Curd");
        assertThat(names(search("q", "dahi"))).containsExactly("Curd");
        assertThat(names(search("q", "bhindi"))).containsExactly("Lady Finger");
        assertThat(names(search("q", "okra"))).containsExactly("Lady Finger");
        assertThat(names(search("q", "dhania"))).containsExactly("Coriander Leaves");
        assertThat(names(search("q", "wheat flour"))).containsExactly("Aashirvaad Atta");
        assertThat(names(search("q", "cottage cheese"))).containsExactly("Paneer");
    }

    @Test
    void nameMatchesRankAboveDescriptionMatches() throws Exception {
        String inDescription = id();
        Map<String, Object> soup = product(inDescription, vendor, "Garden Soup Mix", 1, vegetables);
        soup.put("description", "Dried carrot and spinach");
        publishCatalogEvent("ProductUpserted", inDescription, soup);
        index("Carrot", vegetables);
        refresh();

        assertThat(names(search("q", "carrot"))).containsExactly("Carrot", "Garden Soup Mix");
    }

    @Test
    void distinctProductsCountsEachNamedProductOnceAndDescriptionMatchesOnlyAsALastResort() throws Exception {
        // Live 2026-11-21: "rice" matched 30 listings (Daawat Basmati in 7 stores, Dog Food "chicken and rice"), and
        // the shopping assistant answered "30 rice varieties".
        List<Cat> staples = List.of(Cat.of("Rice and Rice Products"));
        index("Ponni Rice", staples);
        String otherStore = id();
        String elsewhere = id();
        publishCatalogEvent("ProductUpserted", elsewhere, product(elsewhere, otherStore, "Ponni Rice", 1, staples));
        index("Idli Rice", staples);
        String dogFoodId = id();
        Map<String, Object> dogFood = product(dogFoodId, vendor, "Dog Food", 1, List.of(Cat.of("Pet Care")));
        dogFood.put("description", "Dry food for adult dogs, chicken and rice.");
        dogFood.put("tags", List.of());
        publishCatalogEvent("ProductUpserted", dogFoodId, dogFood);
        refresh();

        JsonNode rice = search("q", "rice");
        assertThat(rice.get("totalElements").asLong()).isEqualTo(4);           // every listing, Dog Food last
        assertThat(names(rice).getLast()).isEqualTo("Dog Food");
        assertThat(rice.get("distinctProducts").asLong()).isEqualTo(2);         // Ponni Rice and Idli Rice
        // only a description mentions it: then that is what the words mean
        JsonNode chicken = search("q", "chicken");
        assertThat(names(chicken)).containsExactly("Dog Food");
        assertThat(chicken.get("distinctProducts").asLong()).isEqualTo(1);
        // no words: every product once
        assertThat(search().get("distinctProducts").asLong()).isEqualTo(3);
        assertThat(search("q", "nothing like this").get("distinctProducts").asLong()).isZero();
    }

    @Test
    void cardsCarryAShortDescription() throws Exception {
        String longId = id();
        Map<String, Object> soup = product(longId, vendor, "Garden Soup Mix", 1, vegetables);
        soup.put("description", "Dried carrot and spinach, " + "slow dried in small batches ".repeat(15).strip());
        publishCatalogEvent("ProductUpserted", longId, soup);
        String noneId = id();
        Map<String, Object> plain = product(noneId, vendor, "Plain Rusk", 2, vegetables);
        plain.put("description", null);
        publishCatalogEvent("ProductUpserted", noneId, plain);
        index("Carrot", vegetables);
        refresh();

        Map<String, JsonNode> cards = new HashMap<>();
        search("size", "50").get("content").forEach(card -> cards.put(card.get("name").asString(), card));
        assertThat(cards.get("Carrot").get("shortDescription").asString()).isEqualTo("Fresh carrot from local farms");
        String cut = cards.get("Garden Soup Mix").get("shortDescription").asString();
        assertThat(cut).startsWith("Dried carrot and spinach, slow dried").endsWith(" dried in small…");
        assertThat(cut.length()).isLessThanOrEqualTo(300);
        assertThat(cards.get("Plain Rusk").get("shortDescription").isNull()).isTrue();
    }

    @Test
    void anOlderVersionNeverOverwritesNewerDataAndDuplicatesAreHarmless() throws Exception {
        String productId = id();
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Tomato Hybrid", 5, vegetables));
        // a replayed older snapshot and a duplicate of the current one
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Tomato Old Name", 4, vegetables));
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Tomato Hybrid", 5, vegetables));
        refresh();

        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tomato Hybrid"))
                .andExpect(jsonPath("$.sku").value("SKU-5"));

        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Tomato Desi", 6, vegetables));
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(jsonPath("$.name").value("Tomato Desi"));
    }

    @Test
    void productDetailHasTheFullSnapshot() throws Exception {
        String productId = index("Tomato", vegetables);
        refresh();

        mvc.perform(get("/api/catalog/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId))
                .andExpect(jsonPath("$.name").value("Tomato"))
                .andExpect(jsonPath("$.unitLabel").value("500 g"))
                .andExpect(jsonPath("$.unitValue").value(500))
                .andExpect(jsonPath("$.unitOfMeasure").value("G"))
                .andExpect(jsonPath("$.foodType").value("VEG"))
                .andExpect(jsonPath("$.primaryImageUrl").value("https://img.example.com/" + productId + ".jpg"))
                .andExpect(jsonPath("$.brand.name").value("Farm Fresh"))
                .andExpect(jsonPath("$.category.id").value(freshVegetables.id()))
                .andExpect(jsonPath("$.category.name").value("Fresh Vegetables"))
                .andExpect(jsonPath("$.vendor.id").value(vendor))
                .andExpect(jsonPath("$.vendor.name").value("Green Basket"))
                .andExpect(jsonPath("$.barcode").isEmpty())
                .andExpect(jsonPath("$.tags[0]").value("organic"))
                .andExpect(jsonPath("$.attributes[0].name").value("Origin"))
                .andExpect(jsonPath("$.attributes[0].value").value("Nashik"))
                .andExpect(jsonPath("$.categoryPath.length()").value(2))
                .andExpect(jsonPath("$.categoryPath[0].name").value("Fruits & Vegetables"))
                .andExpect(jsonPath("$.publishedAt").value("2026-11-11T10:15:30Z"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mvc.perform(get("/api/catalog/products/{id}", id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));

        // the stored document: ISO dates, ancestor ids for subtree filters, "id|name" facet keys
        JsonObject source = elasticsearch.get(g -> g.index("products").id(productId), JsonData.class)
                .source().toJson(elasticsearch._jsonpMapper()).asJsonObject();
        assertThat(source.getString("publishedAt")).isEqualTo("2026-11-11T10:15:30Z");
        assertThat(source.getBoolean("visible")).isTrue();
        assertThat(source.getInt("productVersion")).isEqualTo(1);
        assertThat(source.getJsonArray("categoryIds").getValuesAs(JsonString::getString))
                .containsExactly(fruitsAndVegetables.id(), freshVegetables.id());
        assertThat(source.getString("categoryFacet")).isEqualTo(freshVegetables.id() + "|Fresh Vegetables");
    }

    @Test
    void withoutAQueryTheNewestProductsComeFirst() throws Exception {
        for (String[] product : List.of(new String[]{"Apple", "2026-11-01T08:00:00Z"},
                new String[]{"Carrot", "2026-11-10T08:00:00Z"}, new String[]{"Beetroot", "2026-11-05T08:00:00Z"})) {
            String productId = id();
            Map<String, Object> event = TestEvents.product(productId, vendor, product[0], 1, vegetables);
            event.put("publishedAt", product[1]);
            publishCatalogEvent("ProductUpserted", productId, event);
        }
        refresh();

        assertThat(names(search())).containsExactly("Carrot", "Beetroot", "Apple");
        assertThat(names(search("sort", "newest"))).containsExactly("Carrot", "Beetroot", "Apple");
        assertThat(names(search("sort", "NAME_ASC"))).containsExactly("Apple", "Beetroot", "Carrot");
    }

    @Test
    void suspendedVendorsInactiveCategoriesAndUnpublishedProductsAreHidden() throws Exception {
        String visible = index("Tomato Cherry", vegetables);
        String suspendedVendor = id();
        Map<String, Object> a = product(suspendedVendor, vendor, "Tomato Roma", 2, vegetables);
        a.put("vendorActive", false);
        publishCatalogEvent("ProductUpserted", suspendedVendor, a);
        String inactiveCategory = id();
        Map<String, Object> b = product(inactiveCategory, vendor, "Tomato Plum", 2, vegetables);
        b.put("categoryActive", false);
        publishCatalogEvent("ProductUpserted", inactiveCategory, b);
        String unpublished = id();
        Map<String, Object> c = product(unpublished, vendor, "Tomato Beef", 3, vegetables);
        c.put("status", "UNPUBLISHED");
        publishCatalogEvent("ProductUpserted", unpublished, c);
        String archived = id();
        Map<String, Object> d = product(archived, vendor, "Tomato Green", 4, vegetables);
        d.put("status", "ARCHIVED");
        publishCatalogEvent("ProductUpserted", archived, d);
        refresh();

        assertThat(names(search("q", "tomato"))).containsExactly("Tomato Cherry");
        assertThat(search().get("totalElements").asLong()).isEqualTo(1);
        for (String hidden : List.of(suspendedVendor, inactiveCategory, unpublished, archived)) {
            mvc.perform(get("/api/catalog/products/{id}", hidden))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        }
        mvc.perform(get("/api/catalog/products/{id}", visible)).andExpect(status().isOk());

        // vendor reinstated → catalog-command re-publishes the snapshot as visible
        a.put("vendorActive", true);
        a.put("productVersion", 3);
        publishCatalogEvent("ProductUpserted", suspendedVendor, a);
        refresh();
        assertThat(names(search("q", "tomato", "sort", "name_asc"))).containsExactly("Tomato Cherry", "Tomato Roma");
    }

    @Test
    void draftsAreNotIndexedUntilPublished() throws Exception {
        String productId = id();
        Map<String, Object> draft = product(productId, vendor, "Tomato", 1, vegetables);
        draft.put("status", "DRAFT");
        draft.put("publishedAt", null);
        publishCatalogEvent("ProductUpserted", productId, draft);
        refresh();
        assertThat(elasticsearch.exists(e -> e.index("products").id(productId)).value()).isFalse();

        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, "Tomato", 2, vegetables));
        refresh();
        assertThat(names(search("q", "tomato"))).containsExactly("Tomato");
    }

    @Test
    void categoryFilterIncludesSubCategoriesAndFacetsDescribeTheHits() throws Exception {
        index("Tomato", vegetables);
        index("Onion", vegetables);
        index("Banana", fruits);
        String milk = id();
        Map<String, Object> milkEvent = product(milk, vendor, "Milk", 1, List.of(dairy));
        milkEvent.put("brand", Map.of("id", "b0000000-0000-0000-0000-000000000002", "name", "Amul"));
        milkEvent.put("foodType", null);
        publishCatalogEvent("ProductUpserted", milk, milkEvent);
        refresh();

        JsonNode parent = search("categoryId", fruitsAndVegetables.id(), "sort", "name_asc");
        assertThat(names(parent)).containsExactly("Banana", "Onion", "Tomato");
        assertThat(names(search("categoryId", freshVegetables.id(), "sort", "name_asc")))
                .containsExactly("Onion", "Tomato");

        JsonNode facets = parent.get("facets");
        assertThat(facets.get("categories")).hasSize(2);
        assertThat(facets.get("categories").get(0).get("id").asString()).isEqualTo(freshVegetables.id());
        assertThat(facets.get("categories").get(0).get("name").asString()).isEqualTo("Fresh Vegetables");
        assertThat(facets.get("categories").get(0).get("count").asLong()).isEqualTo(2);
        assertThat(facets.get("brands").get(0).get("name").asString()).isEqualTo("Farm Fresh");
        assertThat(facets.get("brands").get(0).get("count").asLong()).isEqualTo(3);
        assertThat(facets.get("foodTypes").get(0).get("value").asString()).isEqualTo("VEG");
        assertThat(facets.get("foodTypes").get(0).get("count").asLong()).isEqualTo(3);

        JsonNode all = search();
        assertThat(all.get("totalElements").asLong()).isEqualTo(4);
        assertThat(all.get("facets").get("brands")).hasSize(2);
        assertThat(names(search("brandId", "b0000000-0000-0000-0000-000000000002"))).containsExactly("Milk");
        assertThat(names(search("foodType", "VEG", "vendorId", vendor))).hasSize(3);
        assertThat(search("vendorId", id()).get("totalElements").asLong()).isZero();
    }

    @Test
    void severalStoresCanBeSearchedAtOnce() throws Exception {
        String nearbyStore = id();
        String farStore = id();
        index("Tomato", vegetables);                                             // vendor
        String onion = id();
        publishCatalogEvent("ProductUpserted", onion, product(onion, nearbyStore, "Onion", 1, vegetables));
        String potato = id();
        publishCatalogEvent("ProductUpserted", potato, product(potato, farStore, "Potato", 1, vegetables));
        refresh();

        // repeated vendorId = products of any of those stores
        JsonNode two = search("vendorId", vendor, "vendorId", nearbyStore, "sort", "name_asc");
        assertThat(names(two)).containsExactly("Onion", "Tomato");
        assertThat(two.get("totalElements").asLong()).isEqualTo(2);
        assertThat(two.get("facets").get("categories").get(0).get("count").asLong()).isEqualTo(2);
        assertThat(names(search("vendorId", farStore, "vendorId", id(), "sort", "name_asc"))).containsExactly("Potato");
        assertThat(names(search("q", "onion", "vendorId", vendor, "vendorId", nearbyStore))).containsExactly("Onion");
        // one value works as before; a repeated value counts once
        assertThat(names(search("vendorId", nearbyStore))).containsExactly("Onion");
        assertThat(names(search("vendorId", vendor, "vendorId", vendor))).containsExactly("Tomato");
        assertThat(search().get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    void atMostFiftyVendorIdsPerSearch() throws Exception {
        index("Tomato", vegetables);
        refresh();

        var fifty = get("/api/search/products").param("vendorId", vendor);
        var fiftyOne = get("/api/search/products").param("vendorId", vendor);
        for (int i = 1; i < 50; i++) {
            String other = id();
            fifty.param("vendorId", other);
            fiftyOne.param("vendorId", other);
        }
        fiftyOne.param("vendorId", id());
        assertThat(names(body(mvc.perform(fifty).andExpect(status().isOk())))).containsExactly("Tomato");
        mvc.perform(fiftyOne)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.detail").value("at most 50 vendorId values"));
        mvc.perform(get("/api/search/products").param("vendorId", vendor).param("vendorId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void pagingAndSorting() throws Exception {
        for (String name : List.of("Carrot", "Apple", "Beetroot")) {
            index(name, vegetables);
        }
        refresh();

        JsonNode page = search("sort", "name_asc", "size", "2", "page", "1");
        assertThat(names(page)).containsExactly("Carrot");
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(2);
        assertThat(page.get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(search("size", "500").get("size").asInt()).isEqualTo(50);
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        mvc.perform(get("/api/search/products").param("page", "21").param("size", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/search/products").param("foodType", "FISH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/search/products").param("sort", "cheapest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/search/products").param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/search/suggest").param("q", "t"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void suggestReturnsPrefixMatchesForProductsAndCategories() throws Exception {
        publishCatalogEvent("CategoryChanged", fruitsAndVegetables.id(), category(List.of(fruitsAndVegetables), 0, 1));
        publishCatalogEvent("CategoryChanged", freshVegetables.id(), category(vegetables, 0, 1));
        index("Tomato", vegetables);
        index("Tomato Ketchup", List.of(Cat.of("Sauces")));
        index("Potato", vegetables);
        refresh();

        mvc.perform(get("/api/search/suggest").param("q", "tom"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andExpect(jsonPath("$.products[?(@.name == 'Tomato')].unitLabel").value("500 g"))
                .andExpect(jsonPath("$.products[?(@.name == 'Tomato Ketchup')].brandName").value("Farm Fresh"))
                .andExpect(jsonPath("$.categories.length()").value(0));
        mvc.perform(get("/api/search/suggest").param("q", "tomato ket"))
                .andExpect(jsonPath("$.products.length()").value(1))
                .andExpect(jsonPath("$.products[0].name").value("Tomato Ketchup"));
        mvc.perform(get("/api/search/suggest").param("q", "fresh veg"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].id").value(freshVegetables.id()))
                .andExpect(jsonPath("$.categories[0].name").value("Fresh Vegetables"))
                .andExpect(jsonPath("$.categories[0].path[0]").value("Fruits & Vegetables"))
                .andExpect(jsonPath("$.categories[0].path[1]").value("Fresh Vegetables"));
    }

    @Test
    void malformedEventsAreInvalidAndOtherEventTypesAreIgnored() {
        ConsumerRecord<String, String> notJson = new ConsumerRecord<>("catalog.events", 0, 0L, "k", "not json");
        notJson.headers().add("eventId", "e-1".getBytes(StandardCharsets.UTF_8));
        notJson.headers().add("eventType", "ProductUpserted".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> catalogEvents.on(notJson)).isInstanceOf(InvalidEventException.class);

        String productId = id();
        Map<String, Object> withoutVersion = product(productId, vendor, "Tomato", 1, vegetables);
        withoutVersion.remove("productVersion");
        assertThatThrownBy(() -> publishCatalogEvent("ProductUpserted", productId, withoutVersion))
                .isInstanceOf(InvalidEventException.class);

        publishCatalogEvent("BrandRenamed", id(), Map.of("brandId", id()));
        publishRestaurantEvent("RestaurantApproved", id(), Map.of("restaurantId", id()));
    }

    @Test
    void searchIsPublic() throws Exception {
        mvc.perform(get("/api/search/products").param("q", "anything"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.facets.categories").isArray());
    }

    @Test
    void aDeletedIndexIsRecreatedThroughTheAliasOnTheNextWrite() throws Exception {
        index("Tomato", vegetables);
        elasticsearch.indices().delete(d -> d.index("products_v1"));

        // reads on the missing alias return empty results (and recreate it)
        mvc.perform(get("/api/search/products").param("q", "tomato"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        elasticsearch.indices().delete(d -> d.index("products_v1"));

        // the next write finds no index behind the alias (require_alias) → index + alias created, write retried
        index("Onion", vegetables);
        refresh();
        assertThat(elasticsearch.indices().existsAlias(a -> a.name("products")).value()).isTrue();
        assertThat(elasticsearch.indices().exists(e -> e.index("products")).value()).isTrue();
        assertThat(elasticsearch.indices().getAlias(a -> a.name("products")).aliases()).containsOnlyKeys("products_v1");
        assertThat(elasticsearch.indices().getMapping(m -> m.index("products_v1")).get("products_v1").mappings()
                .dynamic().jsonValue()).isEqualTo("strict");
        assertThat(names(search("q", "onion"))).containsExactly("Onion");
    }
}
