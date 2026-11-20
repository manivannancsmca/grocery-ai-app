package com.grocery.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CategoryIntegrationTest extends AbstractIntegrationTest {

    private final UUID vendorId = UUID.randomUUID();

    private String productIn(String categoryId, String name) throws Exception {
        return body(mvc.perform(post("/api/vendor/products").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","categoryId":"%s","unitValue":1,"unitOfMeasure":"L"}"""
                                .formatted(name, categoryId)))
                .andExpect(status().isCreated())).get("id").asString();
    }

    private org.springframework.test.web.servlet.ResultActions putCategory(String id, String body) throws Exception {
        return mvc.perform(put("/api/admin/categories/{id}", id).with(opsAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode findNode(JsonNode forest, String id) {
        for (JsonNode node : forest) {
            if (node.get("id").asString().equals(id)) {
                return node;
            }
            JsonNode inChildren = findNode(node.get("children"), id);
            if (inChildren != null) {
                return inChildren;
            }
        }
        return null;
    }

    @Test
    void createReturnsNodeAndPublishesCategoryChanged() throws Exception {
        String rootName = unique("Dairy");
        String root = createCategory(rootName, null);
        mvc.perform(post("/api/admin/categories").with(opsAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" Milk ","parentId":"%s","description":"Fresh milk",
                                 "imageUrl":"https://img.example.com/milk.png","sortOrder":5}""".formatted(root)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Milk"))
                .andExpect(jsonPath("$.parentId").value(root))
                .andExpect(jsonPath("$.depth").value(2))
                .andExpect(jsonPath("$.sortOrder").value(5))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.productCount").value(0))
                .andExpect(jsonPath("$.children.length()").value(0));

        JsonNode event = categoryEvents(root).getLast();
        assertThat(event.get("name").asString()).isEqualTo(rootName);
        assertThat(event.get("parentId").isNull()).isTrue();
        assertThat(event.get("depth").asInt()).isEqualTo(1);
        assertThat(event.get("path")).hasSize(1);
        assertThat(event.get("active").asBoolean()).isTrue();
        assertThat(event.get("effectiveActive").asBoolean()).isTrue();
        assertThat(event.get("deleted").asBoolean()).isFalse();
        assertThat(event.get("categoryVersion").asLong()).isEqualTo(1);
    }

    @Test
    void depthIsLimitedToThreeLevelsAlsoWhenMovingASubtree() throws Exception {
        String a = createCategory(unique("A"), null);
        String ab = createCategory("B", a);
        String abc = createCategory("C", ab);
        mvc.perform(post("/api/admin/categories").with(opsAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"D\",\"parentId\":\"%s\"}".formatted(abc)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_TOO_DEEP"));

        // x (root) → y : moving x (height 2) under ab (depth 2) would make y depth 4
        String x = createCategory(unique("X"), null);
        String y = createCategory("Y", x);
        putCategory(x, "{\"name\":\"X moved\",\"parentId\":\"%s\"}".formatted(ab))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_TOO_DEEP"));
        // under a root (depth 1) it fits: x depth 2, y depth 3
        putCategory(x, "{\"name\":\"X moved\",\"parentId\":\"%s\"}".formatted(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depth").value(2))
                .andExpect(jsonPath("$.children[0].id").value(y))
                .andExpect(jsonPath("$.children[0].depth").value(3));
        JsonNode yEvent = categoryEvents(y).getLast();
        assertThat(yEvent.get("depth").asInt()).isEqualTo(3);
        assertThat(yEvent.get("path")).extracting(n -> n.get("id").asString()).containsExactly(a, x, y);
    }

    @Test
    void movingUnderItselfOrADescendantIsACycle() throws Exception {
        String a = createCategory(unique("A"), null);
        String b = createCategory("B", a);
        String c = createCategory("C", b);
        putCategory(a, "{\"name\":\"A\",\"parentId\":\"%s\"}".formatted(c))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_CYCLE"));
        putCategory(b, "{\"name\":\"B\",\"parentId\":\"%s\"}".formatted(b))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_CYCLE"));
        putCategory(b, "{\"name\":\"B\",\"parentId\":\"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"));
        putCategory(UUID.randomUUID().toString(), "{\"name\":\"B\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void siblingNamesAreUniqueCaseInsensitively() throws Exception {
        String name = unique("Bakery");
        String root = createCategory(name, null);
        mvc.perform(post("/api/admin/categories").with(opsAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NAME_TAKEN"));

        String bread = createCategory("Bread", root);
        String other = createCategory(unique("Other"), null);
        createCategory("bread", other);                       // same name, different parent: fine
        String cakes = createCategory("Cakes", root);
        putCategory(cakes, "{\"name\":\"BREAD\",\"parentId\":\"%s\"}".formatted(root))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NAME_TAKEN"));
        putCategory(bread, "{\"name\":\"bread\",\"parentId\":\"%s\"}".formatted(other))   // move into a clash
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NAME_TAKEN"));
        putCategory(bread, "{\"name\":\"BREAD\",\"parentId\":\"%s\"}".formatted(root))   // own name, other case
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("BREAD"));
        mvc.perform(post("/api/admin/categories").with(opsAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rolls\",\"parentId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"));
    }

    @Test
    void onlyEmptyCategoriesCanBeDeleted() throws Exception {
        String root = createCategory(unique("Snacks"), null);
        String chips = createCategory("Chips", root);
        mvc.perform(delete("/api/admin/categories/{id}", root).with(opsAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_IN_USE"));

        String product = productIn(chips, "Salted Chips");
        mvc.perform(delete("/api/admin/categories/{id}", chips).with(opsAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_IN_USE"));

        // Archived products do not block the delete; they lose their category
        mvc.perform(delete("/api/vendor/products/{id}", product).with(vendor(vendorId))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/categories/{id}", chips).with(opsAdmin())).andExpect(status().isNoContent());
        List<JsonNode> events = categoryEvents(chips);
        assertThat(events.getLast().get("deleted").asBoolean()).isTrue();
        assertThat(events.getLast().get("categoryVersion").asLong()).isGreaterThan(events.get(events.size() - 2).get("categoryVersion").asLong());
        assertThat(events.getLast().get("path")).extracting(n -> n.get("id").asString()).containsExactly(root, chips);
        mvc.perform(get("/api/vendor/products/{id}", product).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.category").isEmpty());
        mvc.perform(delete("/api/admin/categories/{id}", chips).with(opsAdmin())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/categories/{id}", root).with(opsAdmin())).andExpect(status().isNoContent());
    }

    @Test
    void deactivatingAParentHidesTheSubtreeAndItsProducts() throws Exception {
        String root = createCategory(unique("Beverages"), null);
        String tea = createCategory("Tea", root);
        String green = createCategory("Green Tea", tea);
        String product = productIn(green, "Sencha");
        mvc.perform(post("/api/vendor/products/{id}/publish", product).with(vendor(vendorId))).andExpect(status().isOk());
        long versionBefore = lastProductEvent(product).get("productVersion").asLong();
        long greenVersionBefore = categoryEvents(green).getLast().get("categoryVersion").asLong();

        mvc.perform(post("/api/admin/categories/{id}/deactivate", root).with(opsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.children[0].id").value(tea));

        JsonNode rootEvent = categoryEvents(root).getLast();
        assertThat(rootEvent.get("active").asBoolean()).isFalse();
        assertThat(rootEvent.get("effectiveActive").asBoolean()).isFalse();
        for (String descendant : List.of(tea, green)) {
            JsonNode event = categoryEvents(descendant).getLast();
            assertThat(event.get("active").asBoolean()).isTrue();
            assertThat(event.get("effectiveActive").asBoolean()).isFalse();
        }
        assertThat(categoryEvents(green).getLast().get("categoryVersion").asLong()).isGreaterThan(greenVersionBefore);

        JsonNode snapshot = lastProductEvent(product);
        assertThat(snapshot.get("productVersion").asLong()).isGreaterThan(versionBefore);
        assertThat(snapshot.get("categoryActive").asBoolean()).isFalse();
        assertThat(snapshot.get("status").asString()).isEqualTo("PUBLISHED");

        // Products can still be created in an inactive category, but not published
        String draft = productIn(tea, "Assam");
        assertThat(lastProductEvent(draft).get("categoryActive").asBoolean()).isFalse();
        mvc.perform(post("/api/vendor/products/{id}/publish", draft).with(vendor(vendorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_INACTIVE"));
        mvc.perform(get("/api/vendor/products/{id}", draft).with(vendor(vendorId)))
                .andExpect(jsonPath("$.category.active").value(false));

        // Deactivating twice changes nothing
        int eventsBefore = categoryEvents(root).size();
        mvc.perform(post("/api/admin/categories/{id}/deactivate", root).with(opsAdmin())).andExpect(status().isOk());
        assertThat(categoryEvents(root)).hasSize(eventsBefore);

        mvc.perform(post("/api/admin/categories/{id}/activate", root).with(opsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        assertThat(categoryEvents(green).getLast().get("effectiveActive").asBoolean()).isTrue();
        assertThat(lastProductEvent(product).get("categoryActive").asBoolean()).isTrue();
        mvc.perform(post("/api/vendor/products/{id}/publish", draft).with(vendor(vendorId)))
                .andExpect(status().isOk());
    }

    @Test
    void renamingAParentReSnapshotsTheSubtree() throws Exception {
        String root = createCategory(unique("Staples"), null);
        String flour = createCategory("Flour", root);
        String product = productIn(flour, "Atta");
        long versionBefore = lastProductEvent(product).get("productVersion").asLong();
        String newName = unique("Staples & Grains");

        putCategory(root, "{\"name\":\"%s\",\"description\":\"Daily staples\",\"sortOrder\":3}".formatted(newName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.description").value("Daily staples"))
                .andExpect(jsonPath("$.children[0].productCount").value(1));

        JsonNode snapshot = lastProductEvent(product);
        assertThat(snapshot.get("productVersion").asLong()).isEqualTo(versionBefore + 1);
        assertThat(snapshot.get("categoryPath").get(0).get("name").asString()).isEqualTo(newName);
        assertThat(categoryEvents(flour).getLast().get("path").get(0).get("name").asString()).isEqualTo(newName);

        // Changing only the description touches the category itself, not its subtree
        int flourEvents = categoryEvents(flour).size();
        putCategory(root, "{\"name\":\"%s\",\"description\":\"Rice, flour, pulses\"}".formatted(newName))
                .andExpect(status().isOk());
        assertThat(categoryEvents(flour)).hasSize(flourEvents);
        assertThat(lastProductEvent(product).get("productVersion").asLong()).isEqualTo(versionBefore + 1);
        assertThat(categoryEvents(root).getLast().get("description").asString()).isEqualTo("Rice, flour, pulses");
    }

    @Test
    void treeIncludesInactiveCategoriesAndRolesAreEnforced() throws Exception {
        String root = createCategory(unique("Household"), null);
        String cleaners = createCategory("Cleaners", root);
        productIn(cleaners, "Floor Cleaner");
        productIn(cleaners, "Glass Cleaner");
        mvc.perform(post("/api/admin/categories/{id}/deactivate", cleaners).with(opsAdmin())).andExpect(status().isOk());

        JsonNode forest = body(mvc.perform(get("/api/admin/categories").with(as(admin, "SUPPORT")))
                .andExpect(status().isOk()));
        JsonNode node = findNode(forest, cleaners);
        assertThat(node).isNotNull();
        assertThat(node.get("active").asBoolean()).isFalse();
        assertThat(node.get("productCount").asLong()).isEqualTo(2);
        assertThat(node.get("depth").asInt()).isEqualTo(2);
        assertThat(StreamSupport.stream(forest.spliterator(), false).map(n -> n.get("depth").asInt()))
                .containsOnly(1);

        mvc.perform(post("/api/admin/categories").with(as(admin, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/categories/{id}/activate", cleaners).with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/categories").with(vendor(vendorId)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/categories").with(as(admin, "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"sortOrder\":10000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
    }
}
