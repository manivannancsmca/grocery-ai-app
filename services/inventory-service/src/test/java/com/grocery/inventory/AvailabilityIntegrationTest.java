package com.grocery.inventory;

import com.grocery.common.id.Ids;
import com.grocery.inventory.service.ReservationService;
import com.grocery.inventory.service.ReservationService.ReservationLine;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /internal/inventory/availability (Phase 5 contract §4): batch read, missing ids, validation, access. */
class AvailabilityIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/internal/inventory/availability";

    @Autowired
    ReservationService reservations;
    @Autowired
    EntityManagerFactory entityManagerFactory;

    private final UUID customer = UUID.randomUUID();

    private ResultActions availability(RequestPostProcessor caller, UUID... productIds) throws Exception {
        String ids = Arrays.stream(productIds).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        return mvc.perform(post(PATH).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[" + ids + "]}"));
    }

    private ResultActions availability(UUID... productIds) throws Exception {
        return availability(as(customer, "CUSTOMER"), productIds);
    }

    @Test
    void returnsLiveAvailabilityAndListsUnknownAndArchivedProductsAsMissing() throws Exception {
        UUID rice = newProductWithStock(vendorId, "Rice", 10);
        reservations.reserve(Ids.newId(), List.of(new ReservationLine(rice, 3)), Duration.ofMinutes(15));
        UUID empty = newProduct(vendorId, "Dal");                                    // 0 on hand
        UUID allReserved = newProductWithStock(vendorId, "Oil", 2);
        reservations.reserve(Ids.newId(), List.of(new ReservationLine(allReserved, 2)), Duration.ofMinutes(15));
        UUID otherVendor = UUID.randomUUID();
        UUID draft = Ids.newId();
        upsert(draft, otherVendor, "Ghee", null, "DRAFT", 1);                        // visibility is the catalog's call
        UUID archived = newProductWithStock(vendorId, "Old Jam", 4);
        upsert(archived, vendorId, "Old Jam", null, "ARCHIVED", 2);
        UUID unknown = Ids.newId();

        availability(unknown, rice, empty, archived, allReserved, draft, rice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))                    // rice answered once
                .andExpect(jsonPath("$.items[0].productId").value(rice.toString()))
                .andExpect(jsonPath("$.items[0].vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.items[0].available").value(7))                // 10 on hand − 3 reserved
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andExpect(jsonPath("$.items[1].productId").value(empty.toString()))
                .andExpect(jsonPath("$.items[1].available").value(0))
                .andExpect(jsonPath("$.items[1].inStock").value(false))
                .andExpect(jsonPath("$.items[2].productId").value(allReserved.toString()))
                .andExpect(jsonPath("$.items[2].available").value(0))
                .andExpect(jsonPath("$.items[2].inStock").value(false))
                .andExpect(jsonPath("$.items[3].productId").value(draft.toString()))
                .andExpect(jsonPath("$.items[3].vendorId").value(otherVendor.toString()))
                .andExpect(jsonPath("$.missing.length()").value(2))                  // request order
                .andExpect(jsonPath("$.missing[0]").value(unknown.toString()))
                .andExpect(jsonPath("$.missing[1]").value(archived.toString()));

        // Reads change nothing
        assertThat(stockVersion(rice)).isEqualTo(2);                                 // increase + reserve
        assertThat(reserved(rice)).isEqualTo(3);
    }

    @Test
    void theWholeBatchIsReadWithOneQuery() throws Exception {
        UUID[] ids = IntStream.range(0, 20).mapToObj(i -> {
            try {
                return newProductWithStock(vendorId, "Batch item " + i, i);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toArray(UUID[]::new);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            availability(ids).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(20))
                    .andExpect(jsonPath("$.items[19].available").value(19));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
            assertThat(statistics.getQueryExecutionCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void readsTheLastCommittedStockWithoutWaitingForAStockChangeInFlight() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Honey", 10);
        ExecutorService single = Executors.newSingleThreadExecutor();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // Another stock change holds the row lock and has not committed yet
            execute(other, "SELECT product_id FROM stock_item WHERE product_id = UUID_TO_BIN(?) FOR UPDATE", productId);
            execute(other, "UPDATE stock_item SET reserved = reserved + 10, stock_version = stock_version + 1 "
                    + "WHERE product_id = UUID_TO_BIN(?)", productId);

            CompletableFuture<String> read = CompletableFuture.supplyAsync(() -> {
                try {
                    return availability(productId).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, single);
            // No FOR UPDATE / shared lock: answers at once, with the committed stock (not the 0 in flight)
            assertThat(json.readTree(read.get(5, TimeUnit.SECONDS)).get("items").get(0).get("available").asInt())
                    .isEqualTo(10);
            other.rollback();
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void theRequestNeedsOneToOneHundredProductIds() throws Exception {
        mvc.perform(post(PATH).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("productIds"));
        mvc.perform(post(PATH).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post(PATH).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        UUID[] tooMany = IntStream.range(0, 101).mapToObj(i -> Ids.newId()).toArray(UUID[]::new);
        availability(tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        UUID[] hundred = Arrays.copyOf(tooMany, 100);
        availability(hundred)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.missing.length()").value(100));
    }

    @Test
    void anyAuthenticatedCallerMayAskButAnonymousCallsAreRejected() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Tea", 6);
        String body = "{\"productIds\":[\"" + productId + "\"]}";

        // Zero trust: /internal/** still needs a valid JWT
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        // No role restriction: the customer's forwarded token, another vendor, an admin
        availability(productId).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].available").value(6));
        availability(vendor(UUID.randomUUID()), productId).andExpect(status().isOk());
        availability(as(admin, "SUPPORT"), productId).andExpect(status().isOk());
    }

    private static void execute(Connection connection, String sql, UUID productId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, productId.toString());
            statement.execute();
        }
    }
}
