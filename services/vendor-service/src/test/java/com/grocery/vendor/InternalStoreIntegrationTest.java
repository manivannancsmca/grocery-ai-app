package com.grocery.vendor;

import com.grocery.common.id.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /internal/vendors/{vendorId}/store (docs/api/phase-5-cart-and-checkout-preview.md §4). */
class InternalStoreIntegrationTest extends AbstractIntegrationTest {

    private static final String PROFILE = """
            {"businessName":"Green Basket","legalName":"Green Basket Pvt Ltd","gstin":"29ABCDE1234F1Z5",
             "contactEmail":"ops@greenbasket.in","contactPhone":"+919876543210",
             "address":{"line1":"7 Church Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                        "country":"IN","latitude":12.9784,"longitude":77.6408}}""";

    private final UUID admin = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();

    private String saveProfile(UUID owner, String profile) throws Exception {
        return json.readTree(mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private void adminAction(String vendorId, String action, String reason) throws Exception {
        var request = post("/api/admin/vendors/{id}/" + action, vendorId).with(as(admin, "OPS_ADMIN"));
        if (reason != null) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}");
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions store(Object vendorId, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get("/internal/vendors/{id}/store", vendorId).with(caller));
    }

    private ResultActions store(Object vendorId) throws Exception {
        return store(vendorId, as(customer, "CUSTOMER"));
    }

    @Test
    void anApprovedVendorIsAnActiveStoreWithItsLocation() throws Exception {
        UUID owner = UUID.randomUUID();
        String vendorId = saveProfile(owner, PROFILE);
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR"))).andExpect(status().isOk());
        adminAction(vendorId, "approve", null);

        store(vendorId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorId").value(vendorId))
                .andExpect(jsonPath("$.name").value("Green Basket"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.city").value("Bengaluru"))
                .andExpect(jsonPath("$.latitude").value(12.9784))
                .andExpect(jsonPath("$.longitude").value(77.6408));

        // Suspended: still answered (the cart shows STORE_UNAVAILABLE), but not active
        adminAction(vendorId, "suspend", "Repeated late deliveries");
        store(vendorId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.name").value("Green Basket"))
                .andExpect(jsonPath("$.city").value("Bengaluru"));

        adminAction(vendorId, "reinstate", null);
        store(vendorId).andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void aVendorNotYetApprovedIsInactiveAndMayHaveNoLocationYet() throws Exception {
        UUID owner = UUID.randomUUID();
        String vendorId = saveProfile(owner, "{\"businessName\":\"Corner Shop\"}");

        JsonNode draft = json.readTree(store(vendorId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Corner Shop"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.active").value(false))
                .andReturn().getResponse().getContentAsString());
        assertThat(draft.get("city").isNull()).isTrue();
        assertThat(draft.get("latitude").isNull()).isTrue();
        assertThat(draft.get("longitude").isNull()).isTrue();

        saveProfile(owner, PROFILE);
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR"))).andExpect(status().isOk());
        store(vendorId)
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.city").value("Bengaluru"));

        adminAction(vendorId, "reject", "Blurry documents");
        store(vendorId).andExpect(jsonPath("$.status").value("REJECTED")).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void anUnknownVendorIsNotFound() throws Exception {
        store(Ids.newId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_FOUND"));
    }

    @Test
    void anyAuthenticatedCallerMayReadAStoreButAnonymousCallsAreRejected() throws Exception {
        String vendorId = saveProfile(UUID.randomUUID(), PROFILE);

        // Zero trust: /internal/** still needs a valid JWT (and an anonymous caller learns nothing about existence)
        mvc.perform(get("/internal/vendors/{id}/store", vendorId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/internal/vendors/{id}/store", Ids.newId()))
                .andExpect(status().isUnauthorized());

        // No role restriction: the customer's forwarded token, any vendor, an admin
        store(vendorId).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Green Basket"));
        store(vendorId, as(UUID.randomUUID(), "VENDOR")).andExpect(status().isOk());
        store(vendorId, as(admin, "SUPPORT")).andExpect(status().isOk());
    }
}
