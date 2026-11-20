package com.grocery.customer;

import com.grocery.common.id.Ids;
import com.grocery.customer.event.IdentityEventsListener;
import com.grocery.customer.service.CustomerProfileService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerProfileIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    IdentityEventsListener listener;
    @Autowired
    CustomerProfileService profiles;
    @Autowired
    DataSource dataSource;

    private final UUID admin = UUID.randomUUID();

    /** An identity.events record exactly as Debezium's outbox router delivers it. */
    private static ConsumerRecord<String, String> identityEvent(String eventId, String eventType, UUID userId,
                                                               String payload) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("identity.events", 0, 0L, userId.toString(), payload);
        record.headers().add("eventId", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static String userRegistered(UUID userId, String fullName, String email, String role) {
        return """
                {"userId":"%s","email":"%s","fullName":"%s","phone":"+919812345678","role":"%s",
                 "registeredAt":"2026-11-11T10:15:30.123Z"}""".formatted(userId, email, fullName, role);
    }

    private int processedCount(String eventId) {
        return JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM processed_event WHERE event_id = ? AND consumer = ?")
                .params(eventId, CustomerProfileService.IDENTITY_CONSUMER).query(Integer.class).single();
    }

    @Test
    void registrationEventCreatesTheProfileOnce() throws Exception {
        UUID user = UUID.randomUUID();
        String eventId = Ids.newId().toString();
        String payload = userRegistered(user, "Meera Iyer", "meera@example.com", "CUSTOMER");
        listener.on(identityEvent(eventId, "UserRegistered", user, payload));
        listener.on(identityEvent(eventId, "UserRegistered", user, payload)); // redelivery: ignored

        mvc.perform(get("/api/customers/me").with(as(user, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.toString()))
                .andExpect(jsonPath("$.email").value("meera@example.com"))
                .andExpect(jsonPath("$.fullName").value("Meera Iyer"))
                .andExpect(jsonPath("$.phone").value("+919812345678"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        assertThat(processedCount(eventId)).isEqualTo(1);

        // A second registration event for the same user (new eventId) must not overwrite the profile
        listener.on(identityEvent(Ids.newId().toString(), "UserRegistered", user,
                userRegistered(user, "Someone Else", "else@example.com", "CUSTOMER")));
        mvc.perform(get("/api/customers/me").with(as(user, "CUSTOMER")))
                .andExpect(jsonPath("$.fullName").value("Meera Iyer"));
    }

    @Test
    void otherRolesAndOtherEventTypesAreIgnored() throws Exception {
        UUID vendorUser = UUID.randomUUID();
        listener.on(identityEvent(Ids.newId().toString(), "UserRegistered", vendorUser,
                userRegistered(vendorUser, "Asha Rao", "asha@example.com", "VENDOR")));
        UUID suspended = UUID.randomUUID();
        listener.on(identityEvent(Ids.newId().toString(), "UserSuspended", suspended,
                "{\"userId\":\"%s\",\"reason\":\"fraud\"}".formatted(suspended)));

        mvc.perform(get("/api/customers/me").with(as(vendorUser, "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/customers/me").with(as(suspended, "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void profileIsMissingUntilCreatedThenSavingCreatesItOnDemand() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(get("/api/customers/me").with(as(user, "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_PROFILE_NOT_FOUND"));

        mvc.perform(put("/api/customers/me").with(as(user, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Early Bird\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.toString()))
                .andExpect(jsonPath("$.email").value(emailOf(user)))
                .andExpect(jsonPath("$.fullName").value("Early Bird"))
                .andExpect(jsonPath("$.phone").isEmpty());

        // The late registration event is a no-op
        profiles.onCustomerUserRegistered(Ids.newId().toString(), user, "X", "x@example.com", null);
        mvc.perform(get("/api/customers/me").with(as(user, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Early Bird"))
                .andExpect(jsonPath("$.email").value(emailOf(user)));
    }

    @Test
    void customerUpdatesTheirProfile() throws Exception {
        UUID user = UUID.randomUUID();
        profiles.onCustomerUserRegistered(Ids.newId().toString(), user, "Ravi Kumar", "ravi@example.com", null);

        mvc.perform(put("/api/customers/me").with(as(user, "CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"  Ravi K. Kumar \",\"phone\":\"+919900112233\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ravi K. Kumar"))
                .andExpect(jsonPath("$.phone").value("+919900112233"))
                .andExpect(jsonPath("$.email").value("ravi@example.com"));
        mvc.perform(get("/api/customers/me").with(as(user, "CUSTOMER")))
                .andExpect(jsonPath("$.fullName").value("Ravi K. Kumar"))
                .andExpect(jsonPath("$.phone").value("+919900112233"));

        // PUT replaces: omitting the phone clears it
        mvc.perform(put("/api/customers/me").with(as(user, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Ravi Kumar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").isEmpty());
    }

    @Test
    void invalidProfileIsRejectedWithFieldErrors() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(put("/api/customers/me").with(as(user, "CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\" \",\"phone\":\"12-34\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("fullName", "phone")));

        // Nothing was created by the rejected request
        mvc.perform(get("/api/customers/me").with(as(user, "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerEndpointsRequireTheCustomerRole() throws Exception {
        UUID vendor = UUID.randomUUID();
        mvc.perform(get("/api/customers/me").with(as(vendor, "VENDOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(put("/api/customers/me").with(as(vendor, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Not A Customer\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/me/addresses").with(as(vendor, "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/customers/me/addresses").with(as(vendor, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"label":"Home","recipientName":"Asha Rao","phone":"+919876543210","line1":"12 MG Road",
                                 "city":"Bengaluru","state":"Karnataka","postalCode":"560001"}"""))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/customers/me/addresses/{id}/default", UUID.randomUUID()).with(as(vendor, "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/me").with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/customers/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/customers/me/addresses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    @Test
    void anyAdminCanSearchCustomersNewestFirst() throws Exception {
        String marker = "zq" + UUID.randomUUID().toString().substring(0, 6);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        profiles.onCustomerUserRegistered(Ids.newId().toString(), first, "Anil " + marker, "anil@example.com", null);
        profiles.onCustomerUserRegistered(Ids.newId().toString(), second, "Bina Shah", marker + "@example.com", null);
        profiles.onCustomerUserRegistered(Ids.newId().toString(), third, "Chitra " + marker, "chitra@example.com", null);

        mvc.perform(get("/api/admin/customers").param("query", marker.toUpperCase()).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].id",
                        contains(third.toString(), second.toString(), first.toString())));

        mvc.perform(get("/api/admin/customers").param("query", marker).param("page", "1").param("size", "2")
                        .with(as(admin, "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[*].id", contains(first.toString())));

        for (String role : new String[]{"SUPER_ADMIN", "OPS_ADMIN", "DELIVERY_MANAGER"}) {
            mvc.perform(get("/api/admin/customers").with(as(admin, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray());
        }
        mvc.perform(get("/api/admin/customers").with(as(first, "CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
        mvc.perform(get("/api/admin/customers").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/customers"))
                .andExpect(status().isUnauthorized());
    }
}
