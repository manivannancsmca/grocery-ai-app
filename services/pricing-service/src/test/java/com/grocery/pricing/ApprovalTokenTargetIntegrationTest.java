package com.grocery.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 §6: pricing-service is an approval-token target (grocery-common 0.8.0 filter, {@code consumed_approval}).
 * A request carrying an {@code X-Approval-Token} that cannot be verified never reaches the handler; requests without
 * the header are untouched.
 */
class ApprovalTokenTargetIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anUnverifiableApprovalTokenIsRefusedBeforeTheHandler() throws Exception {
        UUID vendorId = UUID.randomUUID();
        UUID productId = pricedProduct(vendorId, "Atta", "300.00", "280.00");

        mvc.perform(put("/api/vendor/prices/{id}", productId).with(vendor(vendorId))
                        .header("X-Approval-Token", "not-a-signed-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mrp\":300.00,\"sellingPrice\":270.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_TOKEN_INVALID"))
                .andExpect(jsonPath("$.reason").value("SIGNATURE"));
        assertThat(priceChangedEvents(productId)).hasSize(1);               // only the first price

        // without the header: an ordinary vendor request
        putPrice(vendorId, productId, "300.00", "270.00").andExpect(status().isOk());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM consumed_approval").query(Long.class).single()).isNotNull();
    }
}
