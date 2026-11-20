package com.grocery.pricing;

import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.pricing.dto.CouponDtos.CouponView;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.dto.PriceDtos.PriceView;
import com.grocery.pricing.service.CouponOwner;
import com.grocery.pricing.service.CouponService;
import com.grocery.pricing.service.PriceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pricing exports: coupons (admin, vendor and restaurant lists), the vendor's prices and price-change requests
 * and the admin approval queue. Each follows its list's filters and order, shows only the caller's own rows, and is
 * read in keyset batches like the lists' pages.
 */
class PricingExportIntegrationTest extends AbstractIntegrationTest {

    private static final String STORE_COUPON_COLUMNS = "Discount type,Value (% or INR),Max discount (INR),"
            + "Min order (INR),Valid from,Valid until,Usage limit,Per customer,Public,Status,Used,"
            + "Discount given (INR),Created";

    private final UUID vendorId = UUID.randomUUID();

    @Autowired
    private CouponService coupons;
    @Autowired
    private PriceQueryService prices;

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private List<String> csv(String path, RequestPostProcessor caller) throws Exception {
        String body = new String(export(path, caller).getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    /** The first column of every row after the header. */
    private static List<String> firstColumn(List<String> lines) {
        return lines.subList(1, lines.size()).stream().map(line -> line.substring(0, line.indexOf(','))).toList();
    }

    /** Every row of an export read one per batch, so each row after the first starts at a keyset boundary. */
    private static <R> List<String> oneByOne(RowSource<R> rows, Function<R, String> value) throws Exception {
        List<String> values = new ArrayList<>();
        rows.forEachBatch(1, batch -> {
            batch.forEach(row -> values.add(value.apply(row)));
            return true;
        });
        return values;
    }

    private static CurrentUser vendorCaller(UUID vendorId) {
        return new CurrentUser(vendorUser(vendorId), null, null, Set.of("VENDOR"), vendorId, null);
    }

    private String storeCoupon(String basePath, RequestPostProcessor owner, String code, String name)
            throws Exception {
        String body = "{\"code\":\"%s\",\"name\":\"%s\",\"discountType\":\"FLAT\",\"value\":50,%s}"
                .formatted(code, name, validWindow());
        return body(createCoupon(basePath, owner, body).andExpect(status().isCreated())).get("id").asString();
    }

    @Test
    void couponExportsFollowTheListsFiltersSortsAndOwners() throws Exception {
        product(vendorId, "Basmati Rice");                                         // the store's name: Green Basket
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String name = "Export " + tag;
        String alpha = code("alpha");
        String mu = code("mu");
        String omega = code("omega");
        storeCoupon("/api/vendor/coupons", vendor(vendorId), alpha, name);
        String muId = storeCoupon("/api/vendor/coupons", vendor(vendorId), mu, name);
        storeCoupon("/api/vendor/coupons", vendor(vendorId), omega, name);
        mvc.perform(post("/api/vendor/coupons/{id}/pause", muId).with(vendor(vendorId))).andExpect(status().isOk());
        UUID otherVendor = UUID.randomUUID();
        String foreign = code("foreign");
        storeCoupon("/api/vendor/coupons", vendor(otherVendor), foreign, name);
        UUID restaurantId = UUID.randomUUID();
        String restaurants = code("resto");
        storeCoupon("/api/restaurant/coupons", restaurantOwner(restaurantId), restaurants, name);

        // the vendor's own coupons, newest first like the list
        List<String> mine = csv("/api/vendor/coupons/export?format=csv", vendor(vendorId));
        assertThat(mine.getFirst()).isEqualTo("Code,Name," + STORE_COUPON_COLUMNS);
        assertThat(firstColumn(mine)).containsExactly(omega, mu, alpha);
        assertThat(mine.get(1)).startsWith(omega + "," + name + ",Flat,50.00,,0.00,").contains(",,1,No,Active,0,0.00,");
        assertThat(mine.get(2)).contains(",Paused,");
        assertThat(firstColumn(csv("/api/vendor/coupons/export?format=csv&sort=code,asc", vendor(vendorId))))
                .containsExactly(alpha, mu, omega);
        assertThat(firstColumn(csv("/api/vendor/coupons/export?format=csv&status=PAUSED", vendor(vendorId))))
                .containsExactly(mu);
        assertThat(firstColumn(csv("/api/vendor/coupons/export?format=csv&q=" + alpha.toLowerCase(),
                vendor(vendorId)))).containsExactly(alpha);
        assertThat(oneByOne(coupons.listExport(CouponOwner.vendor(vendorCaller(vendorId)), null, null, null, null,
                "code,desc"), CouponView::code)).containsExactly(omega, mu, alpha);
        assertThat(oneByOne(coupons.listExport(CouponOwner.vendor(vendorCaller(vendorId)), null, null, null, null,
                null), CouponView::code)).containsExactly(omega, mu, alpha);

        // every other store sees only its own; the restaurant's export is the same file for its coupons
        assertThat(firstColumn(csv("/api/vendor/coupons/export?format=csv", vendor(otherVendor))))
                .containsExactly(foreign);
        List<String> restaurant = csv("/api/restaurant/coupons/export?format=csv&sort=validity,desc",
                restaurantOwner(restaurantId));
        assertThat(restaurant.getFirst()).isEqualTo("Code,Name," + STORE_COUPON_COLUMNS);
        assertThat(firstColumn(restaurant)).containsExactly(restaurants);

        // admins: the list's filters, the scope and the store
        List<String> admins = csv("/api/admin/coupons/export?format=csv&storeId=" + vendorId + "&sort=code",
                as(admin, "SUPPORT"));
        assertThat(admins.getFirst()).isEqualTo("Code,Name,Scope,Store type,Store,Store ID," + STORE_COUPON_COLUMNS);
        assertThat(firstColumn(admins)).containsExactly(alpha, mu, omega);
        assertThat(admins.get(1)).startsWith(alpha + "," + name + ",Store,Grocery,Green Basket," + vendorId
                + ",Flat,50.00,");
        assertThat(firstColumn(csv("/api/admin/coupons/export?format=csv&scope=STORE&status=PAUSED&q=" + tag,
                as(admin, "FINANCE_ADMIN")))).containsExactly(mu);

        mvc.perform(get("/api/admin/coupons/export?format=csv&sort=usage").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/coupons/export?format=docx").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/coupons/export?format=csv&status=NOPE").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/coupons/export?format=csv").with(vendor(vendorId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/restaurant/coupons/export?format=csv").with(vendor(vendorId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/coupons/export?format=csv").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/restaurant/coupons/export?format=csv").with(as(UUID.randomUUID(), "RESTAURANT_OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/coupons/export?format=csv")).andExpect(status().isUnauthorized());
    }

    @Test
    void priceExportsShowTheVendorsOwnPricesAndRequestsAndTheAdminQueue() throws Exception {
        UUID apple = pricedProduct(vendorId, "Apple", "100.00", "90.00");
        product(vendorId, "Banana");
        UUID cherry = pricedProduct(vendorId, "Cherry", "200.00", "180.00");
        UUID dates = pricedProduct(vendorId, "Dates", "300.00", "270.00");
        upsertProduct(dates, vendorId, "Dates", null, "ARCHIVED", 2);              // hidden like in the list
        String cherryRequest = pendingRequest(vendorId, cherry, "300.00", "290.00");
        String appleRequest = pendingRequest(vendorId, apple, "220.00", "200.00");
        UUID otherVendor = UUID.randomUUID();
        pricedProduct(otherVendor, "Apple", "100.00", "90.00");

        // the vendor's prices, by name like the list, with their live and pending prices
        List<String> priceLines = csv("/api/vendor/prices/export?format=csv", vendor(vendorId));
        assertThat(priceLines.getFirst()).isEqualTo("Product,SKU,Unit,Product status,MRP (INR),Selling price (INR),"
                + "Discount (%),Pending MRP (INR),Pending selling price (INR),Price updated");
        assertThat(firstColumn(priceLines)).containsExactly("Apple", "Banana", "Cherry");
        assertThat(priceLines.get(1)).startsWith("Apple,,500 g,Published,100.00,90.00,10,220.00,200.00,");
        assertThat(priceLines.get(2)).isEqualTo("Banana,,500 g,Published" + ",".repeat(6));
        assertThat(priceLines.get(3)).startsWith("Cherry,,500 g,Published,200.00,180.00,10,300.00,290.00,");
        assertThat(firstColumn(csv("/api/vendor/prices/export?format=csv&filter=UNPRICED", vendor(vendorId))))
                .containsExactly("Banana");
        assertThat(firstColumn(csv("/api/vendor/prices/export?format=csv&filter=PENDING_APPROVAL&q=cher",
                vendor(vendorId)))).containsExactly("Cherry");
        assertThat(oneByOne(prices.vendorListExport(vendorCaller(vendorId), null, null), PriceView::productName))
                .containsExactly("Apple", "Banana", "Cherry");
        assertThat(firstColumn(csv("/api/vendor/prices/export?format=csv", vendor(otherVendor))))
                .containsExactly("Apple");

        // the vendor's own requests, newest first
        List<String> requests = csv("/api/vendor/price-change-requests/export?format=csv", vendor(vendorId));
        assertThat(requests.getFirst()).isEqualTo("Product,Unit,MRP before (INR),Price before (INR),"
                + "Requested MRP (INR),Requested price (INR),Change (%),Status,Note,Requested,Decided,Decision reason");
        assertThat(firstColumn(requests)).containsExactly("Apple", "Cherry");
        assertThat(requests.get(1)).startsWith("Apple,500 g,100.00,90.00,220.00,200.00,122.22,Pending,,");
        assertThat(csv("/api/vendor/price-change-requests/export?format=csv", vendor(otherVendor))).hasSize(1);

        // the admin queue: PENDING by default, oldest first, or by a column the page sorts by
        List<String> queue = csv("/api/admin/price-change-requests/export?format=csv&vendorId=" + vendorId,
                as(admin, "SUPPORT"));
        assertThat(queue.getFirst()).isEqualTo("Product,Unit,Vendor,Vendor ID,MRP before (INR),Price before (INR),"
                + "Requested MRP (INR),Requested price (INR),Change (%),Status,Vendor note,Requested,Decided,"
                + "Decision reason");
        assertThat(firstColumn(queue)).containsExactly("Cherry", "Apple");
        assertThat(queue.get(1)).startsWith("Cherry,500 g,Green Basket," + vendorId
                + ",200.00,180.00,300.00,290.00,61.11,Pending,,");
        assertThat(firstColumn(csv("/api/admin/price-change-requests/export?format=csv&sort=change,desc&vendorId="
                + vendorId, as(admin, "SUPPORT")))).containsExactly("Apple", "Cherry");
        assertThat(oneByOne(prices.adminListExport(null, vendorId, "requested,asc"),
                PriceChangeRequestView::productName)).containsExactly("Apple", "Cherry");
        assertThat(oneByOne(prices.adminListExport(null, vendorId, null),
                (PriceChangeRequestView view) -> view.id().toString())).containsExactly(cherryRequest, appleRequest);

        approve(cherryRequest).andExpect(status().isOk());
        assertThat(firstColumn(csv("/api/admin/price-change-requests/export?format=csv&vendorId=" + vendorId,
                as(admin, "FINANCE_ADMIN")))).containsExactly("Apple");
        List<String> approved = csv("/api/admin/price-change-requests/export?format=csv&status=APPROVED&vendorId="
                + vendorId, as(admin, "FINANCE_ADMIN"));
        assertThat(approved).hasSize(2);
        assertThat(approved.get(1)).startsWith("Cherry,").contains(",Approved,");

        mvc.perform(get("/api/admin/price-change-requests/export?format=csv&sort=vendor").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/price-change-requests/export?format=docx").with(as(admin, "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/vendor/prices/export?format=csv&filter=CHEAP").with(vendor(vendorId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/price-change-requests/export?format=csv").with(vendor(vendorId)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/prices/export?format=csv").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/price-change-requests/export?format=csv").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/admin/price-change-requests/export?format=csv")).andExpect(status().isUnauthorized());

        MockHttpServletResponse pdf = export("/api/admin/price-change-requests/export?format=pdf&vendorId=" + vendorId,
                as(admin, "SUPER_ADMIN"));
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
