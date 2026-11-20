package com.grocery.pricing;

import com.grocery.common.id.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminPriceChangeIntegrationTest extends AbstractIntegrationTest {

    private final UUID vendorId = UUID.randomUUID();

    @Test
    void approvingMakesTheRequestedPriceLive() throws Exception {
        UUID productId = pricedProduct(vendorId, "Cashew", "1000.00", "900.00");
        putPrice(vendorId, productId, "1000.00", "880.00").andExpect(status().isOk());       // version 2
        String requestId = pendingRequest(vendorId, productId, "1000.00", "500.00");

        mvc.perform(post("/api/admin/price-change-requests/{id}/approve", requestId).with(as(admin, "FINANCE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Clearance ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(admin.toString()))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.decisionReason").value("Clearance ok"))
                .andExpect(jsonPath("$.vendorName").value("Green Basket"))
                .andExpect(jsonPath("$.productName").value("Cashew"));

        mvc.perform(get("/api/vendor/prices/{id}", productId).with(vendor(vendorId)))
                .andExpect(jsonPath("$.price.sellingPrice").value(500.00))
                .andExpect(jsonPath("$.price.discountPercent").value(50))
                .andExpect(jsonPath("$.pendingRequest").isEmpty());

        JsonNode latest = body(mvc.perform(get("/api/vendor/prices/{id}/history", productId).with(vendor(vendorId))))
                .get("content").get(0);
        assertThat(latest.get("source").asString()).isEqualTo("APPROVED_REQUEST");
        assertThat(latest.get("requestId").asString()).isEqualTo(requestId);
        assertThat(latest.get("changedBy").asString()).isEqualTo(admin.toString());
        assertThat(latest.get("oldSellingPrice").decimalValue()).isEqualByComparingTo("880.00");
        assertThat(latest.get("newSellingPrice").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(latest.get("changePercent").decimalValue()).isEqualByComparingTo("-43.18");

        List<JsonNode> changed = priceChangedEvents(productId);
        assertThat(changed).extracting(e -> e.get("priceVersion").asLong()).containsExactly(1L, 2L, 3L);
        assertThat(changed.getLast().get("sellingPrice").decimalValue()).isEqualByComparingTo("500.00");
        JsonNode approved = events(productId, "PriceChangeApproved").getFirst();
        assertThat(approved.get("requestId").asString()).isEqualTo(requestId);
        assertThat(approved.get("productId").asString()).isEqualTo(productId.toString());
        assertThat(approved.get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(approved.get("approvedBy").asString()).isEqualTo(admin.toString());

        // Decided requests cannot be decided again
        approve(requestId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRICE_CHANGE_NOT_PENDING"));
        mvc.perform(post("/api/admin/price-change-requests/{id}/reject", requestId).with(opsAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRICE_CHANGE_NOT_PENDING"));
    }

    @Test
    void approveWithoutABodyWorks() throws Exception {
        UUID productId = pricedProduct(vendorId, "Almond", "800.00", "700.00");
        String requestId = pendingRequest(vendorId, productId, "800.00", "300.00");
        approve(requestId, "SUPER_ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionReason").isEmpty());
    }

    @Test
    void rejectingNeedsAReasonAndLeavesThePriceAlone() throws Exception {
        UUID productId = pricedProduct(vendorId, "Walnut", "900.00", "800.00");
        String requestId = pendingRequest(vendorId, productId, "900.00", "100.00");

        mvc.perform(post("/api/admin/price-change-requests/{id}/reject", requestId).with(opsAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(post("/api/admin/price-change-requests/{id}/reject", requestId).with(opsAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"%s\"}".formatted("x".repeat(301))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/price-change-requests/{id}/reject", requestId).with(opsAdmin()))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/price-change-requests/{id}/reject", requestId).with(as(admin, "FINANCE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too low for this category\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("Too low for this category"))
                .andExpect(jsonPath("$.decidedBy").value(admin.toString()));

        mvc.perform(get("/api/vendor/prices/{id}", productId).with(vendor(vendorId)))
                .andExpect(jsonPath("$.price.sellingPrice").value(800.00))
                .andExpect(jsonPath("$.pendingRequest").isEmpty());
        assertThat(priceChangedEvents(productId)).hasSize(1);
        JsonNode rejected = events(productId, "PriceChangeRejected").getFirst();
        assertThat(rejected.get("requestId").asString()).isEqualTo(requestId);
        assertThat(rejected.get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(rejected.get("rejectedBy").asString()).isEqualTo(admin.toString());
        assertThat(rejected.get("reason").asString()).isEqualTo("Too low for this category");
    }

    @Test
    void onlyDecisionRolesDecideButEveryAdminReads() throws Exception {
        UUID productId = pricedProduct(vendorId, "Pistachio", "500.00", "450.00");
        String requestId = pendingRequest(vendorId, productId, "500.00", "200.00");

        for (String role : new String[]{"SUPPORT", "DELIVERY_MANAGER", "VENDOR", "CUSTOMER"}) {
            approve(requestId, role).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/price-change-requests/{id}/reject", requestId).with(as(admin, role))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/price-change-requests/{id}", requestId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.vendorName").value("Green Basket"))
                .andExpect(jsonPath("$.currentSellingPrice").value(450.00))
                .andExpect(jsonPath("$.requestedSellingPrice").value(200.00))
                .andExpect(jsonPath("$.changePercent").value(-55.56));
        mvc.perform(get("/api/admin/price-change-requests").with(as(admin, "DELIVERY_MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/price-change-requests/{id}", requestId).with(as(admin, "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/price-change-requests/{id}", Ids.newId()).with(as(admin, "SUPPORT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRICE_CHANGE_REQUEST_NOT_FOUND"));
        approve(Ids.newId().toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRICE_CHANGE_REQUEST_NOT_FOUND"));
        approve(requestId, "OPS_ADMIN").andExpect(status().isOk());
    }

    @Test
    void theQueueListsPendingOldestFirstAndOtherStatusesNewestFirst() throws Exception {
        UUID vendor = UUID.randomUUID();
        String first = pendingRequest(vendor, pricedProduct(vendor, "Rice", "100.00", "90.00"), "100.00", "50.00");
        String second = pendingRequest(vendor, pricedProduct(vendor, "Wheat", "100.00", "90.00"), "100.00", "40.00");
        String third = pendingRequest(vendor, pricedProduct(vendor, "Millet", "100.00", "90.00"), "100.00", "30.00");
        approve(second).andExpect(status().isOk());
        approve(third).andExpect(status().isOk());

        mvc.perform(get("/api/admin/price-change-requests").param("vendorId", vendor.toString())
                        .with(as(admin, "SUPPORT")))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[0].productName").value("Rice"))
                .andExpect(jsonPath("$.content[0].vendorName").value("Green Basket"));
        mvc.perform(get("/api/admin/price-change-requests").param("vendorId", vendor.toString())
                        .param("status", "APPROVED").with(as(admin, "SUPPORT")))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.content[1].id").value(second));
        mvc.perform(get("/api/admin/price-change-requests").param("status", "SOMETIME").with(opsAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        // Without a vendor filter the oldest pending requests of everyone come first
        JsonNode queue = body(mvc.perform(get("/api/admin/price-change-requests").param("size", "100")
                .with(opsAdmin())));
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode request : queue.get("content")) {
            ids.add(request.get("id").asString());
        }
        assertThat(ids).contains(first).doesNotContain(second, third);
        assertThat(ids).isSortedAccordingTo(String::compareTo);          // UUIDv7 ids = creation order
    }

    @Test
    void anArchivedProductCannotBeApproved() throws Exception {
        UUID productId = pricedProduct(vendorId, "Hazelnut", "600.00", "550.00");
        String requestId = pendingRequest(vendorId, productId, "600.00", "100.00");
        // Archived in the projection without the archive event's auto-cancel (as if they raced)
        jdbc().sql("UPDATE product_ref SET status = 'ARCHIVED' WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).update();

        approve(requestId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_ARCHIVED"));
        mvc.perform(get("/api/admin/price-change-requests/{id}", requestId).with(opsAdmin()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(priceChangedEvents(productId)).hasSize(1);
    }
}
