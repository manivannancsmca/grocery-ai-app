package com.grocery.delivery;

import com.grocery.common.id.Ids;
import com.grocery.delivery.partner.service.PartnerOnboardingService;
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

class PartnerLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String PARTNER = "DELIVERY_PARTNER";
    // Riders may type numbers with spaces/hyphens and in lower case; they are stored normalised
    private static final String COMPLETE_PROFILE = """
            {"fullName":"Arjun Singh","phone":"+919811122233","city":"Bengaluru","vehicleType":"MOTORCYCLE",
             "vehicleNumber":"ka-01-ab 1234","drivingLicenseNumber":"KA01 2020-1234567"}""";
    private static final String BICYCLE_PROFILE = """
            {"fullName":"Meena Pedals","phone":"+919800000001","city":"Mysuru","vehicleType":"BICYCLE"}""";

    @Autowired
    PartnerOnboardingService onboarding;
    @Autowired
    DataSource dataSource;

    private final UUID admin = UUID.randomUUID();

    private List<String> outboxEventTypes(String partnerId) {
        return JdbcClient.create(dataSource)
                .sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY occurred_at, id")
                .param(partnerId).query(String.class).list();
    }

    private void save(UUID partner, String body) throws Exception {
        mvc.perform(put("/api/delivery/partners/me").with(as(partner, PARTNER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void registrationEventCreatesOneDraftProfileKeyedByTheUserId() throws Exception {
        UUID user = UUID.randomUUID();
        String eventId = Ids.newId().toString();
        onboarding.onDeliveryPartnerRegistered(eventId, user, "Arjun Singh", "arjun@example.com", "+919811122233");
        onboarding.onDeliveryPartnerRegistered(eventId, user, "Arjun Singh", "arjun@example.com", "+919811122233");

        mvc.perform(get("/api/delivery/partners/me").with(as(user, PARTNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.fullName").value("Arjun Singh"))
                .andExpect(jsonPath("$.email").value("arjun@example.com"))
                .andExpect(jsonPath("$.phone").value("+919811122233"));
        assertThat(outboxEventTypes(user.toString())).containsExactly("DeliveryPartnerRegistered");

        JsonNode payload = json.readTree(JdbcClient.create(dataSource)
                .sql("SELECT payload FROM outbox_event WHERE aggregate_id = ?")
                .param(user.toString()).query(String.class).single());
        assertThat(payload.get("partnerId").asString()).isEqualTo(user.toString());
        assertThat(payload.get("fullName").asString()).isEqualTo("Arjun Singh");
    }

    @Test
    void profileIsMissingUntilCreatedThenSavingCreatesItOnDemand() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/api/delivery/partners/me").with(as(user, PARTNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PARTNER_NOT_FOUND"));
        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PARTNER_NOT_FOUND"));

        mvc.perform(put("/api/delivery/partners/me").with(as(user, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Early Rider\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.toString()))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.fullName").value("Early Rider"))
                .andExpect(jsonPath("$.email").value("user-" + user.toString().substring(0, 8) + "@example.com"));

        // The late registration event must not create a second profile
        onboarding.onDeliveryPartnerRegistered(Ids.newId().toString(), user, "X", "x@example.com", null);
        mvc.perform(get("/api/delivery/partners/me").with(as(user, PARTNER)))
                .andExpect(jsonPath("$.fullName").value("Early Rider"));
    }

    @Test
    void vehicleAndLicenceNumbersAreValidatedAndStoredNormalised() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(put("/api/delivery/partners/me").with(as(user, PARTNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"12","vehicleType":"TRUCK","vehicleNumber":"12345",
                                 "drivingLicenseNumber":"AB12"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[?(@.field == 'phone')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'vehicleType')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'vehicleNumber')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'drivingLicenseNumber')]").exists());

        // Four series letters, five final digits, a licence over 20 characters, symbols other than space/hyphen
        mvc.perform(put("/api/delivery/partners/me").with(as(user, PARTNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleNumber\":\"KA 01 ABCD 1234\",\"drivingLicenseNumber\":\"KA01-2020-1234567-890123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(put("/api/delivery/partners/me").with(as(user, PARTNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleNumber\":\"KA01AB12345\",\"drivingLicenseNumber\":\"KA01/2020/1234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));

        mvc.perform(put("/api/delivery/partners/me").with(as(user, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleType").value("MOTORCYCLE"))
                .andExpect(jsonPath("$.vehicleNumber").value("KA01AB1234"))
                .andExpect(jsonPath("$.drivingLicenseNumber").value("KA0120201234567"));
        // Shorter registration formats are valid too (one-digit district, one or no series letters)
        save(user, COMPLETE_PROFILE.replace("ka-01-ab 1234", "DL3C 1234"));
        save(user, COMPLETE_PROFILE.replace("ka-01-ab 1234", "MH121234"));
    }

    @Test
    void incompleteProfilesCannotBeSubmittedButBicyclesNeedNoVehicleNumberOrLicence() throws Exception {
        UUID user = UUID.randomUUID();
        save(user, "{\"fullName\":\"Half Done\"}");
        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.errors.length()").value(5))
                .andExpect(jsonPath("$.errors[?(@.field == 'phone')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'city')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'vehicleType')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'vehicleNumber')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'drivingLicenseNumber')]").exists());

        // A motor vehicle needs both numbers
        save(user, BICYCLE_PROFILE.replace("BICYCLE", "SCOOTER"));
        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'vehicleNumber')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'drivingLicenseNumber')]").exists());

        // A bicycle needs neither
        save(user, BICYCLE_PROFILE);
        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.vehicleType").value("BICYCLE"))
                .andExpect(jsonPath("$.vehicleNumber").isEmpty())
                .andExpect(jsonPath("$.submittedAt").isNotEmpty());
    }

    @Test
    void fullApprovalLifecycleRunByADeliveryManagerWithHistoryAndEvents() throws Exception {
        UUID user = UUID.randomUUID();
        String partnerId = user.toString();
        save(user, COMPLETE_PROFILE);

        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        mvc.perform(put("/api/delivery/partners/me").with(as(user, PARTNER))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROFILE_LOCKED"));

        mvc.perform(get("/api/admin/delivery-partners").param("status", "PENDING_APPROVAL").param("query", "arjun")
                        .with(as(admin, "DELIVERY_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(partnerId)).exists());

        mvc.perform(post("/api/admin/delivery-partners/{id}/reject", partnerId).with(as(admin, "DELIVERY_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/delivery-partners/{id}/reject", partnerId).with(as(admin, "DELIVERY_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Licence photo is unreadable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("Licence photo is unreadable"));

        // Rider fixes and resubmits; the delivery manager approves and suspends, ops reinstates
        save(user, COMPLETE_PROFILE);
        mvc.perform(post("/api/delivery/partners/me/submit").with(as(user, PARTNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/delivery-partners/{id}/approve", partnerId).with(as(admin, "DELIVERY_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
        mvc.perform(post("/api/admin/delivery-partners/{id}/suspend", partnerId).with(as(admin, "DELIVERY_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Three no-shows this week\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/delivery-partners/{id}/reinstate", partnerId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.statusReason").isEmpty());

        mvc.perform(get("/api/admin/delivery-partners/{id}/history", partnerId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].fromStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$[0].changedBy").value(admin.toString()));

        assertThat(outboxEventTypes(partnerId)).containsExactly("DeliveryPartnerRegistered",
                "DeliveryPartnerSubmitted", "DeliveryPartnerRejected", "DeliveryPartnerSubmitted",
                "DeliveryPartnerApproved", "DeliveryPartnerSuspended", "DeliveryPartnerReinstated");
    }

    @Test
    void transitionsAndPermissionsAreEnforced() throws Exception {
        UUID user = UUID.randomUUID();
        String partnerId = user.toString();
        save(user, COMPLETE_PROFILE);

        mvc.perform(post("/api/admin/delivery-partners/{id}/approve", partnerId).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));
        mvc.perform(post("/api/admin/delivery-partners/{id}/suspend", partnerId).with(as(admin, "DELIVERY_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Too early\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));

        // SUPPORT (any admin role) can look but not change; neither can FINANCE_ADMIN
        mvc.perform(get("/api/admin/delivery-partners").with(as(admin, "SUPPORT"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/delivery-partners/{id}", partnerId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Arjun Singh"));
        mvc.perform(get("/api/admin/delivery-partners/{id}/history", partnerId).with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/delivery-partners/{id}/approve", partnerId).with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/delivery-partners/{id}/reject", partnerId).with(as(admin, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/delivery-partners/{id}/suspend", partnerId).with(as(admin, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/delivery-partners/{id}/reinstate", partnerId).with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/delivery-partners/{id}/approve", partnerId).with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/delivery-partners").with(as(user, PARTNER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/delivery/partners/me").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/delivery/partners/me").with(as(UUID.randomUUID(), "DELIVERY_MANAGER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/delivery/partners/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/admin/delivery-partners"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/delivery-partners").param("status", "NOPE").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/delivery-partners/{id}", UUID.randomUUID()).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PARTNER_NOT_FOUND"));
    }
}
