package com.grocery.pricing;

import com.grocery.common.id.Ids;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VendorPriceIntegrationTest extends AbstractIntegrationTest {

    private final UUID vendorId = UUID.randomUUID();

    private Long priceVersion(UUID productId) {
        return jdbc().sql("SELECT price_version FROM product_price WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single();
    }

    @Test
    void theFirstPriceAppliesImmediately() throws Exception {
        UUID productId = product(vendorId, "Tomato");

        putPrice(vendorId, productId, "{\"mrp\":60,\"sellingPrice\":49.00,\"note\":\"Launch\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.productId").value(productId.toString()))
                .andExpect(jsonPath("$.productName").value("Tomato"))
                .andExpect(jsonPath("$.price.mrp").value(60.00))
                .andExpect(jsonPath("$.price.sellingPrice").value(49.00))
                .andExpect(jsonPath("$.price.currency").value("INR"))
                .andExpect(jsonPath("$.price.discountPercent").value(18))
                .andExpect(jsonPath("$.price.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.pendingRequest").isEmpty())
                .andExpect(jsonPath("$.approvalThresholdPercent").value(20));

        List<JsonNode> changed = priceChangedEvents(productId);
        assertThat(changed).hasSize(1);
        JsonNode event = changed.getFirst();
        assertThat(event.get("productId").asString()).isEqualTo(productId.toString());
        assertThat(event.get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(event.get("mrp").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(event.get("sellingPrice").decimalValue()).isEqualByComparingTo("49.00");
        assertThat(event.get("currency").asString()).isEqualTo("INR");
        assertThat(event.get("priceVersion").asLong()).isEqualTo(1);
        assertThat(event.get("changedAt").isNull()).isFalse();
        assertThat(priceVersion(productId)).isEqualTo(1);

        JsonNode history = body(mvc.perform(get("/api/vendor/prices/{id}/history", productId).with(vendor(vendorId)))
                .andExpect(status().isOk()));
        assertThat(history.get("totalElements").asLong()).isEqualTo(1);
        JsonNode first = history.get("content").get(0);
        assertThat(first.get("oldMrp").isNull()).isTrue();
        assertThat(first.get("oldSellingPrice").isNull()).isTrue();
        assertThat(first.get("changePercent").isNull()).isTrue();
        assertThat(first.get("newMrp").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(first.get("newSellingPrice").decimalValue()).isEqualByComparingTo("49.00");
        assertThat(first.get("source").asString()).isEqualTo("VENDOR");
        assertThat(first.get("requestId").isNull()).isTrue();
        assertThat(first.get("changedBy").asString()).isEqualTo(vendorUser(vendorId).toString());
    }

    @Test
    void changesWithinTwentyPercentApplyInEitherDirection() throws Exception {
        UUID productId = pricedProduct(vendorId, "Onion", "120.00", "100.00");

        putPrice(vendorId, productId, "120.00", "120.00")        // +20 % exactly
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.price.sellingPrice").value(120.00))
                .andExpect(jsonPath("$.price.discountPercent").value(0));
        putPrice(vendorId, productId, "120.00", "96.00")         // -20 % exactly
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        putPrice(vendorId, productId, "150.00", "96.00")         // MRP only: 0 % selling-price change
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.mrp").value(150.00));

        assertThat(priceChangedEvents(productId)).extracting(e -> e.get("priceVersion").asLong())
                .containsExactly(1L, 2L, 3L, 4L);
        JsonNode history = body(mvc.perform(get("/api/vendor/prices/{id}/history", productId).with(vendor(vendorId))));
        assertThat(history.get("content")).extracting(e -> e.get("changePercent").isNull()
                        ? null : e.get("changePercent").asDouble())
                .containsExactly(0.0, -20.0, 20.0, null);                    // newest first
        assertThat(history.get("content").get(1).get("oldSellingPrice").decimalValue()).isEqualByComparingTo("120.00");
    }

    @Test
    void aLargerChangeWaitsForAnAdminAndBlocksFurtherChanges() throws Exception {
        UUID productId = pricedProduct(vendorId, "Garlic", "150.00", "100.00");

        JsonNode pending = body(putPrice(vendorId, productId,
                "{\"mrp\":150.00,\"sellingPrice\":120.01,\"note\":\"Festival season\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.price.sellingPrice").value(100.00))          // live price unchanged
                .andExpect(jsonPath("$.pendingRequest.status").value("PENDING"))
                .andExpect(jsonPath("$.pendingRequest.changePercent").value(20.01))
                .andExpect(jsonPath("$.pendingRequest.currentSellingPrice").value(100.00))
                .andExpect(jsonPath("$.pendingRequest.requestedSellingPrice").value(120.01))
                .andExpect(jsonPath("$.pendingRequest.requestedMrp").value(150.00))
                .andExpect(jsonPath("$.pendingRequest.note").value("Festival season"))
                .andExpect(jsonPath("$.pendingRequest.productName").value("Garlic"))
                .andExpect(jsonPath("$.pendingRequest.vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.pendingRequest.vendorName").doesNotExist())
                .andExpect(jsonPath("$.pendingRequest.decidedBy").isEmpty()));
        String requestId = pending.get("pendingRequest").get("id").asString();

        assertThat(priceVersion(productId)).isEqualTo(1);
        assertThat(priceChangedEvents(productId)).hasSize(1);
        List<JsonNode> requested = events(productId, "PriceChangeRequested");
        assertThat(requested).hasSize(1);
        JsonNode event = requested.getFirst();
        assertThat(event.get("requestId").asString()).isEqualTo(requestId);
        assertThat(event.get("productName").asString()).isEqualTo("Garlic");
        assertThat(event.get("vendorId").asString()).isEqualTo(vendorId.toString());
        assertThat(event.get("oldMrp").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(event.get("oldSellingPrice").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(event.get("newMrp").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(event.get("newSellingPrice").decimalValue()).isEqualByComparingTo("120.01");
        assertThat(event.get("changePercent").decimalValue()).isEqualByComparingTo("20.01");
        assertThat(event.get("requestedBy").asString()).isEqualTo(vendorUser(vendorId).toString());

        // While pending: any other PUT is refused, even a small one
        putPrice(vendorId, productId, "150.00", "101.00")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRICE_CHANGE_PENDING"));
        mvc.perform(get("/api/vendor/prices/{id}", productId).with(vendor(vendorId)))
                .andExpect(jsonPath("$.pendingRequest.id").value(requestId))
                .andExpect(jsonPath("$.price.sellingPrice").value(100.00));

        // Cancel, then cancelling again is refused; changes are possible again
        mvc.perform(post("/api/vendor/price-change-requests/{id}/cancel", requestId).with(vendor(vendorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.decidedBy").value(vendorUser(vendorId).toString()))
                .andExpect(jsonPath("$.decisionReason").value("Cancelled by vendor"));
        mvc.perform(post("/api/vendor/price-change-requests/{id}/cancel", requestId).with(vendor(vendorId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRICE_CHANGE_NOT_PENDING"));
        JsonNode cancelled = events(productId, "PriceChangeCancelled").getFirst();
        assertThat(cancelled.get("cancelledBy").asString()).isEqualTo(vendorUser(vendorId).toString());
        assertThat(cancelled.get("requestId").asString()).isEqualTo(requestId);

        putPrice(vendorId, productId, "150.00", "101.00").andExpect(status().isOk());
        mvc.perform(get("/api/vendor/price-change-requests").with(vendor(vendorId)))
                .andExpect(jsonPath("$.content[0].id").value(requestId))
                .andExpect(jsonPath("$.content[0].status").value("CANCELLED"));
        mvc.perform(get("/api/vendor/price-change-requests").param("status", "PENDING").with(vendor(vendorId)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/vendor/price-change-requests").param("status", "LATER").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void unchangedPricesAndInvalidAmountsAreRefused() throws Exception {
        UUID productId = pricedProduct(vendorId, "Ginger", "60.00", "49.00");

        putPrice(vendorId, productId, "60", "49.0")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PRICE_UNCHANGED"));
        putPrice(vendorId, productId, "60.00", "60.01")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("SELLING_PRICE_ABOVE_MRP"))
                .andExpect(jsonPath("$.errors[0].field").value("sellingPrice"));
        putPrice(vendorId, productId, "60.00", "49.999")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sellingPrice"));
        putPrice(vendorId, productId, "60.00", "0.99")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sellingPrice"));
        putPrice(vendorId, productId, "1000000.01", "50.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("mrp"));
        putPrice(vendorId, productId, "{\"sellingPrice\":50.00}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("mrp"));
        putPrice(vendorId, productId, "{\"mrp\":60,\"sellingPrice\":50,\"note\":\"%s\"}".formatted("x".repeat(301)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        // The extremes are allowed
        UUID extreme = pricedProduct(vendorId, "Saffron", "1000000.00", "1000000.00");
        putPrice(vendorId, extreme, "1000000.00", "1.00")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingRequest.changePercent").value(-100.00));

        assertThat(priceChangedEvents(productId)).hasSize(1);
    }

    @Test
    void listsSummaryFiltersAndSearch() throws Exception {
        UUID vendor = UUID.randomUUID();
        UUID apple = pricedProduct(vendor, "Apple", "100.00", "90.00");
        UUID banana = product(vendor, "Banana");
        UUID carrot = pricedProduct(vendor, "Carrot", "50.00", "40.00");
        pendingRequest(vendor, carrot, "50.00", "20.00");
        UUID archived = pricedProduct(vendor, "Dates", "300.00", "250.00");
        upsertProduct(archived, vendor, "Dates", null, "ARCHIVED", 2);
        upsertProduct(Ids.newId(), vendor, "Eggplant", "EGG-1", "DRAFT", 1);
        product(UUID.randomUUID(), "Apple of another vendor");

        mvc.perform(get("/api/vendor/prices").with(vendor(vendor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.content[0].productName").value("Apple"))
                .andExpect(jsonPath("$.content[0].price.sellingPrice").value(90.00))
                .andExpect(jsonPath("$.content[0].outcome").doesNotExist())
                .andExpect(jsonPath("$.content[1].productName").value("Banana"))
                .andExpect(jsonPath("$.content[1].price").isEmpty())
                .andExpect(jsonPath("$.content[2].productName").value("Carrot"))
                .andExpect(jsonPath("$.content[2].pendingRequest.requestedSellingPrice").value(20.00))
                .andExpect(jsonPath("$.content[3].productName").value("Eggplant"));
        mvc.perform(get("/api/vendor/prices").param("filter", "PRICED").with(vendor(vendor)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].productId").value(apple.toString()))
                .andExpect(jsonPath("$.content[1].productId").value(carrot.toString()));
        mvc.perform(get("/api/vendor/prices").param("filter", "UNPRICED").with(vendor(vendor)))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].productId").value(banana.toString()));
        mvc.perform(get("/api/vendor/prices").param("filter", "PENDING_APPROVAL").with(vendor(vendor)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].productId").value(carrot.toString()));
        mvc.perform(get("/api/vendor/prices").param("filter", "CHEAP").with(vendor(vendor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/prices").param("q", "an").with(vendor(vendor)))
                .andExpect(jsonPath("$.totalElements").value(2));                  // Banana, Eggplant
        mvc.perform(get("/api/vendor/prices").param("q", "EGG-1").with(vendor(vendor)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].productName").value("Eggplant"));
        mvc.perform(get("/api/vendor/prices").param("size", "2").param("page", "1").with(vendor(vendor)))
                .andExpect(jsonPath("$.content[0].productName").value("Carrot"))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/vendor/prices/summary").with(vendor(vendor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products").value(4))
                .andExpect(jsonPath("$.priced").value(2))
                .andExpect(jsonPath("$.unpriced").value(2))
                .andExpect(jsonPath("$.pendingApproval").value(1));
    }

    @Test
    void ownershipAndRoles() throws Exception {
        UUID productId = pricedProduct(vendorId, "Lemon", "10.00", "9.00");
        UUID otherVendor = UUID.randomUUID();
        String requestId = pendingRequest(vendorId, productId, "10.00", "5.00");

        putPrice(otherVendor, productId, "10.00", "8.00")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        mvc.perform(get("/api/vendor/prices/{id}", productId).with(vendor(otherVendor)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/vendor/prices/{id}/history", productId).with(vendor(otherVendor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
        mvc.perform(post("/api/vendor/price-change-requests/{id}/cancel", requestId).with(vendor(otherVendor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRICE_CHANGE_REQUEST_NOT_FOUND"));
        mvc.perform(get("/api/vendor/price-change-requests").with(vendor(otherVendor)))
                .andExpect(jsonPath("$.totalElements").value(0));

        // A VENDOR without the vendor_id claim (not approved) and other roles
        UUID user = UUID.randomUUID();
        mvc.perform(get("/api/vendor/prices").with(as(user, "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/prices/{id}", productId).with(as(user, "CUSTOMER")))
                .andExpect(status().isForbidden());
        putPrice(vendorId, productId, "10.00", "8.00").andExpect(status().isConflict());     // still pending
        mvc.perform(get("/api/vendor/prices/summary").with(as(user, "OPS_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/prices")).andExpect(status().isUnauthorized());
    }
}
