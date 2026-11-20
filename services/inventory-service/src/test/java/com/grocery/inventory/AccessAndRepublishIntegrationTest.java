package com.grocery.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may do what: ownership, vendor approval, roles; and the admin republish. */
class AccessAndRepublishIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anotherVendorsProductIsNotFound() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Milk", 5);
        UUID otherVendor = UUID.randomUUID();

        mvc.perform(get("/api/vendor/inventory/{id}", productId).with(vendor(otherVendor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        adjustRequest(otherVendor, productId, "DECREASE", 1, "DAMAGED")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        mvc.perform(put("/api/vendor/inventory/{id}/threshold", productId).with(vendor(otherVendor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lowStockThreshold\":1}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/vendor/inventory/{id}/movements", productId).with(vendor(otherVendor)))
                .andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get("/api/vendor/inventory").with(vendor(otherVendor))))
                .get("totalElements").asLong()).isZero();
        assertThat(onHand(productId)).isEqualTo(5);
    }

    @Test
    void onlyApprovedVendorsMayUseTheVendorApi() throws Exception {
        UUID productId = newProduct(vendorId, "Bread");
        UUID user = UUID.randomUUID();

        // VENDOR role but no vendor_id claim (vendor not approved yet)
        mvc.perform(get("/api/vendor/inventory").with(as(user, "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(post("/api/vendor/inventory/{id}/adjustments", productId).with(as(user, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INCREASE\",\"quantity\":1,\"reason\":\"RESTOCK\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        // Not a vendor at all, even with a vendor_id claim
        mvc.perform(get("/api/vendor/inventory/summary").with(as(user, vendorId, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/inventory/{id}", productId).with(as(user, "OPS_ADMIN")))
                .andExpect(status().isForbidden());
        // No token
        mvc.perform(get("/api/vendor/inventory")).andExpect(status().isUnauthorized());
        assertThat(stockVersion(productId)).isZero();
    }

    @Test
    void republishResendsCurrentAvailabilityWithoutChangingVersions() throws Exception {
        UUID inStock = newProductWithStock(vendorId, "Butter", 8);
        adjust(vendorId, inStock, "DECREASE", 1, "DAMAGED");
        UUID outOfStock = newProduct(vendorId, "Cheese");
        UUID archived = newProductWithStock(vendorId, "Old Ghee", 2);
        upsert(archived, vendorId, "Old Ghee", null, "ARCHIVED", 2);
        long inStockVersion = stockVersion(inStock);
        int archivedEvents = events(archived, "ProductAvailabilityChanged").size();

        mvc.perform(post("/api/admin/inventory/republish").with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/republish").with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/republish").with(vendor(vendorId)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/inventory/republish")).andExpect(status().isUnauthorized());

        long liveProducts = jdbc().sql("""
                SELECT COUNT(*) FROM stock_item s JOIN product_ref p ON p.product_id = s.product_id
                WHERE p.status <> 'ARCHIVED'""").query(Long.class).single();
        JsonNode counts = body(mvc.perform(post("/api/admin/inventory/republish").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk()));
        assertThat(counts.get("products").asLong()).isEqualTo(liveProducts);

        List<JsonNode> resent = events(inStock, "ProductAvailabilityChanged");
        assertThat(resent).hasSize(2);                                       // the 0 → 8 transition + republish
        assertThat(resent.getLast().get("inStock").asBoolean()).isTrue();
        assertThat(resent.getLast().get("stockVersion").asLong()).isEqualTo(inStockVersion);
        assertThat(resent.getLast().get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(stockVersion(inStock)).isEqualTo(inStockVersion);         // no increment

        JsonNode never = events(outOfStock, "ProductAvailabilityChanged").getLast();
        assertThat(never.get("inStock").asBoolean()).isFalse();
        assertThat(never.get("stockVersion").asLong()).isZero();
        assertThat(events(archived, "ProductAvailabilityChanged")).hasSize(archivedEvents);

        mvc.perform(post("/api/admin/inventory/republish").with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(liveProducts));
        assertThat(jdbc().sql("SELECT aggregate_type FROM outbox_event WHERE aggregate_id = ? ORDER BY occurred_at DESC LIMIT 1")
                .param(inStock.toString()).query(String.class).single()).isEqualTo("Stock");
        assertThat(jdbc().sql("SELECT DISTINCT topic FROM outbox_event WHERE aggregate_id = ?")
                .param(inStock.toString()).query(String.class).list()).containsExactly("inventory.events");
    }
}
