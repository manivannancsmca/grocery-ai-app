package com.grocery.catalog;

import com.grocery.catalog.dto.ProductDtos.AdminProductView;
import com.grocery.catalog.dto.ProductDtos.ProductView;
import com.grocery.catalog.service.ProductQueryService;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The product exports: a vendor's own catalog (only theirs, with the list's filters) and the admin product browser
 * (its filters and the sorts a client may choose), read in keyset batches like the lists' pages.
 */
class ProductExportIntegrationTest extends AbstractIntegrationTest {

    private static final String VENDOR_HEADER = "Product,SKU,Brand,Category,Unit,Food type,Barcode,Tags,Status,"
            + "Published,Created,Updated";

    private final UUID vendorId = UUID.randomUUID();
    private String rootName;
    private String rootId;
    private String leafId;

    @Autowired
    private ProductQueryService queries;

    @BeforeEach
    void categories() throws Exception {
        rootName = unique("Pantry");
        rootId = createCategory(rootName, null);
        leafId = createCategory("Spices", rootId);
    }

    private String createProduct(UUID vendor, String name, String sku) throws Exception {
        String json = """
                {"name":"%s","categoryId":"%s","brandName":null,"unitValue":100,"unitOfMeasure":"G","sku":%s,
                 "barcode":"8901234567890","foodType":"VEG","tags":["Organic","fresh"]}"""
                .formatted(name, leafId, sku == null ? "null" : "\"" + sku + "\"");
        return body(mvc.perform(post("/api/vendor/products").with(vendor(vendor))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())).get("id").asString();
    }

    private void publish(String productId) throws Exception {
        mvc.perform(post("/api/vendor/products/{id}/publish", productId).with(vendor(vendorId)))
                .andExpect(status().isOk());
    }

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    /** Every row of an export read in batches of two, so the keyset boundary between batches is crossed. */
    private static <R> List<String> inBatchesOfTwo(RowSource<R> rows, Function<R, String> name) throws Exception {
        List<String> names = new ArrayList<>();
        rows.forEachBatch(2, batch -> {
            batch.forEach(row -> names.add(name.apply(row)));
            return true;
        });
        return names;
    }

