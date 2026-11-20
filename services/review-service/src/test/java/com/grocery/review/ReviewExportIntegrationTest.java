package com.grocery.review;

import com.grocery.common.export.ExportProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The review exports: the customer's own reviews (every status), a store's published reviews (its own store only, the
 * author by public name only) and the admin moderation list with its filters; each in its list's order, read in
 * small keyset batches here.
 */
class ReviewExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ExportProperties exportProperties;

    private int batchSize;

    @BeforeEach
    void smallBatches() {
        batchSize = exportProperties.getBatchSize();
        exportProperties.setBatchSize(2);
    }

    @AfterEach
    void restoreBatchSize() {
        exportProperties.setBatchSize(batchSize);
    }

    private MockHttpServletResponse export(String path, JwtRequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    /** Moves a review's creation (and submission) to {@code atUtc}. */
    private void writtenAt(JsonNode review, String atUtc) {
        jdbc().sql("UPDATE review SET created_at = ?, submitted_at = ? WHERE id = UUID_TO_BIN(?)")
                .params(atUtc, atUtc, id(review).toString()).update();
    }

    @Test
    void aCustomerExportsTheirOwnReviewsOfEveryStatusNewestFirst() throws Exception {
        Order order = deliveredRestaurantOrder(customer, "Dosa", "Idli");
        JsonNode dosa = review(customer, order.orderId(), "MENU_ITEM", order.item(0).itemId(), 5, "Crisp", null);
        JsonNode idli = review(customer, order.orderId(), "MENU_ITEM", order.item(1).itemId(), 1, null,
                "WhatsApp 98765-43210");
        JsonNode store = review(customer, order.orderId(), "RESTAURANT", order.storeId(), 4, null, null);
        moderate(id(idli), "reject", "Contact details", "SUPPORT").andExpect(status().isOk());
        writtenAt(dosa, "2026-11-01 04:30:00");
        writtenAt(idli, "2026-11-12 10:00:00");
        writtenAt(store, "2026-11-14 18:45:00");
        Order othersOrder = deliveredGroceryOrder(otherCustomer, "Milk");
        reviewFirstItem(othersOrder, 3);

        MockHttpServletResponse response = export("/api/reviews/mine/export?format=csv", customer(customer));
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"my-reviews-");
        List<String> lines = csvLines(response);
        assertThat(lines).containsExactly(
                "Written,Order number,Type,About,Store,Rating,Title,Comment,Status,Moderation reason,Edited,"
                        + "Store reply",
                "2026-11-15 00:15," + order.orderNumber() + ",Restaurant,Spice Route,Spice Route,4,,,Published,,No,",
                "2026-11-12 15:30," + order.orderNumber() + ",Menu item,Idli,Spice Route,1,,WhatsApp 98765-43210,"
                        + "Rejected,Contact details,No,",
                "2026-11-01 10:00," + order.orderNumber() + ",Menu item,Dosa,Spice Route,5,Crisp,,Published,,No,");

        List<String> others = csvLines(export("/api/reviews/mine/export?format=csv", customer(otherCustomer)));
        assertThat(others).hasSize(2);
        assertThat(String.join("\n", others)).doesNotContain(order.orderNumber());

        mvc.perform(get("/api/reviews/mine/export?format=docx").with(customer(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/reviews/mine/export?format=csv").with(vendor(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reviews/mine/export?format=csv").with(admin(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        // not one of the public reads
        mvc.perform(get("/api/reviews/mine/export?format=csv")).andExpect(status().isUnauthorized());
    }

    @Test
    void aStoreExportsItsOwnPublishedReviewsWithItsFilters() throws Exception {
        UUID vendorUser = UUID.randomUUID();
        Order order = deliveredGroceryOrder(customer, "Tomato", "Onion");
        JwtRequestPostProcessor owner = vendor(vendorUser, order.storeId());
        JsonNode tomato = review(customer, order.orderId(), "PRODUCT", order.item(0).itemId(), 5, "Fresh",
                "-Fresh and crisp");
        review(customer, order.orderId(), "PRODUCT", order.item(1).itemId(), 1, null, "visit onion-mart.com");
        review(customer, order.orderId(), "VENDOR", order.storeId(), 4, null, null);
        review(customer, order.orderId(), "DELIVERY_PARTNER", order.partnerId(), 2, null, null);
        mvc.perform(put("/api/store/reviews/{id}/reply", id(tomato)).with(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "Thanks, see you soon"))))
                .andExpect(status().isOk());

        MockHttpServletResponse response = export("/api/store/reviews/export?format=csv", owner);
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"store-reviews-");
        List<String> lines = csvLines(response);
        assertThat(lines.getFirst()).isEqualTo("Written,Rating,Title,Comment,Author,Type,About,Order number,Edited,"
                + "Your reply,Replied");
        // published only (not the flagged one, not the rider's), newest first
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).contains(",4,,,Asha S.,Vendor,Green Basket," + order.orderNumber() + ",No,,");
        // a text starting with - gets an apostrophe, so a spreadsheet never runs it as a formula
        assertThat(lines.get(2)).matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d,5,Fresh,'-Fresh and crisp,Asha S.,"
                + "Product,Tomato," + order.orderNumber() + ",No,\"Thanks, see you soon\","
                + "\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d");
        assertThat(String.join("\n", lines)).doesNotContain(customer.toString()).doesNotContain("@");

        List<String> products = csvLines(export("/api/store/reviews/export?format=csv&targetType=product", owner));
        assertThat(products).hasSize(2);
        assertThat(products.get(1)).contains(",Product,Tomato,");
        List<String> fourStars = csvLines(export("/api/store/reviews/export?format=csv&rating=4", owner));
        assertThat(fourStars).hasSize(2);
        assertThat(fourStars.get(1)).contains(",Vendor,Green Basket,");
        assertThat(csvLines(export("/api/store/reviews/export?format=csv&targetType=DELIVERY_PARTNER", owner)))
                .hasSize(1);

        // other stores get none of these
        assertThat(csvLines(export("/api/store/reviews/export?format=csv",
                vendor(UUID.randomUUID(), UUID.randomUUID())))).hasSize(1);
        assertThat(csvLines(export("/api/store/reviews/export?format=csv",
                restaurantOwner(UUID.randomUUID(), order.storeId())))).hasSize(1);

        mvc.perform(get("/api/store/reviews/export?format=csv&rating=6").with(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/store/reviews/export?format=docx").with(owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/store/reviews/export?format=csv").with(customer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/store/reviews/export?format=csv").with(vendor(vendorUser, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/store/reviews/export?format=csv")).andExpect(status().isUnauthorized());
    }

    @Test
    void theModerationExportFollowsTheListsFiltersForAnyAdmin() throws Exception {
        Order order = deliveredRestaurantOrder(customer, "Paneer Tikka");
        String marker = "zx" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode flaggedDish = review(customer, order.orderId(), "MENU_ITEM", order.item(0).itemId(), 1, marker,
                "call 9876543210");
        JsonNode flaggedStore = review(customer, order.orderId(), "RESTAURANT", order.storeId(), 2, marker,
                "see bad-food.com");
        review(customer, order.orderId(), "DELIVERY_PARTNER", order.partnerId(), 5, marker, "Polite");
        writtenAt(flaggedDish, "2026-11-14 18:45:00");
        writtenAt(flaggedStore, "2026-11-15 02:00:00");

        String header = "Submitted,Status,Rating,Title,Comment,Author,Customer ID,Type,About,Order number,Store,"
                + "Flag reason,Moderation note,Moderated,Edited,Store reply,Written";
        // FLAGGED by default, the most recently submitted first
        List<String> flagged = csvLines(export("/api/admin/reviews/export?format=csv&q=" + marker.toUpperCase(),
                admin(UUID.randomUUID(), "SUPPORT")));
        assertThat(flagged).containsExactly(header,
                "2026-11-15 07:30,Flagged,2," + marker + ",see bad-food.com,Asha S.," + customer + ",Restaurant,"
                        + "Spice Route," + order.orderNumber() + ",Spice Route,Link,,,No,,2026-11-15 07:30",
                "2026-11-15 00:15,Flagged,1," + marker + ",call 9876543210,Asha S.," + customer + ",Menu item,"
                        + "Paneer Tikka," + order.orderNumber() + ",Spice Route,Phone,,,No,,2026-11-15 00:15");

        List<String> published = csvLines(export("/api/admin/reviews/export?format=csv&status=published"
                + "&targetType=delivery_partner&q=" + marker, admin(UUID.randomUUID(), "FINANCE_ADMIN")));
        assertThat(published.getFirst()).isEqualTo(header);
        assertThat(published).hasSize(2);
        assertThat(published.get(1)).contains(",Published,5," + marker + ",Polite,Asha S.,");
        assertThat(csvLines(export("/api/admin/reviews/export?format=csv&targetType=PRODUCT&q=" + marker,
                admin(UUID.randomUUID(), "DELIVERY_MANAGER")))).hasSize(1);

        mvc.perform(get("/api/admin/reviews/export?format=csv&status=DELETED").with(admin(UUID.randomUUID(),
                        "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/reviews/export?format=docx").with(admin(UUID.randomUUID(), "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/reviews/export?format=csv").with(customer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reviews/export?format=csv").with(vendor(UUID.randomUUID(), order.storeId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/reviews/export?format=csv")).andExpect(status().isUnauthorized());
    }
}
