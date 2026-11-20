package com.grocery.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The snapshot rule under real concurrency: a competing transaction (plain JDBC) holds a row lock and
 * changes the row; the product command must wait for it and then build its snapshot from the committed
 * change, with a higher version.
 */
class SnapshotLockingIntegrationTest extends AbstractIntegrationTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final UUID vendorId = UUID.randomUUID();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<Integer> async(org.springframework.test.web.servlet.RequestBuilder request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(request).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    private static void execute(Connection connection, String sql, String... params) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            statement.execute();
        }
    }

    @Test
    void productCreateWaitsForACategoryChangeAndSeesItsResult() throws Exception {
        String category = createCategory(unique("Spices"), null);
        String renamed = unique("Spices & Masalas");

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // A category rename in flight: it holds the row lock and has changed the name, not yet committed
            execute(other, "SELECT id FROM category WHERE id = UUID_TO_BIN(?) FOR UPDATE", category);
            execute(other, "UPDATE category SET name = ? WHERE id = UUID_TO_BIN(?)", renamed, category);

            CompletableFuture<Integer> create = async(post("/api/vendor/products").with(vendor(vendorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Turmeric\",\"categoryId\":\"%s\",\"unitValue\":100,\"unitOfMeasure\":\"G\"}"
                            .formatted(category)));
            Thread.sleep(700);
            assertThat(create).isNotDone();                 // blocked on FOR SHARE of the category

            other.commit();
            assertThat(create.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        }

        String productId = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/vendor/products").param("q", "Turmeric").with(vendor(vendorId))))
                .get("content").get(0).get("id").asString();
        JsonNode snapshot = lastProductEvent(productId);
        assertThat(snapshot.get("productVersion").asLong()).isEqualTo(1);
        assertThat(snapshot.get("category").get("name").asString()).isEqualTo(renamed);
    }

    @Test
    void productCommandWaitsForTheProductLockAndProducesTheHigherVersion() throws Exception {
        String category = createCategory(unique("Oils"), null);
        String productId = body(mvc.perform(post("/api/vendor/products").with(vendor(vendorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mustard Oil\",\"categoryId\":\"%s\",\"unitValue\":1,\"unitOfMeasure\":\"L\"}"
                                .formatted(category)))
                .andExpect(status().isCreated())).get("id").asString();

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // Another writer of the same product: locked, changed and version bumped, not yet committed
            execute(other, "SELECT id FROM product WHERE id = UUID_TO_BIN(?) FOR UPDATE", productId);
            execute(other, "UPDATE product SET name = 'Cold Pressed Mustard Oil', product_version = product_version + 1 "
                    + "WHERE id = UUID_TO_BIN(?)", productId);

            CompletableFuture<Integer> publish = async(post("/api/vendor/products/{id}/publish", productId)
                    .with(vendor(vendorId)));
            Thread.sleep(700);
            assertThat(publish).isNotDone();                // blocked on FOR UPDATE of the product

            other.commit();
            assertThat(publish.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }

        JsonNode snapshot = lastProductEvent(productId);
        assertThat(snapshot.get("productVersion").asLong()).isEqualTo(3);     // 1 create, 2 other writer, 3 publish
        assertThat(snapshot.get("name").asString()).isEqualTo("Cold Pressed Mustard Oil");
        assertThat(snapshot.get("status").asString()).isEqualTo("PUBLISHED");
    }
}
