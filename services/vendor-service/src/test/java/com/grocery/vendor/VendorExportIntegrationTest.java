package com.grocery.vendor;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The vendor-service exports: the admin vendor list (contact details only for support and operations admins), every
 * vendor's purchase orders for admins, and a vendor's own purchase orders, suppliers and order board — the list's
 * filters and order, and never another vendor's rows.
 */
class VendorExportIntegrationTest extends PurchasingTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-10T04:30:00Z");       // 10:00 India time

    private final UUID admin = UUID.randomUUID();

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private List<String> csv(String path, RequestPostProcessor caller) throws Exception {
        MockHttpServletResponse response = export(path, caller);
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=");
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿").endsWith("\r\n");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    private void badRequest(String path, RequestPostProcessor caller) throws Exception {
        mvc.perform(get(path).with(caller))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    // ---- admin: vendors ---------------------------------------------------------------------------------------

    private String saveProfile(UUID owner, String businessName, String email) throws Exception {
        String body = """
                {"businessName":"%s","legalName":"%s Pvt Ltd","gstin":"29ABCDE1234F1Z5",
                 "fssaiLicense":"12345678901234","contactEmail":"%s","contactPhone":"+919876543210",
                 "address":{"line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                            "country":"IN","latitude":12.9716,"longitude":77.5946}}""".formatted(businessName,
                businessName, email);
        return body(mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())).get("id").asString();
    }

    private void submit(UUID owner) throws Exception {
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR"))).andExpect(status().isOk());
    }

    @Test
    void theAdminVendorListFollowsItsFiltersAndOrderAndHidesContactsFromFinance() throws Exception {
        String token = "Exp" + UUID.randomUUID().toString().substring(0, 8);
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        clock.set(T0);
        String vendorA = saveProfile(ownerA, token + " Mart A", "ops-a@example.in");
        clock.set(T0.plus(Duration.ofHours(1)));
        saveProfile(ownerB, token + " Mart B", "ops-b@example.in");
        clock.set(T0.plus(Duration.ofHours(2)));
        submit(ownerB);
        clock.set(T0.plus(Duration.ofHours(3)));
        submit(ownerA);

        // most recently changed first: A (submitted last), then B
        List<String> support = csv("/api/admin/vendors/export?format=csv&query=" + token, as(admin, "SUPPORT"));
        assertThat(support.getFirst()).isEqualTo("Business name,Legal name,Vendor ID,Status,GSTIN,FSSAI licence,"
                + "Contact email,Contact phone,Street address,City,State,PIN code,Status reason,Submitted,Approved,"
                + "Registered,Last change");
        assertThat(support).hasSize(3);
        // a phone starting with + gets an apostrophe in CSV, so a spreadsheet never runs it as a formula
        assertThat(support.get(1)).isEqualTo(token + " Mart A," + token + " Mart A Pvt Ltd," + vendorA
                + ",Pending approval,29ABCDE1234F1Z5,12345678901234,ops-a@example.in,'+919876543210,12 MG Road,"
                + "Bengaluru,Karnataka,560001,,2026-11-10 13:00,,2026-11-10 10:00,2026-11-10 13:00");
        assertThat(support.get(2)).startsWith(token + " Mart B,");

        // the review queue: oldest submission first (B, then A); finance admins get no contact details
        List<String> finance = csv("/api/admin/vendors/export?format=csv&status=PENDING_APPROVAL&query="
                + token.toUpperCase(Locale.ROOT), as(admin, "FINANCE_ADMIN"));
        assertThat(finance.getFirst()).isEqualTo("Business name,Legal name,Vendor ID,Status,GSTIN,FSSAI licence,"
                + "City,State,PIN code,Status reason,Submitted,Approved,Registered,Last change");
        assertThat(finance).hasSize(3);
        assertThat(finance.get(1)).startsWith(token + " Mart B,");
        assertThat(finance.get(2)).startsWith(token + " Mart A,");
        assertThat(String.join("\n", finance)).doesNotContain("919876543210").doesNotContain("12 MG Road")
                .doesNotContain("@example.in");

        assertThat(csv("/api/admin/vendors/export?format=csv&status=APPROVED&query=" + token, as(admin, "OPS_ADMIN")))
                .hasSize(1);

        badRequest("/api/admin/vendors/export?format=docx", as(admin, "SUPPORT"));
        badRequest("/api/admin/vendors/export", as(admin, "SUPPORT"));
        badRequest("/api/admin/vendors/export?format=csv&status=NOPE", as(admin, "SUPPORT"));
        mvc.perform(get("/api/admin/vendors/export?format=csv").with(as(ownerA, "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/vendors/export?format=csv")).andExpect(status().isUnauthorized());
    }

    // ---- admin: purchase orders -------------------------------------------------------------------------------

    @Test
    void theAdminPurchaseOrderExportFollowsTheFiltersAndTheChosenSort() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Basmati Rice");
        UUID dal = product(store, "Toor Dal");
        UUID supplierId = supplier(store, "Sharma Traders");
        clock.set(T0);
        String first = createPo(store, supplierId, List.of(poLine(rice, 10, "45.50"))).get("number").asString();
        clock.set(T0.plus(Duration.ofHours(1)));
        UUID secondId = sentPo(store, supplierId, List.of(poLine(rice, 2, "12.25"), poLine(dal, 3, "100.00")));
        String second = poNumber(store, secondId);
        Store otherStore = newStore();
        String othersPo = createPo(otherStore, supplier(otherStore, "Other Traders"),
                List.of(poLine(product(otherStore, "Sugar"), 1, "40.00"))).get("number").asString();

        List<String> lines = csv("/api/admin/purchase-orders/export?format=csv&vendorId=" + store.vendorId(),
                as(admin, "FINANCE_ADMIN"));
        assertThat(lines.getFirst()).isEqualTo("PO number,Vendor ID,Supplier,Status,Products,Units ordered,"
                + "Units received,Total (INR),Received value (INR),Expected,Notes,Created,Sent,Fully received,Closed,"
                + "Cancelled");
        assertThat(lines).hasSize(3);
        // newest first, like the list
        assertThat(lines.get(1)).isEqualTo(second + "," + store.vendorId() + ",Sharma Traders,Sent,2,5,0,324.50,0.00,"
                + ",,2026-11-10 11:00,2026-11-10 11:00,,,");
        assertThat(lines.get(2)).startsWith(first + "," + store.vendorId() + ",Sharma Traders,Draft,1,10,0,455.00,");
        assertThat(String.join("\n", lines)).doesNotContain(othersPo);

        List<String> sent = csv("/api/admin/purchase-orders/export?format=csv&status=SENT&vendorId="
                + store.vendorId(), as(admin, "SUPPORT"));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).startsWith(second + ",");

        List<String> oldestFirst = csv("/api/admin/purchase-orders/export?format=csv&sort=created,asc&vendorId="
                + store.vendorId(), as(admin, "OPS_ADMIN"));
        assertThat(oldestFirst.get(1)).startsWith(first + ",");
        assertThat(oldestFirst.get(2)).startsWith(second + ",");

        List<String> byNumber = csv("/api/admin/purchase-orders/export?format=csv&sort=number,desc&vendorId="
                + store.vendorId(), as(admin, "OPS_ADMIN"));
        String larger = first.compareTo(second) > 0 ? first : second;
        assertThat(byNumber.get(1)).startsWith(larger + ",");

        badRequest("/api/admin/purchase-orders/export?format=csv&sort=total", as(admin, "SUPPORT"));
        badRequest("/api/admin/purchase-orders/export?format=docx", as(admin, "SUPPORT"));
        badRequest("/api/admin/purchase-orders/export?format=csv&vendorId=not-a-uuid", as(admin, "SUPPORT"));
        mvc.perform(get("/api/admin/purchase-orders/export?format=csv").with(vendor(store)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/purchase-orders/export?format=csv")).andExpect(status().isUnauthorized());
    }

    private String poNumber(Store store, UUID poId) throws Exception {
        return body(mvc.perform(get("/api/vendor/purchase-orders/{id}", poId).with(vendor(store)))
                .andExpect(status().isOk())).get("number").asString();
    }

    // ---- vendor: purchase orders and suppliers ----------------------------------------------------------------

    @Test
    void aVendorExportsItsOwnPurchaseOrdersAndSuppliersOnly() throws Exception {
        Store store = newStore();
        Store other = newStore();
        UUID zen = UUID.fromString(body(createSupplierRequest(store, """
                {"name":"=Zen Foods","contactName":"Meera","phone":"+919811112222","email":"meera@zen.example",
                 "gstin":"29ABCDE1234F1Z5","address":"5 Market Road, Pune","leadTimeDays":2,
                 "notes":"Calls before delivery"}""").andExpect(status().isCreated())).get("id").asString());
        UUID aarav = supplier(store, "Aarav Agro");
        mvc.perform(delete("/api/vendor/suppliers/{id}", aarav).with(vendor(store)))
                .andExpect(status().isNoContent());
        supplier(other, "Elsewhere Supplies");

        // active first, then by name; a name starting with = is written as text
        List<String> suppliers = csv("/api/vendor/suppliers/export?format=csv", vendor(store));
        assertThat(suppliers.getFirst()).isEqualTo("Supplier,GSTIN,Contact person,Phone,Email,Address,"
                + "Lead time (days),Active,Notes,Added,Last change");
        assertThat(suppliers).hasSize(3);
        assertThat(suppliers.get(1)).startsWith("'=Zen Foods,29ABCDE1234F1Z5,Meera,'+919811112222,meera@zen.example,"
                + "\"5 Market Road, Pune\",2,Yes,Calls before delivery,");
        assertThat(suppliers.get(2)).startsWith("Aarav Agro,,,,,,3,No,,");
        assertThat(String.join("\n", suppliers)).doesNotContain("Elsewhere Supplies");
        assertThat(csv("/api/vendor/suppliers/export?format=csv&active=false", vendor(store))).hasSize(2)
                .last().asString().startsWith("Aarav Agro,");
        assertThat(csv("/api/vendor/suppliers/export?format=csv", vendor(other))).hasSize(2);

        UUID rice = product(store, "Basmati Rice");
        clock.set(T0);
        String first = createPo(store, zen, List.of(poLine(rice, 4, "50.00"))).get("number").asString();
        clock.set(T0.plus(Duration.ofHours(1)));
        String second = poNumber(store, sentPo(store, zen, List.of(poLine(rice, 1, "1234.50"))));

        List<String> orders = csv("/api/vendor/purchase-orders/export?format=csv", vendor(store));
        assertThat(orders.getFirst()).isEqualTo("PO number,Supplier,Status,Products,Units ordered,Units received,"
                + "Total (INR),Received value (INR),Expected,Notes,Created,Sent,Fully received,Closed,Cancelled");
        assertThat(orders).hasSize(3);
        assertThat(orders.get(1)).startsWith(second + ",'=Zen Foods,Sent,1,1,0,1234.50,0.00,");
        assertThat(orders.get(2)).startsWith(first + ",'=Zen Foods,Draft,1,4,0,200.00,0.00,");

        assertThat(csv("/api/vendor/purchase-orders/export?format=csv&status=DRAFT", vendor(store))).hasSize(2)
                .last().asString().startsWith(first + ",");
        assertThat(csv("/api/vendor/purchase-orders/export?format=csv&number=" + first.toLowerCase(Locale.ROOT),
                vendor(store))).hasSize(2).last().asString().startsWith(first + ",");
        assertThat(csv("/api/vendor/purchase-orders/export?format=csv&supplierId=" + aarav, vendor(store)))
                .hasSize(1);
        assertThat(csv("/api/vendor/purchase-orders/export?format=csv", vendor(other))).hasSize(1);

        badRequest("/api/vendor/purchase-orders/export?format=docx", vendor(store));
        badRequest("/api/vendor/suppliers/export?format=csv&active=maybe", vendor(store));
        // a vendor whose store is not approved (no vendor_id claim) gets nothing
        mvc.perform(get("/api/vendor/purchase-orders/export?format=csv").with(as(store.ownerUserId(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/suppliers/export?format=csv").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/suppliers/export?format=csv")).andExpect(status().isUnauthorized());
    }

    // ---- vendor: the order board ------------------------------------------------------------------------------

    private void act(Store store, UUID orderId, String action, String body) throws Exception {
        var request = post("/api/vendor/orders/{id}/" + action, orderId).with(vendor(store));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    @Test
    void theOrderBoardExportKeepsTheBoardsOrderAndOnlyTheStoresOwnOrders() throws Exception {
        Store store = newStore();
        Store other = newStore();
        clock.set(T0);
        UUID packing = confirm(store);
        clock.set(T0.plusSeconds(30));
        act(store, packing, "accept", "{\"readyInMinutes\":20}");
        clock.set(T0.plusSeconds(60));
        act(store, packing, "preparing", null);
        clock.set(T0.plus(Duration.ofHours(1)));
        UUID older = confirm(store);
        clock.set(T0.plus(Duration.ofHours(2)));
        UUID newer = confirm(store);
        clock.set(T0.plus(Duration.ofHours(3)));
        UUID rejected = confirm(store);
        clock.set(T0.plus(Duration.ofHours(3)).plusSeconds(30));
        act(store, rejected, "reject", "{\"reason\":\"OUT_OF_STOCK\"}");
        UUID othersOrder = confirm(other);

        // NEW first by deadline (the older one is more urgent), then the rest newest first
        List<String> lines = csv("/api/vendor/orders/export?format=csv", vendor(store));
        assertThat(lines.getFirst()).isEqualTo("Order number,Status,Received,Customer,Customer area,Items,Products,"
                + "Subtotal (INR),Total (INR),Note,Accept by,Accepted,Ready by,Packing started,Ready,Picked up,"
                + "Rejection reason,Auto-rejected,Rejection note,Rejected,Cancellation reason,Cancelled by,Cancelled");
        assertThat(lines).hasSize(5);
        assertThat(lines.get(1)).startsWith(orderNumber(older) + ",New,2026-11-10 11:00,Ravi Kumar,"
                + "\"Indiranagar, Bengaluru\",3,2 × Basmati Rice; 1 × Toor Dal,220.00,245.00,Ring the bell twice,"
                + "2026-11-10 11:05,");
        assertThat(lines.get(2)).startsWith(orderNumber(newer) + ",New,");
        assertThat(lines.get(3)).startsWith(orderNumber(rejected) + ",Rejected,")
                .endsWith(",Out of stock,No,,2026-11-10 13:00,,,");
        assertThat(lines.get(4)).startsWith(orderNumber(packing) + ",Packing,2026-11-10 10:00,")
                .contains(",2026-11-10 10:05,2026-11-10 10:00,2026-11-10 10:20,2026-11-10 10:01,");
        // the store never gets the customer's phone or street
        assertThat(String.join("\n", lines)).doesNotContain("919876543210").doesNotContain("42 HAL 2nd Stage")
                .doesNotContain(orderNumber(othersOrder));

        List<String> active = csv("/api/vendor/orders/export?format=csv&status=ACTIVE", vendor(store));
        assertThat(active).hasSize(4);
        assertThat(active.get(1)).startsWith(orderNumber(older) + ",");
        assertThat(active.get(3)).startsWith(orderNumber(packing) + ",");
        assertThat(csv("/api/vendor/orders/export?format=csv&status=REJECTED", vendor(store))).hasSize(2)
                .last().asString().startsWith(orderNumber(rejected) + ",");
        List<String> others = csv("/api/vendor/orders/export?format=csv", vendor(other));
        assertThat(others).hasSize(2);
        assertThat(others.get(1)).startsWith(orderNumber(othersOrder) + ",");

        MockHttpServletResponse xlsx = export("/api/vendor/orders/export?format=xlsx", vendor(store));
        assertThat(xlsx.getContentType()).isEqualTo(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        MockHttpServletResponse pdf = export("/api/vendor/orders/export?format=pdf&status=NEW", vendor(store));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        badRequest("/api/vendor/orders/export?format=docx", vendor(store));
        badRequest("/api/vendor/orders/export?format=csv&status=NOPE", vendor(store));
        mvc.perform(get("/api/vendor/orders/export?format=csv").with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/orders/export?format=csv")).andExpect(status().isUnauthorized());
    }
}
