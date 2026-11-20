package com.grocery.vendor;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Suppliers (Phase 12 §4): CRUD, validation, ownership, delete = deactivate and SUPPLIER_IN_USE. */
class SupplierIntegrationTest extends PurchasingTestSupport {

    private static final String FULL = """
            {"name":"  Sharma Wholesale  ","contactName":"Anil Sharma","phone":"+919812345678",
             "email":"orders@sharma.in","gstin":"29ABCDE1234F1Z5","address":"12 APMC Yard, Yeshwanthpur, Bengaluru",
             "leadTimeDays":2,"notes":"Calls before delivery"}""";

    private static List<String> names(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(s -> s.get("name").asString()).toList();
    }

    @Test
    void createReturnsTheWholeViewAndGetAndListShowIt() throws Exception {
        Store store = newStore();
        Instant before = Instant.now().minusSeconds(1);
        JsonNode created = body(createSupplierRequest(store, FULL).andExpect(status().isCreated()));

        assertThat(fieldNames(created)).containsExactly("id", "name", "contactName", "phone", "email", "gstin",
                "address", "leadTimeDays", "notes", "active", "createdAt", "updatedAt");
        assertThat(created.get("name").asString()).isEqualTo("Sharma Wholesale");
        assertThat(created.get("contactName").asString()).isEqualTo("Anil Sharma");
        assertThat(created.get("phone").asString()).isEqualTo("+919812345678");
        assertThat(created.get("email").asString()).isEqualTo("orders@sharma.in");
        assertThat(created.get("gstin").asString()).isEqualTo("29ABCDE1234F1Z5");
        assertThat(created.get("leadTimeDays").asInt()).isEqualTo(2);
        assertThat(created.get("notes").asString()).isEqualTo("Calls before delivery");
        assertThat(created.get("active").asBoolean()).isTrue();
        assertThat(Instant.parse(created.get("createdAt").asString())).isAfter(before);

        String id = created.get("id").asString();
        JsonNode fetched = body(mvc.perform(get("/api/vendor/suppliers/{id}", id).with(vendor(store)))
                .andExpect(status().isOk()));
        assertThat(fetched).isEqualTo(created);
        JsonNode list = body(mvc.perform(get("/api/vendor/suppliers").with(vendor(store))).andExpect(status().isOk()));
        assertThat(list.isArray()).isTrue();
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isEqualTo(created);
    }

    @Test
    void blankOptionalFieldsAreStoredAsNull() throws Exception {
        Store store = newStore();
        JsonNode created = body(createSupplierRequest(store, """
                {"name":"Fresh Farms","contactName":"  ","phone":"","email":"","gstin":"","address":" ","notes":""}""")
                .andExpect(status().isCreated()));
        for (String field : List.of("contactName", "phone", "email", "gstin", "address", "leadTimeDays", "notes")) {
            assertThat(created.get(field).isNull()).as(field).isTrue();
        }
    }

