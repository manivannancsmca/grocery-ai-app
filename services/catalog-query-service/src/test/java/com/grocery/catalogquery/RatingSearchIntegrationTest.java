package com.grocery.catalogquery;

import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.json.JsonData;
import com.grocery.catalogquery.TestEvents.Cat;
import com.grocery.catalogquery.document.RestaurantDocument;
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

import static com.grocery.catalogquery.TestEvents.availability;
import static com.grocery.catalogquery.TestEvents.item;
import static com.grocery.catalogquery.TestEvents.price;
import static com.grocery.catalogquery.TestEvents.product;
import static com.grocery.catalogquery.TestEvents.ratingSummary;
import static com.grocery.catalogquery.TestEvents.restaurant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 (docs/api/phase-12-post-mvp-features.md §1): review.events {@code RatingSummaryUpdated} → the rating part
 * ({@code rating}, {@code ratingCount}, version {@code ratingVersion} = summaryVersion) of product and restaurant
 * documents, shown on cards and detail pages, {@code sort=rating} and {@code minRating}.
 */
class RatingSearchIntegrationTest extends AbstractIntegrationTest {

    private static final double LAT = 12.9756;
    private static final double LNG = 77.6066;

    private final List<Cat> vegetables = List.of(Cat.of("Fruits & Vegetables"), Cat.of("Fresh Vegetables"));
    private final String vendor = id();

