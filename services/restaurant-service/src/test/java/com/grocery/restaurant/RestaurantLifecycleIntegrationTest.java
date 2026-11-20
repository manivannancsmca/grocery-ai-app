package com.grocery.restaurant;

import com.grocery.common.id.Ids;
import com.grocery.restaurant.service.RestaurantOnboardingService;
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

class RestaurantLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String OWNER = "RESTAURANT_OWNER";
    private static final String ADDRESS = """
            {"line1":"5 Church Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
             "country":"IN","latitude":12.9752,"longitude":77.6050}""";
    private static final String COMPLETE_PROFILE = """
            {"name":"Spice Route","description":"North Indian kitchen","cuisines":["NORTH_INDIAN","BIRYANI"],
             "fssaiLicense":"12345678901234","gstin":null,"contactEmail":"ops@spiceroute.in",
             "contactPhone":"+919876543210","address":%s,
             "openingHours":[{"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"}]}""".formatted(ADDRESS);

    @Autowired
    RestaurantOnboardingService onboarding;
    @Autowired
    DataSource dataSource;

    private final UUID admin = UUID.randomUUID();

    private List<String> outboxEventTypes(String restaurantId) {
        return JdbcClient.create(dataSource)
                .sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY occurred_at, id")
                .param(restaurantId).query(String.class).list();
    }

    private String createProfile(UUID owner, String body) throws Exception {
        return json.readTree(mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void registrationEventCreatesOneDraftProfileAndPublishesTheIdsIdentityNeeds() throws Exception {
        UUID owner = UUID.randomUUID();
        String eventId = Ids.newId().toString();
        onboarding.onRestaurantOwnerRegistered(eventId, owner, "Ravi Kumar", "ravi@example.com", "+919812345678");
        onboarding.onRestaurantOwnerRegistered(eventId, owner, "Ravi Kumar", "ravi@example.com", "+919812345678");

        String body = mvc.perform(get("/api/restaurants/me").with(as(owner, OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.name").value("Ravi Kumar"))
                .andExpect(jsonPath("$.contactEmail").value("ravi@example.com"))
                .andExpect(jsonPath("$.cuisines").isEmpty())
                .andExpect(jsonPath("$.openingHours").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String restaurantId = json.readTree(body).get("id").asString();
        assertThat(outboxEventTypes(restaurantId)).containsExactly("RestaurantRegistered");

        // identity-service reads exactly these field names to grant the restaurant_id claim
        JsonNode payload = json.readTree(JdbcClient.create(dataSource)
                .sql("SELECT payload FROM outbox_event WHERE aggregate_id = ?")
                .param(restaurantId).query(String.class).single());
        assertThat(payload.get("restaurantId").asString()).isEqualTo(restaurantId);
        assertThat(payload.get("ownerUserId").asString()).isEqualTo(owner.toString());
        assertThat(payload.get("name").asString()).isEqualTo("Ravi Kumar");
    }

    @Test
    void profileIsMissingUntilCreatedThenSavingCreatesItOnDemand() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(get("/api/restaurants/me").with(as(owner, OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));

        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Early Bird Cafe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.ownerUserId").value(owner.toString()))
                .andExpect(jsonPath("$.name").value("Early Bird Cafe"));

        // The late registration event must not create a second profile
        onboarding.onRestaurantOwnerRegistered(Ids.newId().toString(), owner, "X", "x@example.com", null);
        mvc.perform(get("/api/restaurants/me").with(as(owner, OWNER)))
                .andExpect(jsonPath("$.name").value("Early Bird Cafe"));
    }

    @Test
    void invalidFieldsAreRejectedAndIncompleteProfilesCannotBeSubmitted() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cuisines":["north indian","BIRYANI"],"fssaiLicense":"123",
                                 "address":{"postalCode":"12","latitude":200}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(4))
                .andExpect(jsonPath("$.errors[?(@.field == 'cuisines[0]')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'fssaiLicense')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address.postalCode')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address.latitude')]").exists());

        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cuisines\":[\"A_A\",\"B_B\",\"C_C\",\"D_D\",\"E_E\",\"F_F\",\"G_G\",\"H_H\",\"I_I\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'cuisines')]").exists());

        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Half Done\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").doesNotExist())
                .andExpect(jsonPath("$.errors[?(@.field == 'cuisines')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'fssaiLicense')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'address')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'openingHours')]").exists());
    }

    @Test
    void openingHoursAreValidatedReplacedAndReturnedMondayFirst() throws Exception {
        UUID owner = UUID.randomUUID();
        // [1] closes when it opens, [2] repeats MONDAY, [3] has no valid field at all
        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"openingHours":[
                                  {"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"},
                                  {"dayOfWeek":"TUESDAY","opensAt":"10:00","closesAt":"10:00"},
                                  {"dayOfWeek":"MONDAY","opensAt":"12:00","closesAt":"14:00"},
                                  {"dayOfWeek":"FUNDAY","opensAt":"9am","closesAt":"24:00"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(5))
                .andExpect(jsonPath("$.errors[?(@.field == 'openingHours[1].closesAt')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'openingHours[2].dayOfWeek')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'openingHours[3].dayOfWeek')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'openingHours[3].opensAt')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'openingHours[3].closesAt')]").exists());

        // Overnight hours are fine; days come back Monday first; cuisines keep the owner's order, without duplicates
        String overnight = COMPLETE_PROFILE
                .replace("[\"NORTH_INDIAN\",\"BIRYANI\"]", "[\"BIRYANI\",\"NORTH_INDIAN\",\"BIRYANI\"]")
                .replace("[{\"dayOfWeek\":\"MONDAY\",\"opensAt\":\"09:00\",\"closesAt\":\"23:00\"}]", """
                        [{"dayOfWeek":"SUNDAY","opensAt":"18:00","closesAt":"02:00"},
                         {"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"}]""");
        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(overnight))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuisines.length()").value(2))
                .andExpect(jsonPath("$.cuisines[0]").value("BIRYANI"))
                .andExpect(jsonPath("$.cuisines[1]").value("NORTH_INDIAN"))
                .andExpect(jsonPath("$.openingHours.length()").value(2))
                .andExpect(jsonPath("$.openingHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.openingHours[1].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("$.openingHours[1].opensAt").value("18:00"))
                .andExpect(jsonPath("$.openingHours[1].closesAt").value("02:00"));

        // Saving again replaces both collections (same day with new times, a day dropped, a day added)
        String changed = COMPLETE_PROFILE
                .replace("[\"NORTH_INDIAN\",\"BIRYANI\"]", "[\"SOUTH_INDIAN\"]")
                .replace("[{\"dayOfWeek\":\"MONDAY\",\"opensAt\":\"09:00\",\"closesAt\":\"23:00\"}]", """
                        [{"dayOfWeek":"FRIDAY","opensAt":"11:30","closesAt":"23:45"},
                         {"dayOfWeek":"MONDAY","opensAt":"10:00","closesAt":"22:00"}]""");
        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                .contentType(MediaType.APPLICATION_JSON).content(changed)).andExpect(status().isOk());
        mvc.perform(get("/api/restaurants/me").with(as(owner, OWNER)))
                .andExpect(jsonPath("$.cuisines.length()").value(1))
                .andExpect(jsonPath("$.cuisines[0]").value("SOUTH_INDIAN"))
                .andExpect(jsonPath("$.openingHours.length()").value(2))
                .andExpect(jsonPath("$.openingHours[0].dayOfWeek").value("MONDAY"))
                .andExpect(jsonPath("$.openingHours[0].opensAt").value("10:00"))
                .andExpect(jsonPath("$.openingHours[0].closesAt").value("22:00"))
                .andExpect(jsonPath("$.openingHours[1].dayOfWeek").value("FRIDAY"))
                .andExpect(jsonPath("$.openingHours[1].closesAt").value("23:45"));

        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void fullApprovalLifecycleWithHistoryAndEvents() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner, COMPLETE_PROFILE);

        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty());

        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PROFILE_LOCKED"));

        mvc.perform(get("/api/admin/restaurants").param("status", "PENDING_APPROVAL").param("query", "spice route")
                        .with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(restaurantId)).exists());

        mvc.perform(post("/api/admin/restaurants/{id}/reject", restaurantId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/restaurants/{id}/reject", restaurantId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"FSSAI licence has expired\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("FSSAI licence has expired"));

        // Owner fixes and resubmits; admin approves, suspends, reinstates
        mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE)).andExpect(status().isOk());
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/restaurants/{id}/approve", restaurantId).with(as(admin, "SUPER_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
        mvc.perform(post("/api/admin/restaurants/{id}/suspend", restaurantId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Hygiene complaint under review\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        mvc.perform(post("/api/admin/restaurants/{id}/reinstate", restaurantId).with(as(admin, "OPS_ADMIN")))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.statusReason").isEmpty());

        mvc.perform(get("/api/admin/restaurants/{id}/history", restaurantId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$[0].fromStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$[0].changedBy").value(admin.toString()));

        List<String> events = outboxEventTypes(restaurantId);
        assertThat(events).filteredOn(type -> !type.equals("RestaurantListingUpdated"))
                .containsExactly("RestaurantRegistered", "RestaurantSubmitted",
                        "RestaurantRejected", "RestaurantSubmitted", "RestaurantApproved", "RestaurantSuspended",
                        "RestaurantReinstated");
        // Phase 3: approve, suspend and reinstate also publish the public listing (search index)
        assertThat(events).filteredOn("RestaurantListingUpdated"::equals).hasSize(3);
    }

    @Test
    void transitionsAndPermissionsAreEnforced() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner, COMPLETE_PROFILE);

        mvc.perform(post("/api/admin/restaurants/{id}/approve", restaurantId).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));
        mvc.perform(post("/api/admin/restaurants/{id}/reinstate", restaurantId).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));

        // SUPPORT (any admin role) can look but not change
        mvc.perform(get("/api/admin/restaurants").with(as(admin, "SUPPORT"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/restaurants/{id}", restaurantId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Spice Route"));
        mvc.perform(get("/api/admin/restaurants/{id}/history", restaurantId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/restaurants/{id}/approve", restaurantId).with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(post("/api/admin/restaurants/{id}/reject", restaurantId).with(as(admin, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/restaurants/{id}/suspend", restaurantId).with(as(admin, "SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/restaurants/{id}/reinstate", restaurantId).with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden());
        // Delivery managers run the fleet, not restaurant approvals
        mvc.perform(post("/api/admin/restaurants/{id}/approve", restaurantId).with(as(admin, "DELIVERY_MANAGER")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/admin/restaurants").with(as(owner, OWNER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/restaurants/me").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/restaurants/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/admin/restaurants"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/restaurants").param("status", "NOPE").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/admin/restaurants/{id}", UUID.randomUUID()).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));
    }
}
