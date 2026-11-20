package com.grocery.vendor;

import com.grocery.common.id.Ids;
import com.grocery.vendor.service.VendorOnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VendorLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String COMPLETE_PROFILE = """
            {"businessName":"FreshMart","legalName":"FreshMart Pvt Ltd","gstin":"29ABCDE1234F1Z5",
             "fssaiLicense":"12345678901234","contactEmail":"ops@freshmart.in","contactPhone":"+919876543210",
             "address":{"line1":"12 MG Road","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                        "country":"IN","latitude":12.9716,"longitude":77.5946}}""";

    @Autowired
    VendorOnboardingService onboarding;
    @Autowired
    DataSource dataSource;

    private final UUID admin = UUID.randomUUID();

    private List<String> outboxEventTypes(String vendorId) {
        return JdbcClient.create(dataSource)
                .sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY occurred_at, id")
                .param(vendorId).query(String.class).list();
    }

    @Test
    void registrationEventCreatesOneDraftProfile() throws Exception {
        UUID owner = UUID.randomUUID();
        String eventId = Ids.newId().toString();
        onboarding.onVendorUserRegistered(eventId, owner, "Asha Rao", "asha@example.com", "+919876543210");
        onboarding.onVendorUserRegistered(eventId, owner, "Asha Rao", "asha@example.com", "+919876543210");

        String body = mvc.perform(get("/api/vendors/me").with(as(owner, "VENDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.businessName").value("Asha Rao"))
                .andExpect(jsonPath("$.contactEmail").value("asha@example.com"))
                .andReturn().getResponse().getContentAsString();
        String vendorId = json.readTree(body).get("id").asString();
        assertThat(outboxEventTypes(vendorId)).containsExactly("VendorRegistered");
    }

    @Test
    void profileIsMissingUntilCreatedThenSavingCreatesItOnDemand() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(get("/api/vendors/me").with(as(owner, "VENDOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_FOUND"));

        mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"businessName\":\"Early Bird\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.businessName").value("Early Bird"));

        // The late registration event must not create a second profile
        onboarding.onVendorUserRegistered(Ids.newId().toString(), owner, "X", "x@example.com", null);
        mvc.perform(get("/api/vendors/me").with(as(owner, "VENDOR")))
                .andExpect(jsonPath("$.businessName").value("Early Bird"));
    }

    @Test
    void invalidFieldsAreRejectedAndIncompleteProfilesCannotBeSubmitted() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gstin\":\"NOT-A-GSTIN\",\"address\":{\"postalCode\":\"12\",\"latitude\":200}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3));

        mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"businessName\":\"Half Done\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.errors[?(@.field == 'gstin')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address')]").exists());
    }

    @Test
    void fullApprovalLifecycleWithHistoryAndEvents() throws Exception {
        UUID owner = UUID.randomUUID();
        JsonNode saved = json.readTree(mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String vendorId = saved.get("id").asString();

        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty());

        mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROFILE_LOCKED"));

        mvc.perform(get("/api/admin/vendors").param("status", "PENDING_APPROVAL").param("query", "freshmart")
                        .with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(vendorId)).exists());

        mvc.perform(post("/api/admin/vendors/{id}/reject", vendorId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/vendors/{id}/reject", vendorId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"GSTIN does not match legal name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("GSTIN does not match legal name"));

        // Vendor fixes and resubmits; admin approves, suspends, reinstates
        mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE)).andExpect(status().isOk());
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR"))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/vendors/{id}/approve", vendorId).with(as(admin, "SUPER_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
        mvc.perform(post("/api/admin/vendors/{id}/suspend", vendorId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Repeated late deliveries\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/vendors/{id}/reinstate", vendorId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.statusReason").isEmpty());

        mvc.perform(get("/api/admin/vendors/{id}/history", vendorId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].changedBy").value(admin.toString()));

        assertThat(outboxEventTypes(vendorId)).containsExactly("VendorRegistered", "VendorSubmitted",
                "VendorRejected", "VendorSubmitted", "VendorApproved", "VendorSuspended", "VendorReinstated");
    }

    @Test
    void transitionsAndPermissionsAreEnforced() throws Exception {
        UUID owner = UUID.randomUUID();
        String vendorId = json.readTree(mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andReturn().getResponse().getContentAsString()).get("id").asString();

        mvc.perform(post("/api/admin/vendors/{id}/approve", vendorId).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));

        mvc.perform(post("/api/admin/vendors/{id}/approve", vendorId).with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/vendors").with(as(owner, "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendors/me").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/vendors/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/admin/vendors").param("status", "NOPE").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/vendors/{id}", UUID.randomUUID()).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isNotFound());
    }
}