    @Test
    void invalidBodiesAre400WithTheFields() throws Exception {
        Store store = newStore();
        createSupplierRequest(store, "{\"name\":\" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        createSupplierRequest(store, "{\"name\":\"" + "x".repeat(121) + "\"}")
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        createSupplierRequest(store, "{\"name\":\"A\",\"leadTimeDays\":91}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("leadTimeDays"));
        createSupplierRequest(store, "{\"name\":\"A\",\"leadTimeDays\":-1}")
                .andExpect(jsonPath("$.errors[0].field").value("leadTimeDays"));
        createSupplierRequest(store, "{\"name\":\"A\",\"gstin\":\"29ABCDE1234\"}")
                .andExpect(jsonPath("$.errors[0].field").value("gstin"));
        createSupplierRequest(store, "{\"name\":\"A\",\"email\":\"not-an-email\"}")
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        createSupplierRequest(store, "{\"name\":\"A\",\"phone\":\"12-34\"}")
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
        createSupplierRequest(store, "{\"name\":\"A\",\"notes\":\"" + "n".repeat(501) + "\"}")
                .andExpect(jsonPath("$.errors[0].field").value("notes"));
        createSupplierRequest(store, "{\"name\":\"A\",\"address\":\"" + "a".repeat(301) + "\"}")
                .andExpect(jsonPath("$.errors[0].field").value("address"));
        // 0 and 90 are the bounds
        createSupplierRequest(store, "{\"name\":\"A\",\"leadTimeDays\":0}").andExpect(status().isCreated());
        createSupplierRequest(store, "{\"name\":\"B\",\"leadTimeDays\":90}").andExpect(status().isCreated());
    }

    @Test
    void updateReplacesEveryFieldAndKeepsActiveWhenAbsent() throws Exception {
        Store store = newStore();
        UUID id = supplier(store, "Old Name");
        JsonNode updated = body(mvc.perform(put("/api/vendor/suppliers/{id}", id).with(vendor(store))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New Name\",\"notes\":\"Weekly\"}"))
                .andExpect(status().isOk()));
        assertThat(updated.get("name").asString()).isEqualTo("New Name");
        assertThat(updated.get("notes").asString()).isEqualTo("Weekly");
        assertThat(updated.get("leadTimeDays").isNull()).isTrue();            // replaced, not merged
        assertThat(updated.get("active").asBoolean()).isTrue();

        // active=false deactivates, active=true reactivates
        mvc.perform(put("/api/vendor/suppliers/{id}", id).with(vendor(store)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\",\"active\":false}")).andExpect(jsonPath("$.active").value(false));
        mvc.perform(put("/api/vendor/suppliers/{id}", id).with(vendor(store)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New Name\",\"active\":true}")).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void deleteDeactivatesAndListsFilterByActive() throws Exception {
        Store store = newStore();
        UUID zeta = supplier(store, "Zeta Traders");
        supplier(store, "Alpha Agro");
        UUID beta = supplier(store, "beta Mills");

        mvc.perform(delete("/api/vendor/suppliers/{id}", zeta).with(vendor(store))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/vendor/suppliers/{id}", zeta).with(vendor(store))).andExpect(status().isNoContent());
        mvc.perform(get("/api/vendor/suppliers/{id}", zeta).with(vendor(store)))
                .andExpect(jsonPath("$.active").value(false));

        // active first, then by name (case-insensitive); filters
        assertThat(names(body(mvc.perform(get("/api/vendor/suppliers").with(vendor(store))))))
                .containsExactly("Alpha Agro", "beta Mills", "Zeta Traders");
        assertThat(names(body(mvc.perform(get("/api/vendor/suppliers?active=true").with(vendor(store))))))
                .containsExactly("Alpha Agro", "beta Mills");
        assertThat(names(body(mvc.perform(get("/api/vendor/suppliers?active=false").with(vendor(store))))))
                .containsExactly("Zeta Traders");
        mvc.perform(get("/api/vendor/suppliers?active=maybe").with(vendor(store)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        // an inactive supplier cannot be named by a new purchase order
        UUID rice = product(store, "Rice");
        createPoRequest(store, poBody(zeta, null, null, List.of(poLine(rice, 5, "40.00"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SUPPLIER"))
                .andExpect(jsonPath("$.supplierId").value(zeta.toString()));
        createPoRequest(store, poBody(beta, null, null, List.of(poLine(rice, 5, "40.00"))))
                .andExpect(status().isCreated());
    }

    @Test
    void aSupplierWithOpenPurchaseOrdersCannotBeDeactivated() throws Exception {
        Store store = newStore();
        UUID supplierId = supplier(store, "Busy Supplier");
        UUID rice = product(store, "Rice");
        UUID draft = UUID.fromString(createPo(store, supplierId, List.of(poLine(rice, 10, "40.00")))
                .get("id").asString());
        UUID sent = sentPo(store, supplierId, List.of(poLine(rice, 10, "40.00")));

        mvc.perform(delete("/api/vendor/suppliers/{id}", supplierId).with(vendor(store)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUPPLIER_IN_USE"))
                .andExpect(jsonPath("$.openPurchaseOrders").value(2));
        mvc.perform(put("/api/vendor/suppliers/{id}", supplierId).with(vendor(store))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Busy Supplier\",\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SUPPLIER_IN_USE"));
        // other edits stay possible
        mvc.perform(put("/api/vendor/suppliers/{id}", supplierId).with(vendor(store))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Busy Supplier Ltd\"}"))
                .andExpect(status().isOk());

        // partially received is still open; received / closed / cancelled are not
        receiveRequest(store, sent, newKey(), receiveBody(List.of(java.util.Map.entry(rice, 4)), null))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/vendor/suppliers/{id}", supplierId).with(vendor(store)))
                .andExpect(jsonPath("$.openPurchaseOrders").value(2));
        poAction(store, sent, "close").andExpect(status().isOk());
        poAction(store, draft, "cancel").andExpect(status().isOk());
        mvc.perform(delete("/api/vendor/suppliers/{id}", supplierId).with(vendor(store)))
                .andExpect(status().isNoContent());
        assertThat(jdbc().sql("SELECT active FROM supplier WHERE id = UUID_TO_BIN(?)").param(supplierId.toString())
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void anotherVendorsSupplierIsNotFoundAndNeverListed() throws Exception {
        Store owner = newStore();
        Store other = newStore();
        UUID supplierId = supplier(owner, "Private Supplier");

        mvc.perform(get("/api/vendor/suppliers/{id}", supplierId).with(vendor(other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SUPPLIER_NOT_FOUND"));
        mvc.perform(put("/api/vendor/suppliers/{id}", supplierId).with(vendor(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Mine now\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/vendor/suppliers/{id}", supplierId).with(vendor(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/vendor/suppliers/{id}", UUID.randomUUID()).with(vendor(owner)))
                .andExpect(status().isNotFound());
        assertThat(body(mvc.perform(get("/api/vendor/suppliers").with(vendor(other))))).isEmpty();
        // and it cannot be named by the other vendor's purchase order
        UUID product = product(other, "Oil");
        createPoRequest(other, poBody(supplierId, null, null, List.of(poLine(product, 1, "100.00"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SUPPLIER"));
        mvc.perform(get("/api/vendor/suppliers/not-a-uuid").with(vendor(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    @Test
    void onlyApprovedVendorsMayUseSuppliers() throws Exception {
        mvc.perform(get("/api/vendor/suppliers").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        mvc.perform(get("/api/vendor/suppliers").with(as(UUID.randomUUID(), UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/suppliers").with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendor/suppliers")).andExpect(status().isUnauthorized());
    }
}
