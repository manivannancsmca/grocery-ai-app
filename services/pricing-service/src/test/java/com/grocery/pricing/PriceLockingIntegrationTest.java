package com.grocery.pricing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * The locking rule under real concurrency: a competing transaction (plain JDBC) holds the product_ref lock; the
 * price commands must wait for it, and then work on what it committed.
 */
class PriceLockingIntegrationTest extends AbstractIntegrationTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final UUID vendorId = UUID.randomUUID();

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    private CompletableFuture<MockHttpServletResponse> async(RequestBuilder request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(request).andReturn().getResponse();
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

    private static void lockProduct(Connection connection, UUID productId) throws Exception {
        execute(connection, "SELECT product_id FROM product_ref WHERE product_id = UUID_TO_BIN(?) FOR UPDATE",
                productId.toString());
    }

    @Test
    void twoAdminsApprovingTheSameRequestAtOnceOnlyOneWins() throws Exception {
        UUID productId = pricedProduct(vendorId, "Basmati Rice", "200.00", "180.00");
        String requestId = pendingRequest(vendorId, productId, "200.00", "100.00");

        CompletableFuture<MockHttpServletResponse> first;
        CompletableFuture<MockHttpServletResponse> second;
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            lockProduct(other, productId);              // both approvals queue up behind this lock
            first = async(post("/api/admin/price-change-requests/{id}/approve", requestId)
                    .with(as(UUID.randomUUID(), "OPS_ADMIN")));
            second = async(post("/api/admin/price-change-requests/{id}/approve", requestId)
                    .with(as(UUID.randomUUID(), "FINANCE_ADMIN")));
            Thread.sleep(700);
            assertThat(first).isNotDone();
            assertThat(second).isNotDone();
            other.commit();
        }

        List<Integer> statuses = List.of(first.get(20, TimeUnit.SECONDS).getStatus(),
                second.get(20, TimeUnit.SECONDS).getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        MockHttpServletResponse loser = first.get().getStatus() == 409 ? first.get() : second.get();
        assertThat(json.readTree(loser.getContentAsString()).get("errorCode").asString())
                .isEqualTo("PRICE_CHANGE_NOT_PENDING");

        assertThat(events(productId, "PriceChangeApproved")).hasSize(1);
        assertThat(priceChangedEvents(productId)).extracting(e -> e.get("priceVersion").asLong())
                .containsExactly(1L, 2L);
        Long historyRows = jdbc().sql("SELECT COUNT(*) FROM price_history WHERE product_id = UUID_TO_BIN(?)")
                .param(productId.toString()).query(Long.class).single();
        assertThat(historyRows).isEqualTo(2);
    }

    @Test
    void aPriceChangeWaitsForTheProductLockAndBuildsOnTheCommittedPrice() throws Exception {
        UUID productId = pricedProduct(vendorId, "Toor Dal", "200.00", "150.00");

        CompletableFuture<MockHttpServletResponse> change;
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // Another writer of the same product: locked, price changed and version bumped, not yet committed
            lockProduct(other, productId);
            execute(other, "UPDATE product_price SET selling_price = 160.00, price_version = price_version + 1 "
                    + "WHERE product_id = UUID_TO_BIN(?)", productId.toString());

            change = async(put("/api/vendor/prices/{id}", productId).with(vendor(vendorId))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"mrp\":200.00,\"sellingPrice\":168.00}"));
            Thread.sleep(700);
            assertThat(change).isNotDone();             // blocked on FOR UPDATE of product_ref
            other.commit();
        }
        assertThat(change.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(200);

        JsonNode last = priceChangedEvents(productId).getLast();
        assertThat(last.get("priceVersion").asLong()).isEqualTo(3);     // 1 first, 2 other writer, 3 this change
        JsonNode latest = body(mvc.perform(get("/api/vendor/prices/{id}/history", productId).with(vendor(vendorId))))
                .get("content").get(0);
        assertThat(latest.get("oldSellingPrice").decimalValue()).isEqualByComparingTo("160.00");
        assertThat(latest.get("changePercent").decimalValue()).isEqualByComparingTo("5.00");
    }

    @Test
    void aVendorChangeAndAnApprovalAreSerialised() throws Exception {
        UUID productId = pricedProduct(vendorId, "Moong Dal", "100.00", "90.00");
        String requestId = pendingRequest(vendorId, productId, "100.00", "50.00");

        CompletableFuture<MockHttpServletResponse> approval;
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // The vendor cancels in a competing transaction that holds the product lock
            lockProduct(other, productId);
            execute(other, "UPDATE price_change_request SET status = 'CANCELLED' WHERE id = UUID_TO_BIN(?)", requestId);

            approval = async(post("/api/admin/price-change-requests/{id}/approve", requestId).with(opsAdmin()));
            Thread.sleep(700);
            assertThat(approval).isNotDone();
            other.commit();
        }
        MockHttpServletResponse response = approval.get(20, TimeUnit.SECONDS);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(json.readTree(response.getContentAsString()).get("errorCode").asString())
                .isEqualTo("PRICE_CHANGE_NOT_PENDING");
        mvc.perform(get("/api/vendor/prices/{id}", productId).with(vendor(vendorId)))
                .andExpect(jsonPath("$.price.sellingPrice").value(90.00));
        assertThat(priceChangedEvents(productId)).hasSize(1);
    }
}
