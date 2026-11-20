package com.grocery.catalogquery;

import com.grocery.catalogquery.TestEvents.Cat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.catalogquery.TestEvents.category;
import static com.grocery.catalogquery.TestEvents.product;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryTreeAndAdminIntegrationTest extends AbstractIntegrationTest {

    private final Cat fruitsAndVegetables = Cat.of("Fruits & Vegetables");
    private final Cat freshVegetables = Cat.of("Fresh Vegetables");
    private final Cat freshFruits = Cat.of("Fresh Fruits");
    private final Cat exotic = Cat.of("Exotic Vegetables");
    private final Cat dairy = Cat.of("Dairy");
    private final Cat discontinued = Cat.of("Discontinued");
    private final String vendor = id();

    private void publishCategory(List<Cat> path, int sortOrder, long version) {
        publishCatalogEvent("CategoryChanged", path.getLast().id(), category(path, sortOrder, version));
    }

    private void publishProduct(String name, List<Cat> path) {
        String productId = id();
        publishCatalogEvent("ProductUpserted", productId, product(productId, vendor, name, 1, path));
    }

    @Test
    void activeCategoriesFormATreeWithVisibleProductCountsOfTheWholeSubtree() throws Exception {
        publishCategory(List.of(fruitsAndVegetables), 1, 1);
        publishCategory(List.of(fruitsAndVegetables, freshVegetables), 0, 1);
        publishCategory(List.of(fruitsAndVegetables, freshFruits), 0, 1);
        publishCategory(List.of(fruitsAndVegetables, freshVegetables, exotic), 5, 1);
        publishCategory(List.of(dairy), 0, 1);
        Map<String, Object> inactive = category(List.of(discontinued), 0, 1);
        inactive.put("active", false);
        inactive.put("effectiveActive", false);
        publishCatalogEvent("CategoryChanged", discontinued.id(), inactive);

        publishProduct("Tomato", List.of(fruitsAndVegetables, freshVegetables));
        publishProduct("Onion", List.of(fruitsAndVegetables, freshVegetables));
        publishProduct("Avocado", List.of(fruitsAndVegetables, freshVegetables, exotic));
        publishProduct("Banana", List.of(fruitsAndVegetables, freshFruits));
        String hidden = id();
        Map<String, Object> unpublished = product(hidden, vendor, "Apple", 1, List.of(fruitsAndVegetables, freshFruits));
        unpublished.put("status", "UNPUBLISHED");
        publishCatalogEvent("ProductUpserted", hidden, unpublished);
        refresh();

        JsonNode tree = body(mvc.perform(get("/api/catalog/categories")).andExpect(status().isOk()));
        // sorted by sortOrder, then name: Dairy (0) before Fruits & Vegetables (1); inactive categories left out
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("name").asString()).isEqualTo("Dairy");
        assertThat(tree.get(0).get("productCount").asLong()).isZero();
        assertThat(tree.get(0).get("children")).isEmpty();

        JsonNode root = tree.get(1);
        assertThat(root.get("id").asString()).isEqualTo(fruitsAndVegetables.id());
        assertThat(root.get("productCount").asLong()).isEqualTo(4);
        assertThat(root.get("description").isNull()).isTrue();
        assertThat(root.get("children")).hasSize(2);
        assertThat(root.get("children").get(0).get("name").asString()).isEqualTo("Fresh Fruits");
        assertThat(root.get("children").get(0).get("productCount").asLong()).isEqualTo(1);
        JsonNode vegetables = root.get("children").get(1);
        assertThat(vegetables.get("name").asString()).isEqualTo("Fresh Vegetables");
        assertThat(vegetables.get("productCount").asLong()).isEqualTo(3);
        assertThat(vegetables.get("children").get(0).get("name").asString()).isEqualTo("Exotic Vegetables");
        assertThat(vegetables.get("children").get(0).get("productCount").asLong()).isEqualTo(1);
    }

    @Test
    void categoryChangesAreVersionedAndDeletionsRemoveTheCategory() throws Exception {
        publishCategory(List.of(dairy), 0, 2);
        Map<String, Object> stale = category(List.of(new Cat(dairy.id(), "Old Dairy Name")), 0, 1);
        publishCatalogEvent("CategoryChanged", dairy.id(), stale);
        refresh();
        mvc.perform(get("/api/catalog/categories"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Dairy"));

        Map<String, Object> deleted = category(List.of(dairy), 0, 3);
        deleted.put("deleted", true);
        publishCatalogEvent("CategoryChanged", dairy.id(), deleted);
        // a stale replay of the deletion and of an older snapshot are harmless
        publishCatalogEvent("CategoryChanged", dairy.id(), deleted);
        publishCatalogEvent("CategoryChanged", dairy.id(), category(List.of(dairy), 0, 2));
        refresh();
        mvc.perform(get("/api/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void indexStatusIsForAdminsOnly() throws Exception {
        mvc.perform(get("/api/admin/search/indexes"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/search/indexes").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());

        publishProduct("Tomato", List.of(fruitsAndVegetables));
        refresh();
        mvc.perform(get("/api/admin/search/indexes").with(as(UUID.randomUUID(), "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].alias").value("products"))
                .andExpect(jsonPath("$[0].index").value("products_v1"))
                .andExpect(jsonPath("$[0].documents").value(1))
                .andExpect(jsonPath("$[0].health").value("green"))
                .andExpect(jsonPath("$[1].alias").value("restaurants"))
                .andExpect(jsonPath("$[1].index").value("restaurants_v1"))
                .andExpect(jsonPath("$[2].alias").value("categories"))
                .andExpect(jsonPath("$[2].index").value("categories_v1"))
                .andExpect(jsonPath("$[2].documents").value(0));
    }

    @Test
    void catalogPagesArePublic() throws Exception {
        mvc.perform(get("/api/catalog/categories")).andExpect(status().isOk());
        mvc.perform(get("/api/catalog/products/{id}", id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/catalog/restaurants/{id}", id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/search/restaurants")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}
