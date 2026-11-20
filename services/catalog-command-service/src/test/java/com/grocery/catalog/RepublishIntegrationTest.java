package com.grocery.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RepublishIntegrationTest extends AbstractIntegrationTest {

    @Test
    void republishResendsEverySnapshotWithoutChangingVersions() throws Exception {
        UUID vendorId = UUID.randomUUID();
        String root = createCategory(unique("Personal Care"), null);
        String soap = createCategory("Soap", root);
        String product = body(mvc.perform(post("/api/vendor/products").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neem Soap\",\"categoryId\":\"%s\",\"unitValue\":4,\"unitOfMeasure\":\"PACK\"}"
                                .formatted(soap)))
                .andExpect(status().isCreated())).get("id").asString();
        mvc.perform(post("/api/vendor/products/{id}/publish", product).with(vendor(vendorId))).andExpect(status().isOk());
        JsonNode before = lastProductEvent(product);
        long soapVersion = categoryEvents(soap).getLast().get("categoryVersion").asLong();

        mvc.perform(post("/api/admin/catalog/republish").with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden());
        JsonNode counts = body(mvc.perform(post("/api/admin/catalog/republish").with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk()));

        long categoryRows = JdbcClient.create(dataSource).sql("SELECT COUNT(*) FROM category").query(Long.class).single();
        long sendableProducts = JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM product WHERE category_id IS NOT NULL").query(Long.class).single();
        assertThat(counts.get("categories").asLong()).isEqualTo(categoryRows);
        assertThat(counts.get("products").asLong()).isEqualTo(sendableProducts);

        List<JsonNode> productEvents = productEvents(product);
        JsonNode resent = productEvents.getLast();
        assertThat(productEvents).hasSize(3);
        assertThat(resent.get("productVersion").asLong()).isEqualTo(before.get("productVersion").asLong());
        assertThat(resent).isEqualTo(before);                    // identical snapshot, same version
        JsonNode soapResent = categoryEvents(soap).getLast();
        assertThat(soapResent.get("categoryVersion").asLong()).isEqualTo(soapVersion);
        assertThat(soapResent.get("path")).hasSize(2);

        Long stored = JdbcClient.create(dataSource)
                .sql("SELECT product_version FROM product WHERE id = UUID_TO_BIN(?)").param(product)
                .query(Long.class).single();
        assertThat(stored).isEqualTo(before.get("productVersion").asLong());
    }
}
