package com.grocery.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VendorProductIntegrationTest extends AbstractIntegrationTest {

    private final UUID vendorId = UUID.randomUUID();
    private String rootName;
    private String rootId;
    private String leafId;

    @BeforeEach
    void categories() throws Exception {
        rootName = unique("Fruits & Vegetables");
        rootId = createCategory(rootName, null);
        leafId = createCategory("Fresh Vegetables", rootId);
    }

    private String productJson(String name, String sku, String brand) {
        return """
                {"name":"%s","description":"Farm-fresh red tomatoes","categoryId":"%s",
                 "brandName":%s,"unitValue":500,"unitOfMeasure":"G","sku":%s,"barcode":"8901234567890",
                 "foodType":"VEG","tags":["Organic"," fresh ","organic"],
                 "attributes":[{"name":"Origin","value":"Nashik"}],
                 "imageUrls":["https://img.example.com/tomato-1.jpg","https://img.example.com/tomato-2.jpg"]}"""
                .formatted(name, leafId, brand == null ? "null" : "\"" + brand + "\"",
                        sku == null ? "null" : "\"" + sku + "\"");
    }

    private String createProduct(UUID vendor, String json) throws Exception {
        return body(mvc.perform(post("/api/vendor/products").with(vendor(vendor))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())).get("id").asString();
    }

    @Test
    void fullLifecyclePublishesSnapshotsWithStrictlyIncreasingVersions() throws Exception {
        String brand = unique("Farm Fresh");
        JsonNode created = body(mvc.perform(post("/api/vendor/products").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("Tomato", "TOM-500", brand)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.unitValue").value(500))
                .andExpect(jsonPath("$.unitLabel").value("500 g"))
                .andExpect(jsonPath("$.category.id").value(leafId))
                .andExpect(jsonPath("$.category.path[0]").value(rootName))
                .andExpect(jsonPath("$.category.path[1]").value("Fresh Vegetables"))
                .andExpect(jsonPath("$.category.active").value(true))
                .andExpect(jsonPath("$.brand.name").value(brand))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0]").value("organic"))
                .andExpect(jsonPath("$.tags[1]").value("fresh"))
                .andExpect(jsonPath("$.attributes[0].name").value("Origin"))
                .andExpect(jsonPath("$.imageUrls.length()").value(2))
                .andExpect(jsonPath("$.publishedAt").isEmpty()));
        String id = created.get("id").asString();

        mvc.perform(put("/api/vendor/products/{id}", id).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Tomato Hybrid", "TOM-500", brand).replace("\"unitValue\":500", "\"unitValue\":1.5")
                                .replace("\"unitOfMeasure\":\"G\"", "\"unitOfMeasure\":\"KG\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tomato Hybrid"))
                .andExpect(jsonPath("$.unitLabel").value("1.5 kg"));

        mvc.perform(post("/api/vendor/products/{id}/publish", id).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").isNotEmpty());
        mvc.perform(post("/api/vendor/products/{id}/publish", id).with(vendor(vendorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));
        mvc.perform(post("/api/vendor/products/{id}/unpublish", id).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNPUBLISHED"));
        mvc.perform(delete("/api/vendor/products/{id}", id).with(vendor(vendorId)))
                .andExpect(status().isNoContent());

        // Archived products are frozen
        mvc.perform(put("/api/vendor/products/{id}", id).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("Tomato", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_ARCHIVED"));
        mvc.perform(post("/api/vendor/products/{id}/publish", id).with(vendor(vendorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));
        mvc.perform(delete("/api/vendor/products/{id}", id).with(vendor(vendorId)))
                .andExpect(status().isConflict());

        List<JsonNode> events = productEvents(id);
        assertThat(events).extracting(e -> e.get("status").asString())
                .containsExactly("DRAFT", "DRAFT", "PUBLISHED", "UNPUBLISHED", "ARCHIVED");
        assertThat(events).extracting(e -> e.get("productVersion").asLong()).containsExactly(1L, 2L, 3L, 4L, 5L);

        JsonNode published = events.get(2);
        assertThat(published.get("productId").asString()).isEqualTo(id);
        assertThat(published.get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(published.get("vendorActive").asBoolean()).isTrue();
        assertThat(published.get("vendorName").isNull()).isTrue();      // no VendorApproved consumed yet
        assertThat(published.get("name").asString()).isEqualTo("Tomato Hybrid");
        assertThat(published.get("brand").get("name").asString()).isEqualTo(brand);
        assertThat(published.get("category").get("id").asString()).isEqualTo(leafId);
        assertThat(published.get("categoryPath")).hasSize(2);
        assertThat(published.get("categoryPath").get(0).get("id").asString()).isEqualTo(rootId);
        assertThat(published.get("categoryPath").get(1).get("name").asString()).isEqualTo("Fresh Vegetables");
        assertThat(published.get("categoryActive").asBoolean()).isTrue();
        assertThat(published.get("unitValue").isNumber()).isTrue();
        assertThat(published.get("unitValue").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(published.get("unitOfMeasure").asString()).isEqualTo("KG");
        assertThat(published.get("unitLabel").asString()).isEqualTo("1.5 kg");
        assertThat(published.get("sku").asString()).isEqualTo("TOM-500");
        assertThat(published.get("barcode").asString()).isEqualTo("8901234567890");
        assertThat(published.get("foodType").asString()).isEqualTo("VEG");
        assertThat(published.get("tags").get(0).asString()).isEqualTo("organic");
        assertThat(published.get("attributes").get(0).get("value").asString()).isEqualTo("Nashik");
        assertThat(published.get("imageUrls")).hasSize(2);
        assertThat(published.get("publishedAt").isString()).isTrue();
        assertThat(published.get("updatedAt").isString()).isTrue();

        Long storedVersion = JdbcClient.create(dataSource)
                .sql("SELECT product_version FROM product WHERE id = UUID_TO_BIN(?)").param(id)
                .query(Long.class).single();
        assertThat(storedVersion).isEqualTo(5L);
    }

    @Test
    void ownershipAndRolesAreEnforced() throws Exception {
        String id = createProduct(vendorId, productJson("Onion", null, null));

        UUID otherVendor = UUID.randomUUID();
        mvc.perform(get("/api/vendor/products/{id}", id).with(vendor(otherVendor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        mvc.perform(post("/api/vendor/products/{id}/publish", id).with(vendor(otherVendor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        mvc.perform(put("/api/vendor/products/{id}", id).with(vendor(otherVendor))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("Mine now", null, null)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/vendor/products/{id}", UUID.randomUUID()).with(vendor(vendorId)))
                .andExpect(status().isNotFound());

        // VENDOR role but not (yet / any more) approved: no vendor_id claim
        mvc.perform(get("/api/vendor/products").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(post("/api/vendor/products").with(as(UUID.randomUUID(), "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("X", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/products").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/vendor/products"))
                .andExpect(status().isUnauthorized());

        // Admins see every vendor's product, with the vendor name
        mvc.perform(get("/api/admin/products/{id}", id).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("Onion"))
                .andExpect(jsonPath("$.category.id").value(leafId))
                .andExpect(jsonPath("$.vendorName").isEmpty());
        mvc.perform(get("/api/admin/products").param("vendorId", vendorId.toString()).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get("/api/admin/products").with(vendor(vendorId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsFilterByStatusAndQueryAndHideArchivedByDefault() throws Exception {
        String tomato = createProduct(vendorId, productJson("Cherry Tomato", "CT-1", null));
        String onion = createProduct(vendorId, productJson("Red Onion", "RO-1", null));
        String garlic = createProduct(vendorId, productJson("Garlic", "GA-1", null));
        mvc.perform(post("/api/vendor/products/{id}/publish", tomato).with(vendor(vendorId))).andExpect(status().isOk());
        mvc.perform(delete("/api/vendor/products/{id}", garlic).with(vendor(vendorId))).andExpect(status().isNoContent());

        mvc.perform(get("/api/vendor/products").with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(tomato))     // most recently changed (published) first
                .andExpect(jsonPath("$.content[1].id").value(onion));
        mvc.perform(get("/api/vendor/products").param("status", "ARCHIVED").with(vendor(vendorId)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(garlic));
        mvc.perform(get("/api/vendor/products").param("status", "PUBLISHED").with(vendor(vendorId)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(tomato));
        mvc.perform(get("/api/vendor/products").param("q", "TOMATO").with(vendor(vendorId)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(tomato));
        mvc.perform(get("/api/vendor/products").param("q", "ro-1").with(vendor(vendorId)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(onion));
        mvc.perform(get("/api/vendor/products").param("q", "100%").with(vendor(vendorId)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/vendor/products").param("status", "NOPE").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        mvc.perform(get("/api/admin/products").param("categoryId", rootId).param("q", "onion")
                        .with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(onion));
    }

    @Test
    void skuIsUniquePerVendorAmongNonArchivedProducts() throws Exception {
        String first = createProduct(vendorId, productJson("Apple", "APL-1", null));
        mvc.perform(post("/api/vendor/products").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("Apple 2", "apl-1", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SKU_ALREADY_EXISTS"));
        // Another vendor may use the same SKU
        createProduct(UUID.randomUUID(), productJson("Apple", "APL-1", null));
        // Updating a product while keeping its own SKU is fine; taking another product's SKU is not
        String second = createProduct(vendorId, productJson("Pear", "PER-1", null));
        mvc.perform(put("/api/vendor/products/{id}", second).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("Pear", "PER-1", null)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/vendor/products/{id}", second).with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON).content(productJson("Pear", "APL-1", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SKU_ALREADY_EXISTS"));
        // Archiving frees the SKU
        mvc.perform(delete("/api/vendor/products/{id}", first).with(vendor(vendorId))).andExpect(status().isNoContent());
        createProduct(vendorId, productJson("Apple (new pack)", "APL-1", null));
    }

    @Test
    void brandsAreMatchedCaseInsensitivelyAndSuggested() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String brandName = "Farm Fresh " + suffix;
        String a = createProduct(vendorId, productJson("Spinach", null, brandName));
        String b = createProduct(UUID.randomUUID(), productJson("Lettuce", null, "  farm   FRESH " + suffix.toUpperCase() + " "));

        String brandA = body(mvc.perform(get("/api/vendor/products/{id}", a).with(vendor(vendorId)))).get("brand").get("id").asString();
        JsonNode adminView = body(mvc.perform(get("/api/admin/products/{id}", b).with(as(admin, "SUPPORT"))));
        assertThat(adminView.get("brand").get("id").asString()).isEqualTo(brandA);
        assertThat(adminView.get("brand").get("name").asString()).isEqualTo(brandName);

        mvc.perform(get("/api/vendor/brands").param("q", "farm fresh " + suffix).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(brandA))
                .andExpect(jsonPath("$[0].name").value(brandName));
        mvc.perform(get("/api/vendor/brands").with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(10)));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/vendor/products").with(vendor(vendorId)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","categoryId":null,"unitValue":0.0001,"unitOfMeasure":"TON",
                                 "sku":"bad sku!","barcode":"12","foodType":"VEGAN","tags":["x"],
                                 "imageUrls":["ftp://img.example.com/a.jpg"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'categoryId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'unitValue')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'unitOfMeasure')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'sku')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'barcode')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'foodType')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'tags[0]')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'imageUrls[0]')]").exists());

        mvc.perform(post("/api/vendor/products").with(vendor(vendorId)).contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Ghost", null, null).replace(leafId, UUID.randomUUID().toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }
}
