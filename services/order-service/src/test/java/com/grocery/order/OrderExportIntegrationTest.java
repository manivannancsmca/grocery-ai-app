package com.grocery.order;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order and return exports: the list's filters on every page, only what the caller may see (the customer's own
 * orders, the store's own returns, admins only for the admin monitor), and the three formats.
 */
class OrderExportIntegrationTest extends AbstractIntegrationTest {

    private final UUID admin = UUID.randomUUID();

    private MockHttpServletResponse export(String path, org.springframework.test.web.servlet.request.RequestPostProcessor
            caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    private String orderNumber(UUID customerId, UUID orderId) throws Exception {
        return order(customerId, orderId).get("orderNumber").asString();
    }

    @Test
    void theCustomerExportsTheirOwnOrdersOnlyWithTheListsFilter() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID grocery = newGroceryOrder(customerId);
        UUID restaurant = newRestaurantOrder(customerId);
        UUID otherCustomer = UUID.randomUUID();
        UUID someoneElses = newGroceryOrder(otherCustomer);

        MockHttpServletResponse response = export("/api/orders/export?format=csv", customer(customerId));

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"my-orders-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("2");
        List<String> lines = csvLines(response);
        assertThat(lines.getFirst()).isEqualTo("Order number,Placed,Type,Store,Items,First item,Status,Payment,Coupon,"
                + "Discount (INR),Total (INR)");
        assertThat(lines).hasSize(3);
        // newest first, like the list
        assertThat(lines.get(1)).startsWith(orderNumber(customerId, restaurant) + ",")
                .contains(",Restaurant,Spice Route,3,Chicken Biryani,Awaiting payment,Pending,,0.00,359.00");
        assertThat(lines.get(2)).startsWith(orderNumber(customerId, grocery) + ",");
        assertThat(String.join("\n", lines)).doesNotContain(orderNumber(otherCustomer, someoneElses));

        List<String> open = csvLines(export("/api/orders/export?format=csv&status=CANCELLED", customer(customerId)));
        assertThat(open).hasSize(1); // the header only: no cancelled order
    }

    @Test
    void theAdminExportFollowsTheFiltersAndTheChosenSort() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID first = newGroceryOrder(customerId);
        UUID second = newRestaurantOrder(customerId);
        newGroceryOrder(UUID.randomUUID());

        List<String> lines = csvLines(export("/api/admin/orders/export?format=csv&customerId=" + customerId,
                as(admin, "SUPPORT")));
        assertThat(lines.getFirst()).isEqualTo("Order number,Placed,Type,Store,Store ID,Customer ID,Items,Status,"
                + "Payment,Coupon,Discount (INR),Delivery fee discount (INR),Total (INR)");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith(orderNumber(customerId, second) + ",").contains("," + customerId + ",");

        // by total, smallest first: the grocery order (245.00) before the restaurant order (359.00)
        List<String> byTotal = csvLines(export("/api/admin/orders/export?format=csv&sort=total,asc&customerId="
                + customerId, as(admin, "OPS_ADMIN")));
        assertThat(byTotal.get(1)).startsWith(orderNumber(customerId, first) + ",");
        assertThat(byTotal.get(2)).startsWith(orderNumber(customerId, second) + ",");

        mvc.perform(get("/api/admin/orders/export?format=csv&sort=customerEmail").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/orders/export?format=docx").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/orders/export?format=csv&from=2026-11-10&to=2026-11-01").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdminsExportTheMonitorAndEveryFormatOpens() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = newGroceryOrder(customerId);

        mvc.perform(get("/api/admin/orders/export?format=csv").with(customer(customerId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/orders/export?format=csv").with(as(admin, "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/orders/export?format=csv")).andExpect(status().isUnauthorized());

        MockHttpServletResponse xlsx = export("/api/admin/orders/export?format=xlsx&customerId=" + customerId,
                as(admin, "FINANCE_ADMIN"));
        assertThat(xlsx.getContentType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
            // the title (row 1), the line with the filters (row 2), the headers (row 4), then the one order (row 5)
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(rows.getFirst().getCellText(0)).isEqualTo("Orders");
            assertThat(rows.get(1).getCellText(0)).contains("1 row", "Customer ID: " + customerId);
            Row header = rows.stream().filter(row -> row.getRowNum() == 4).findFirst().orElseThrow();
            assertThat(header.getCellText(0)).isEqualTo("Order number");
            List<Row> data = rows.stream().filter(row -> row.getRowNum() > 4).toList();
            assertThat(data).hasSize(1);
            assertThat(data.getFirst().getCellText(0)).isEqualTo(orderNumber(customerId, orderId));
            assertThat(data.getFirst().getCellAsNumber(12)).hasValueSatisfying(total ->
                    assertThat(total).isEqualByComparingTo(GROCERY_TOTAL));
        }

        MockHttpServletResponse pdf = export("/api/admin/orders/export?format=pdf&customerId=" + customerId,
                as(admin, "SUPER_ADMIN"));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void aStoreExportsItsOwnReturnsAndACustomerTheirs() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = deliveredOrder(customerId);
        String returnNumber = body(requestReturn(customerId, orderId, Map.of(itemIds(customerId, orderId).getFirst(), 1),
                "QUALITY", "Cold").andExpect(status().isCreated())).get("returnNumber").asString();

        List<String> store = csvLines(export("/api/store/returns/export?format=csv",
                storeUser(UUID.randomUUID(), "RESTAURANT_OWNER", storeIdOf(orderId))));
        assertThat(store.getFirst()).startsWith("Return number,Requested,Order number,Type,Store,Status,Reason,Items,");
        assertThat(store).anyMatch(line -> line.startsWith(returnNumber + ",")
                && line.contains(",Requested,Quality,1 × Chicken Biryani,"));

        List<String> otherStore = csvLines(export("/api/store/returns/export?format=csv",
                storeUser(UUID.randomUUID(), "RESTAURANT_OWNER", UUID.randomUUID())));
        assertThat(otherStore).hasSize(1);

        List<String> mine = csvLines(export("/api/returns/export?format=csv", customer(customerId)));
        assertThat(mine).hasSize(2);
        assertThat(mine.get(1)).startsWith(returnNumber + ",");
        assertThat(csvLines(export("/api/returns/export?format=csv", customer(UUID.randomUUID())))).hasSize(1);

        List<String> admins = csvLines(export("/api/admin/returns/export?format=csv&storeId=" + storeIdOf(orderId),
                as(admin, "SUPPORT")));
        assertThat(admins).anyMatch(line -> line.startsWith(returnNumber + ","));
        mvc.perform(get("/api/store/returns/export?format=csv").with(customer(customerId)))
                .andExpect(status().isForbidden());
    }
}
