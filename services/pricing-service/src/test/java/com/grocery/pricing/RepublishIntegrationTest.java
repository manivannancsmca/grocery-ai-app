package com.grocery.pricing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepublishIntegrationTest extends AbstractIntegrationTest {

    @Test
    void republishResendsEveryLivePriceWithoutChangingVersions() throws Exception {
        UUID vendorId = UUID.randomUUID();
        UUID productId = pricedProduct(vendorId, "Cumin", "90.00", "80.00");
        putPrice(vendorId, productId, "90.00", "85.00").andExpect(status().isOk());
        product(vendorId, "Unpriced Fennel");
        JsonNode before = priceChangedEvents(productId).getLast();

        mvc.perform(post("/api/admin/pricing/republish").with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/pricing/republish").with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isForbidden());
        JsonNode counts = body(mvc.perform(post("/api/admin/pricing/republish").with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk()));

        long livePrices = jdbc().sql("SELECT COUNT(*) FROM product_price").query(Long.class).single();
        assertThat(counts.get("prices").asLong()).isEqualTo(livePrices);

        List<JsonNode> events = priceChangedEvents(productId);
        assertThat(events).hasSize(3);
        JsonNode resent = events.getLast();
        assertThat(resent.get("priceVersion").asLong()).isEqualTo(2);
        assertThat(resent).isEqualTo(before);                               // identical event, same version
        Long stored = jdbc().sql("SELECT price_version FROM product_price WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single();
        assertThat(stored).isEqualTo(2);
        Long history = jdbc().sql("SELECT COUNT(*) FROM price_history WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single();
        assertThat(history).isEqualTo(2);
    }
}
