package com.grocery.inventory;

import com.grocery.inventory.service.StockQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The vendor's stock export: the list's search and filter on every page, by product name, only the caller's own
 * products (never archived ones), read in keyset batches like the list's pages.
 */
class InventoryExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private StockQueryService queries;

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    /** The first column (the product name) of every row after the header. */
    private static List<String> names(List<String> lines) {
        return lines.subList(1, lines.size()).stream().map(line -> line.substring(0, line.indexOf(','))).toList();
    }

    @Test
    void aVendorExportsTheirOwnStockByNameWithTheListsSearchAndFilter() throws Exception {
        newProductWithStock(vendorId, "Apple", 20);
        newProductWithStock(vendorId, "banana", 3);                                 // low (alert level 5)
        newProduct(vendorId, "Cherry");                                             // out of stock
        UUID dates = newProductWithStock(vendorId, "Dates", 2);
        upsert(dates, vendorId, "Dates", null, "ARCHIVED", 2);                      // hidden like in the list
        UUID other = UUID.randomUUID();
        newProductWithStock(other, "Apple of another vendor", 5);

        MockHttpServletResponse response = export("/api/vendor/inventory/export?format=csv", vendor(vendorId));

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"inventory-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("3");
        List<String> lines = csvLines(response);
        assertThat(lines.getFirst()).isEqualTo("Product,SKU,Unit,On hand,Reserved,Available,Alert level,Stock,"
                + "Product status,Updated");
        assertThat(lines).hasSize(4);
        assertThat(lines.get(1)).startsWith("Apple,,500 g,20,0,20,5,In stock,Published,");
        assertThat(lines.get(2)).startsWith("banana,,500 g,3,0,3,5,Low stock,Published,");
        assertThat(lines.get(3)).startsWith("Cherry,,500 g,0,0,0,5,Out of stock,Published,");
        assertThat(String.join("\n", lines)).doesNotContain("Dates").doesNotContain("another vendor");

        assertThat(names(csvLines(export("/api/vendor/inventory/export?format=csv&filter=LOW_STOCK",
                vendor(vendorId))))).containsExactly("banana", "Cherry");
        assertThat(names(csvLines(export("/api/vendor/inventory/export?format=csv&filter=IN_STOCK&q=AN",
                vendor(vendorId))))).containsExactly("banana");
        assertThat(names(csvLines(export("/api/vendor/inventory/export?format=csv", vendor(other)))))
                .containsExactly("Apple of another vendor");

        // read in batches of two, the keyset boundary (product name, then id) gives the same rows in the same order
        List<String> names = new ArrayList<>();
        queries.listExport(vendorUser(vendorId), null, null).forEachBatch(2, batch -> {
            batch.forEach(row -> names.add(row.productName()));
            return true;
        });
        assertThat(names).containsExactly("Apple", "banana", "Cherry");

        mvc.perform(get("/api/vendor/inventory/export?format=csv&filter=ALMOST_OUT").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/inventory/export?format=docx").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/inventory/export?format=csv").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/inventory/export?format=csv").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/inventory/export?format=csv")).andExpect(status().isUnauthorized());

        MockHttpServletResponse pdf = export("/api/vendor/inventory/export?format=pdf", vendor(vendorId));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
