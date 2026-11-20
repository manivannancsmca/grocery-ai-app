package com.grocery.catalogquery;

import com.grocery.catalogquery.TestEvents.Cat;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.grocery.catalogquery.TestEvents.availability;
import static com.grocery.catalogquery.TestEvents.price;
import static com.grocery.catalogquery.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5: {@code POST /internal/catalog/products/lookup} — the cart's product lookup (one multi-get through the
 * {@code products} alias; docs/api/phase-5-cart-and-checkout-preview.md §4).
 */
class ProductLookupIntegrationTest extends AbstractIntegrationTest {

    private static final String LOOKUP = "/internal/catalog/products/lookup";

    private final List<Cat> vegetables = List.of(Cat.of("Fruits & Vegetables"), Cat.of("Fresh Vegetables"));
    private final String vendor = id();
    private final UUID caller = UUID.randomUUID();

    private String index(String name) {
        String productId = id();
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, name, 1, vegetables));
        return productId;
    }

    /** A product snapshot with one field changed (e.g. status UNPUBLISHED, vendorActive false). */
    private String indexWith(String name, String field, Object value) {
        String productId = id();
        Map<String, Object> snapshot = product(productId, vendor, name, 1, vegetables);
        snapshot.put(field, value);
        publishCatalogEvent("ProductUpserted", productId, snapshot);
        return productId;
    }

    private ResultActions lookup(String... productIds) throws Exception {
        return lookupBody("{\"productIds\":[" + Arrays.stream(productIds).map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}");
    }

    private ResultActions lookupBody(String body) throws Exception {
        return mvc.perform(post(LOOKUP).with(as(caller, "CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    @Test
    void visibleProductsComeBackAsTheSameCardsSearchShowsInRequestOrderWithoutDuplicates() throws Exception {
        String tomato = index("Tomato");
        String onion = index("Onion");                                  // never priced, never stocked
        publishPricingEvent("PriceChanged", tomato, price(tomato, vendor, "60.00", "49.00", 1));
        publishInventoryEvent("ProductAvailabilityChanged", tomato, availability(tomato, vendor, true, 1));
        // no refresh: a multi-get is real-time

        ResultActions result = lookup(onion, tomato, onion)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(onion))
                .andExpect(jsonPath("$.items[0].name").value("Onion"))
                .andExpect(jsonPath("$.items[0].price").isEmpty())
                .andExpect(jsonPath("$.items[0].inStock").value(false))
                .andExpect(jsonPath("$.items[1].id").value(tomato))
                .andExpect(jsonPath("$.items[1].unitLabel").value("500 g"))
                .andExpect(jsonPath("$.items[1].foodType").value("VEG"))
                .andExpect(jsonPath("$.items[1].primaryImageUrl").value("https://img.example.com/" + tomato + ".jpg"))
                .andExpect(jsonPath("$.items[1].brand.name").value("Farm Fresh"))
                .andExpect(jsonPath("$.items[1].category.id").value(vegetables.getLast().id()))
                .andExpect(jsonPath("$.items[1].category.name").value("Fresh Vegetables"))
                .andExpect(jsonPath("$.items[1].vendor.id").value(vendor))
                .andExpect(jsonPath("$.items[1].vendor.name").value("Green Basket"))
                .andExpect(jsonPath("$.items[1].price.mrp").value(60.0))
                .andExpect(jsonPath("$.items[1].price.sellingPrice").value(49.0))
                .andExpect(jsonPath("$.items[1].price.currency").value("INR"))
                .andExpect(jsonPath("$.items[1].price.discountPercent").value(18))
                .andExpect(jsonPath("$.items[1].inStock").value(true))
                .andExpect(jsonPath("$.missing").isEmpty());
        assertThat(result.andReturn().getResponse().getContentAsString())
                .contains("\"mrp\":60.00", "\"sellingPrice\":49.00");          // always 2 decimals
        JsonNode response = body(result);

        // exactly the card the public search returns for the same products
        refresh();
        JsonNode search = body(mvc.perform(get("/api/search/products").param("sort", "name_asc"))
                .andExpect(status().isOk()));
        assertThat(search.get("content").get(0)).isEqualTo(response.get("items").get(0));     // Onion
        assertThat(search.get("content").get(1)).isEqualTo(response.get("items").get(1));     // Tomato
    }

    @Test
    void unknownHiddenAndPlaceholderProductsAreMissing() throws Exception {
        String visible = index("Tomato");
        String unpublished = indexWith("Carrot", "status", "UNPUBLISHED");
        String archived = indexWith("Beetroot", "status", "ARCHIVED");
        String vendorInactive = indexWith("Potato", "vendorActive", false);
        String categoryInactive = indexWith("Okra", "categoryActive", false);
        String placeholder = id();                                      // price and stock arrived, product did not
        publishPricingEvent("PriceChanged", placeholder, price(placeholder, vendor, "120.00", "99.00", 1));
        publishInventoryEvent("ProductAvailabilityChanged", placeholder, availability(placeholder, vendor, true, 1));
        String unknown = id();

        JsonNode response = body(lookup(unpublished, visible, archived, vendorInactive, categoryInactive,
                placeholder, unknown, unknown)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(visible)));
        assertThat(strings(response.get("missing"))).containsExactly(unpublished, archived, vendorInactive,
                categoryInactive, placeholder, unknown);

        // publishing the product makes it visible to the lookup at once
        publishCatalogEvent("ProductUpserted", unpublished, product(unpublished, vendor, "Carrot", 2, vegetables));
        lookup(unpublished)
                .andExpect(jsonPath("$.items[0].name").value("Carrot"))
                .andExpect(jsonPath("$.missing").isEmpty());
    }

    @Test
    void between1And100IdsAreAccepted() throws Exception {
        String tomato = index("Tomato");
        String[] hundred = IntStream.range(0, 100).mapToObj(i -> i == 0 ? tomato : id()).toArray(String[]::new);
        lookup(hundred)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.missing.length()").value(99));

        String[] tooMany = IntStream.range(0, 101).mapToObj(i -> id()).toArray(String[]::new);
        lookup(tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));
        for (String body : List.of("{\"productIds\":[]}", "{}", "{\"productIds\":null}",
                "{\"productIds\":[\"" + tomato + "\",null]}")) {
            lookupBody(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        lookupBody("{\"productIds\":[\"not-a-uuid\"]}").andExpect(status().isBadRequest());
        lookupBody("not json").andExpect(status().isBadRequest());
    }

    @Test
    void aMissingIndexMeansEverythingIsMissingAndTheIndexIsCreatedAgain() throws Exception {
        String tomato = index("Tomato");
        elasticsearch.indices().delete(d -> d.index("products_v1"));

        lookup(tomato)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.missing[0]").value(tomato));

        assertThat(elasticsearch.indices().getAlias(a -> a.name("products")).aliases()).containsOnlyKeys("products_v1");
        assertThat(elasticsearch.indices().getMapping(m -> m.index("products_v1")).get("products_v1").mappings()
                .dynamic().jsonValue()).isEqualTo("strict");
        publishCatalogEvent("ProductUpserted", tomato, product(tomato, vendor, "Tomato", 1, vegetables));
        lookup(tomato).andExpect(jsonPath("$.items[0].name").value("Tomato"));
    }

    @Test
    void internalPathsNeedATokenButNoParticularRole() throws Exception {
        String tomato = index("Tomato");
        String body = "{\"productIds\":[\"" + tomato + "\"]}";

        mvc.perform(post(LOOKUP).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        for (String role : List.of("CUSTOMER", "VENDOR", "OPS_ADMIN")) {
            mvc.perform(post(LOOKUP).with(as(UUID.randomUUID(), role))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].id").value(tomato));
        }
        // the public reads stay public
        mvc.perform(get("/api/catalog/products/{id}", tomato)).andExpect(status().isOk());
    }
}