    private String indexedProduct(String name) {
        String productId = id();
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, name, 1, vegetables));
        return productId;
    }

    private void rated(String targetType, String targetId, long version, int... distribution) {
        publishReviewEvent("RatingSummaryUpdated", targetId, ratingSummary(targetType, targetId, version, distribution));
    }

    private String listedRestaurant(String name, double latOffset) {
        String restaurantId = id();
        publishRestaurantEvent("RestaurantListingUpdated", restaurantId, restaurant(restaurantId, name, 1,
                LAT + latOffset, LNG, List.of(item(name + " Thali", "199.00", "VEG"))));
        return restaurantId;
    }

    private JsonNode search(String path, String... params) throws Exception {
        var request = get(path);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private static List<String> names(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.get("content").forEach(card -> names.add(card.get("name").asString()));
        return names;
    }

    private static JsonNode card(JsonNode response, String id) {
        for (JsonNode card : response.get("content")) {
            if (card.get("id").asString().equals(id)) {
                return card;
            }
        }
        throw new AssertionError("No card " + id);
    }

    private JsonObject stored(String index, String id) throws Exception {
        return elasticsearch.get(g -> g.index(index).id(id), JsonData.class)
                .source().toJson(elasticsearch._jsonpMapper()).asJsonObject();
    }

    @Test
    void productRatingsShowOnCardsAndTheProductPage() throws Exception {
        String tomato = indexedProduct("Tomato");
        String onion = indexedProduct("Onion");
        rated("PRODUCT", tomato, 2, 0, 0, 0, 1, 1);        // 4 and 5 stars
        refresh();

        JsonNode response = search("/api/search/products");
        JsonNode tomatoCard = card(response, tomato);
        assertThat(tomatoCard.get("rating").decimalValue()).isEqualByComparingTo("4.50");
        // always 2 decimals in the JSON (the tree reader would parse 4.50 as a double)
        assertThat(mvc.perform(get("/api/catalog/products/{id}", tomato)).andReturn().getResponse()
                .getContentAsString()).contains("\"rating\":4.50");
        assertThat(tomatoCard.get("ratingCount").asInt()).isEqualTo(2);
        JsonNode onionCard = card(response, onion);
        assertThat(onionCard.get("rating").isNull()).isTrue();
        assertThat(onionCard.get("ratingCount").asInt()).isZero();

        mvc.perform(get("/api/catalog/products/{id}", tomato))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4.5))
                .andExpect(jsonPath("$.ratingCount").value(2));
        JsonObject source = stored("products", tomato);
        assertThat(source.getInt("ratingVersion")).isEqualTo(2);
        assertThat(source.getInt("productVersion")).isEqualTo(1);
    }

    @Test
    void olderAndDuplicateSummariesAreIgnored() throws Exception {
        String tomato = indexedProduct("Tomato");
        rated("PRODUCT", tomato, 3, 0, 0, 1, 0, 0);        // 3.00, v3
        rated("PRODUCT", tomato, 2, 0, 0, 0, 0, 5);        // older: ignored
        rated("PRODUCT", tomato, 3, 0, 0, 0, 0, 9);        // same version: ignored
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", tomato))
                .andExpect(jsonPath("$.rating").value(3.0))
                .andExpect(jsonPath("$.ratingCount").value(1));

        rated("PRODUCT", tomato, 4, 0, 0, 1, 0, 1);        // newer: 4.00 of 2
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", tomato))
                .andExpect(jsonPath("$.rating").value(4.0))
                .andExpect(jsonPath("$.ratingCount").value(2));
        assertThat(stored("products", tomato).getInt("ratingVersion")).isEqualTo(4);
    }

    @Test
    void theRatingAndTheOtherPartsNeverOverwriteEachOther() throws Exception {
        String tomato = indexedProduct("Tomato");
        publishPricingEvent("PriceChanged", tomato, price(tomato, vendor, "60.00", "49.00", 1));
        publishInventoryEvent("ProductAvailabilityChanged", tomato, availability(tomato, vendor, true, 1));
        rated("PRODUCT", tomato, 1, 0, 0, 0, 0, 1);

        // a new snapshot, price and stock keep the rating …
        publishCatalogEvent("ProductUpserted", tomato, product(tomato, vendor, "Tomato Hybrid", 2, vegetables));
        publishPricingEvent("PriceChanged", tomato, price(tomato, vendor, "60.00", "45.00", 2));
        publishInventoryEvent("ProductAvailabilityChanged", tomato, availability(tomato, vendor, false, 2));
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", tomato))
                .andExpect(jsonPath("$.name").value("Tomato Hybrid"))
                .andExpect(jsonPath("$.price.sellingPrice").value(45.0))
                .andExpect(jsonPath("$.inStock").value(false))
                .andExpect(jsonPath("$.rating").value(5.0))
                .andExpect(jsonPath("$.ratingCount").value(1));

        // … and a new rating keeps them
        rated("PRODUCT", tomato, 2, 0, 1, 0, 0, 1);
        refresh();
        mvc.perform(get("/api/catalog/products/{id}", tomato))
                .andExpect(jsonPath("$.name").value("Tomato Hybrid"))
                .andExpect(jsonPath("$.price.sellingPrice").value(45.0))
                .andExpect(jsonPath("$.inStock").value(false))
                .andExpect(jsonPath("$.rating").value(3.5))
                .andExpect(jsonPath("$.ratingCount").value(2));
        JsonObject source = stored("products", tomato);
        assertThat(source.getInt("productVersion")).isEqualTo(2);
        assertThat(source.getInt("priceVersion")).isEqualTo(2);
        assertThat(source.getInt("stockVersion")).isEqualTo(2);
        assertThat(source.getInt("ratingVersion")).isEqualTo(2);
    }

    @Test
    void aRatingBeforeTheProductWaitsInAHiddenPlaceholder() throws Exception {
        String tomato = id();
        rated("PRODUCT", tomato, 1, 0, 0, 0, 1, 0);
        refresh();
        assertThat(search("/api/search/products").get("content")).isEmpty();
        mvc.perform(get("/api/catalog/products/{id}", tomato)).andExpect(status().isNotFound());
        assertThat(stored("products", tomato).getBoolean("visible")).isFalse();

        publishCatalogEvent("ProductUpserted", tomato, product(tomato, vendor, "Tomato", 1, vegetables));
        refresh();
        assertThat(card(search("/api/search/products"), tomato).get("rating").decimalValue())
                .isEqualByComparingTo("4.00");
    }

    @Test
    void sortByRatingPutsTheHighestFirstAndUnratedLast() throws Exception {
        String fewVotes = indexedProduct("Basil");
        String manyVotes = indexedProduct("Carrot");
        String lower = indexedProduct("Apple");
        indexedProduct("Banana");                           // never rated
        String noMore = indexedProduct("Dates");
        rated("PRODUCT", fewVotes, 1, 0, 0, 0, 1, 1);      // 4.50 of 2
        rated("PRODUCT", manyVotes, 1, 0, 0, 0, 5, 5);     // 4.50 of 10
        rated("PRODUCT", lower, 1, 0, 0, 2, 0, 0);         // 3.00
        rated("PRODUCT", noMore, 1, 0, 0, 0, 0, 1);        // 5.00 …
        rated("PRODUCT", noMore, 2);                       // … until its only review went: unrated again
        refresh();

        List<String> byRating = names(search("/api/search/products", "sort", "rating"));
        assertThat(byRating).hasSize(5);
        assertThat(byRating.subList(0, 3)).containsExactly("Carrot", "Basil", "Apple");
        assertThat(byRating.subList(3, 5)).containsExactlyInAnyOrder("Banana", "Dates");     // unrated last
        JsonNode dates = card(search("/api/search/products"), noMore);
        assertThat(dates.get("rating").isNull()).isTrue();
        assertThat(dates.get("ratingCount").asInt()).isZero();
        assertThat(stored("products", noMore).containsKey("rating")).isFalse();
        // sorting is combined with filters, and an unknown sort is still rejected
        assertThat(names(search("/api/search/products", "sort", "RATING", "q", "carrot"))).containsExactly("Carrot");
        mvc.perform(get("/api/search/products").param("sort", "stars"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void restaurantRatingsSortAndFilter() throws Exception {
        String spice = listedRestaurant("Spice Route", 0.009);
        String green = listedRestaurant("Green Leaf", 0.018);
        String dhaba = listedRestaurant("Highway Dhaba", 0.027);
        String fresh = listedRestaurant("Fresh Bowl", 0.036);           // unrated
        rated("RESTAURANT", spice, 1, 0, 0, 1, 1, 0);             // 3.50
        rated("RESTAURANT", green, 3, 0, 0, 0, 1, 3);             // 4.75
        rated("RESTAURANT", dhaba, 1, 1, 0, 0, 0, 0);             // 1.00
        refresh();

        JsonNode all = search("/api/search/restaurants");
        assertThat(card(all, green).get("rating").decimalValue()).isEqualByComparingTo("4.75");
        assertThat(card(all, green).get("ratingCount").asInt()).isEqualTo(4);
        assertThat(card(all, fresh).get("rating").isNull()).isTrue();
        assertThat(card(all, fresh).get("ratingCount").asInt()).isZero();
        mvc.perform(get("/api/catalog/restaurants/{id}", green))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4.75))
                .andExpect(jsonPath("$.ratingCount").value(4));

        assertThat(names(search("/api/search/restaurants", "sort", "rating")))
                .containsExactly("Green Leaf", "Spice Route", "Highway Dhaba", "Fresh Bowl");
        assertThat(names(search("/api/search/restaurants", "minRating", "3.5")))
                .containsExactlyInAnyOrder("Green Leaf", "Spice Route");
        assertThat(names(search("/api/search/restaurants", "minRating", "4", "sort", "rating")))
                .containsExactly("Green Leaf");
        assertThat(names(search("/api/search/restaurants", "minRating", "0", "sort", "rating")))
                .containsExactly("Green Leaf", "Spice Route", "Highway Dhaba");
        // with a location, rating sorts first and the distance is still reported
        JsonNode near = search("/api/search/restaurants", "lat", String.valueOf(LAT), "lng", String.valueOf(LNG),
                "sort", "rating");
        assertThat(names(near)).containsExactly("Green Leaf", "Spice Route", "Highway Dhaba", "Fresh Bowl");
        assertThat(card(near, green).get("distanceKm").asDouble()).isEqualTo(2.0);
        // with a query, filters and rating sort combine
        assertThat(names(search("/api/search/restaurants", "q", "thali", "minRating", "1", "sort", "rating")))
                .containsExactly("Green Leaf", "Spice Route", "Highway Dhaba");

        for (String bad : List.of("-1", "5.5", "NaN", "high")) {
            mvc.perform(get("/api/search/restaurants").param("minRating", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }
        mvc.perform(get("/api/search/restaurants").param("sort", "distance"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void listingUpdatesKeepTheRestaurantRatingAndOlderListingsAreIgnored() throws Exception {
        String spice = id();
        rated("RESTAURANT", spice, 1, 0, 0, 0, 0, 2);             // before the listing: hidden placeholder
        refresh();
        assertThat(search("/api/search/restaurants").get("content")).isEmpty();
        mvc.perform(get("/api/catalog/restaurants/{id}", spice)).andExpect(status().isNotFound());

        publishRestaurantEvent("RestaurantListingUpdated", spice, restaurant(spice, "Spice Route", 2, LAT, LNG,
                List.of(item("Chicken Biryani", "249.00", "NON_VEG"))));
        Map<String, Object> older = restaurant(spice, "Old Name", 1, LAT, LNG, List.of());
        publishRestaurantEvent("RestaurantListingUpdated", spice, older);
        refresh();
        mvc.perform(get("/api/catalog/restaurants/{id}", spice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Spice Route"))
                .andExpect(jsonPath("$.rating").value(5.0))
                .andExpect(jsonPath("$.ratingCount").value(2));

        Map<String, Object> renamed = restaurant(spice, "Spice Route Express", 3, LAT, LNG,
                List.of(item("Veg Biryani", "199.00", "VEG")));
        publishRestaurantEvent("RestaurantListingUpdated", spice, renamed);
        rated("RESTAURANT", spice, 2, 0, 0, 0, 1, 2);
        refresh();
        mvc.perform(get("/api/catalog/restaurants/{id}", spice))
                .andExpect(jsonPath("$.name").value("Spice Route Express"))
                .andExpect(jsonPath("$.pureVeg").value(true))
                .andExpect(jsonPath("$.rating").value(4.67))
                .andExpect(jsonPath("$.ratingCount").value(3));
        JsonObject source = stored("restaurants", spice);
        assertThat(source.getInt("listingVersion")).isEqualTo(3);
        assertThat(source.getInt("ratingVersion")).isEqualTo(2);
        assertThat(source.getBoolean("visible")).isTrue();
    }

    @Test
    void aRestaurantIndexedBeforePhase12KeepsWorking() throws Exception {
        // restaurants_v1 as Phase 3 created it (no rating fields), with a document written the old way: the whole
        // listing with listingVersion as Elasticsearch's external version
        ObjectNode definition = (ObjectNode) json.readTree(indexManager.definitionJson(IndexDefinition.RESTAURANTS));
        ObjectNode properties = (ObjectNode) definition.at("/mappings/properties");
        for (String field : RestaurantDocument.RATING_FIELDS) {
            properties.remove(field);
        }
        elasticsearch.indices().delete(d -> d.index("restaurants_v1"));
        elasticsearch.indices().create(c -> c.index("restaurants_v1")
                .withJson(new StringReader(json.writeValueAsString(definition)))
                .aliases("restaurants", a -> a.isWriteIndex(true)));
        String spice = id();
        Map<String, Object> oldDocument = Map.of("restaurantId", spice, "name", "Spice Route", "status", "APPROVED",
                "visible", true, "acceptingOrders", true, "listingVersion", 5);
        elasticsearch.index(i -> i.index("restaurants").id(spice).version(5L).versionType(VersionType.External)
                .document(oldDocument));
        assertThat(elasticsearch.indices().getMapping(m -> m.index("restaurants_v1")).get("restaurants_v1")
                .mappings().properties()).doesNotContainKey("rating");

        indexManager.recheck(IndexDefinition.RESTAURANTS);         // what a restart of the new release does
        assertThat(elasticsearch.indices().getMapping(m -> m.index("restaurants_v1")).get("restaurants_v1")
                .mappings().properties()).containsKeys("rating", "ratingCount", "ratingVersion");

        rated("RESTAURANT", spice, 1, 0, 0, 0, 1, 0);
        publishRestaurantEvent("RestaurantListingUpdated", spice, restaurant(spice, "Spice Route", 4, LAT, LNG,
                List.of()));                                      // older than the stored v5: ignored
        refresh();
        mvc.perform(get("/api/catalog/restaurants/{id}", spice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4.0));
        publishRestaurantEvent("RestaurantListingUpdated", spice, restaurant(spice, "Spice Route Cafe", 6, LAT, LNG,
                List.of(item("Masala Dosa", "120.00", "VEG"))));
        refresh();
        mvc.perform(get("/api/catalog/restaurants/{id}", spice))
                .andExpect(jsonPath("$.name").value("Spice Route Cafe"))
                .andExpect(jsonPath("$.rating").value(4.0));
        assertThat(names(search("/api/search/restaurants", "sort", "rating", "minRating", "4")))
                .containsExactly("Spice Route Cafe");
    }

    @Test
    void anIndexCreatedBeforePhase12GetsTheProductRatingFieldsAdded() throws Exception {
        ObjectNode definition = (ObjectNode) json.readTree(indexManager.definitionJson(IndexDefinition.PRODUCTS));
        ObjectNode properties = (ObjectNode) definition.at("/mappings/properties");
        List.of("rating", "ratingCount", "ratingVersion").forEach(properties::remove);
        elasticsearch.indices().delete(d -> d.index("products_v1"));
        elasticsearch.indices().create(c -> c.index("products_v1")
                .withJson(new StringReader(json.writeValueAsString(definition)))
                .aliases("products", a -> a.isWriteIndex(true)));

        indexManager.recheck(IndexDefinition.PRODUCTS);
        assertThat(elasticsearch.indices().getMapping(m -> m.index("products_v1")).get("products_v1").mappings()
                .properties()).containsKeys("rating", "ratingCount", "ratingVersion", "sellingPrice");
        String tomato = indexedProduct("Tomato");
        rated("PRODUCT", tomato, 1, 0, 0, 0, 0, 1);
        refresh();
        assertThat(names(search("/api/search/products", "sort", "rating"))).containsExactly("Tomato");
    }

    @Test
    void otherTargetTypesAndOtherReviewEventsAreIgnored() throws Exception {
        for (String type : List.of("MENU_ITEM", "VENDOR", "DELIVERY_PARTNER")) {
            String target = id();
            rated(type, target, 1, 0, 0, 0, 0, 1);
            assertThat(elasticsearch.exists(e -> e.index("products").id(target)).value()).isFalse();
            assertThat(elasticsearch.exists(e -> e.index("restaurants").id(target)).value()).isFalse();
        }
        String tomato = indexedProduct("Tomato");
        publishReviewEvent("ReviewSubmitted", id(), Map.of("reviewId", id(), "targetType", "PRODUCT",
                "targetId", tomato, "rating", 5, "status", "PUBLISHED"));
        refresh();
        assertThat(card(search("/api/search/products"), tomato).get("ratingCount").asInt()).isZero();
    }

    @Test
    void brokenSummariesAreInvalid() {
        String tomato = id();
        Map<String, Object> noVersion = ratingSummary("PRODUCT", tomato, 1, 0, 0, 0, 0, 1);
        noVersion.remove("summaryVersion");
        Map<String, Object> badId = ratingSummary("PRODUCT", "not-a-uuid", 1, 0, 0, 0, 0, 1);
        Map<String, Object> noCount = ratingSummary("RESTAURANT", tomato, 1, 0, 0, 0, 0, 1);
        noCount.put("ratingCount", null);
        Map<String, Object> tooHigh = ratingSummary("PRODUCT", tomato, 1, 0, 0, 0, 0, 1);
        tooHigh.put("averageRating", 7.5);
        Map<String, Object> negative = ratingSummary("PRODUCT", tomato, 1);
        negative.put("ratingCount", -1);
        for (Map<String, Object> broken : List.of(noVersion, badId, noCount, tooHigh, negative)) {
            assertThatThrownBy(() -> publishReviewEvent("RatingSummaryUpdated", tomato, broken))
                    .as(broken.toString())
                    .isInstanceOf(InvalidEventException.class);
        }
    }
}