    @Test
    void aVendorExportsTheirOwnProductsWithTheListsFilters() throws Exception {
        String tomato = createProduct(vendorId, "Cherry Tomato", "CT-1");
        createProduct(vendorId, "@Home Spice Mix", "HS-1");
        String garlic = createProduct(vendorId, "Garlic", "GA-1");
        publish(tomato);
        mvc.perform(delete("/api/vendor/products/{id}", garlic).with(vendor(vendorId)))
                .andExpect(status().isNoContent());
        UUID otherVendor = UUID.randomUUID();
        createProduct(otherVendor, "Someone Else's Pepper", "SP-1");

        MockHttpServletResponse response = export("/api/vendor/products/export?format=csv", vendor(vendorId));

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"my-products-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("2");
        List<String> lines = csvLines(response);
        assertThat(lines.getFirst()).isEqualTo(VENDOR_HEADER);
        assertThat(lines).hasSize(3);
        // most recently changed first, like the list; archived products only when asked for
        assertThat(lines.get(1)).startsWith("Cherry Tomato,CT-1,," + rootName + " › Spices,100 g,Veg,8901234567890,"
                + "\"organic, fresh\",Published,");
        // a value a spreadsheet would run as a formula gets an apostrophe
        assertThat(lines.get(2)).startsWith("'@Home Spice Mix,HS-1,").contains(",Draft,,");
        assertThat(String.join("\n", lines)).doesNotContain("Garlic").doesNotContain("Pepper");

        List<String> archived = csvLines(export("/api/vendor/products/export?format=csv&status=ARCHIVED",
                vendor(vendorId)));
        assertThat(archived).hasSize(2);
        assertThat(archived.get(1)).startsWith("Garlic,GA-1,").contains(",Archived,");
        List<String> bySku = csvLines(export("/api/vendor/products/export?format=csv&q=ct-1", vendor(vendorId)));
        assertThat(bySku).hasSize(2);
        assertThat(bySku.get(1)).startsWith("Cherry Tomato,");

        // another vendor gets only their own; a vendor not (yet) approved, a customer and no token get nothing
        assertThat(csvLines(export("/api/vendor/products/export?format=csv", vendor(otherVendor))))
                .hasSize(2).noneMatch(line -> line.contains("Tomato"));
        mvc.perform(get("/api/vendor/products/export?format=csv").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/products/export?format=csv").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/products/export?format=csv")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vendor/products/export?format=docx").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/products/export?format=csv&status=NOPE").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        CurrentUser owner = new CurrentUser(UUID.nameUUIDFromBytes(vendorId.toString().getBytes()), null, null,
                Set.of("VENDOR"), vendorId, null);
        createProduct(vendorId, "Bay Leaf", null);
        assertThat(inBatchesOfTwo(queries.vendorExport(owner, null, null), ProductView::name))
                .containsExactly("Bay Leaf", "Cherry Tomato", "@Home Spice Mix");
    }

    @Test
    void theAdminExportFollowsTheFiltersAndTheChosenSort() throws Exception {
        JdbcClient.create(dataSource)
                .sql("INSERT INTO vendor_ref (vendor_id, business_name, status, updated_at) "
                        + "VALUES (UUID_TO_BIN(?), 'Spice Route Traders', 'APPROVED', NOW(6))")
                .param(vendorId.toString()).update();
        String onion = createProduct(vendorId, "Red Onion", "RO-1");
        String tomato = createProduct(vendorId, "Cherry Tomato", "CT-1");
        String garlic = createProduct(vendorId, "Garlic", "GA-1");
        publish(tomato);
        publish(garlic);
        mvc.perform(post("/api/vendor/products/{id}/unpublish", garlic).with(vendor(vendorId)))
                .andExpect(status().isOk());
        createProduct(UUID.randomUUID(), "Cherry Tomato", "CT-1");

        List<String> lines = csvLines(export("/api/admin/products/export?format=csv&vendorId=" + vendorId,
                as(admin, "SUPPORT")));
        assertThat(lines.getFirst()).isEqualTo("Product,SKU,Brand,Vendor,Vendor ID,Category,Unit,Food type,Barcode,"
                + "Status,Published,Created,Updated");
        assertThat(lines).hasSize(4);
        assertThat(lines.get(1)).startsWith("Garlic,GA-1,,Spice Route Traders," + vendorId + "," + rootName
                + " › Spices,100 g,Veg,8901234567890,Unpublished,");
        assertThat(lines.get(2)).startsWith("Cherry Tomato,");
        assertThat(lines.get(3)).startsWith("Red Onion,");

        // the list's filters: the category with its sub-categories, the search, the status
        List<String> filtered = csvLines(export("/api/admin/products/export?format=csv&categoryId=" + rootId
                + "&q=onion&vendorId=" + vendorId, as(admin, "FINANCE_ADMIN")));
        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(1)).startsWith("Red Onion,RO-1,");
        List<String> published = csvLines(export("/api/admin/products/export?format=csv&status=PUBLISHED&vendorId="
                + vendorId, as(admin, "OPS_ADMIN")));
        assertThat(published).hasSize(2);
        assertThat(published.get(1)).startsWith("Cherry Tomato,");

        // the columns a client may sort by, named like the page's column keys
        List<String> byName = csvLines(export("/api/admin/products/export?format=csv&sort=product,asc&vendorId="
                + vendorId, as(admin, "SUPPORT")));
        assertThat(byName.subList(1, 4)).extracting(line -> line.substring(0, line.indexOf(',')))
                .containsExactly("Cherry Tomato", "Garlic", "Red Onion");
        List<String> byStatus = csvLines(export("/api/admin/products/export?format=csv&sort=status,desc&vendorId="
                + vendorId, as(admin, "SUPPORT")));
        assertThat(byStatus.subList(1, 4)).extracting(line -> line.substring(0, line.indexOf(',')))
                .containsExactly("Garlic", "Cherry Tomato", "Red Onion");
        assertThat(inBatchesOfTwo(queries.adminExport(vendorId, null, null, null, "status,asc"),
                (AdminProductView row) -> row.product().name()))
                .containsExactly("Red Onion", "Cherry Tomato", "Garlic");
        assertThat(inBatchesOfTwo(queries.adminExport(vendorId, null, null, null, null),
                (AdminProductView row) -> row.product().id().toString()))
                .containsExactly(garlic, tomato, onion);

        mvc.perform(get("/api/admin/products/export?format=csv&sort=vendor").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/products/export?format=docx").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/products/export?format=csv").with(vendor(vendorId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/products/export?format=csv")).andExpect(status().isUnauthorized());

        MockHttpServletResponse xlsx = export("/api/admin/products/export?format=xlsx&vendorId=" + vendorId,
                as(admin, "SUPPORT"));
        assertThat(xlsx.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MockHttpServletResponse pdf = export("/api/admin/products/export?format=pdf&vendorId=" + vendorId,
                as(admin, "SUPER_ADMIN"));
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
