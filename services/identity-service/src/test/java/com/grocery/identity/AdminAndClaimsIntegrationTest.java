package com.grocery.identity;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.identity.security.TokenRevocationService;
import com.grocery.identity.service.BusinessMembershipService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAndClaimsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StringRedisTemplate redis;
    @Autowired
    BusinessMembershipService businessMemberships;
    @Autowired
    DataSource dataSource;

    private String adminToken() throws Exception {
        MockHttpServletResponse response = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(response.getStatus()).as("bootstrap admin exists").isEqualTo(200);
        return accessToken(response);
    }

    private void deliver(String eventType, UUID vendorId, UUID ownerUserId) {
        String payload = "{\"vendorId\":\"%s\",\"ownerUserId\":\"%s\",\"businessName\":\"Fresh\"}"
                .formatted(vendorId, ownerUserId);
        businessMemberships.handle(new EventEnvelope(Ids.newId().toString(), eventType, 1, "Vendor",
                vendorId.toString(), "corr-test-claims", "vendor.events", payload));
    }

    @Test
    void adminSearchesSuspendsAndReactivatesAUser() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail("victim");
        MockHttpServletResponse registered = register(email, "CUSTOMER");
        String userId = body(registered).at("/user/id").asString();
        String oldRefresh = refreshCookie(registered);

        mvc.perform(get("/api/admin/users").param("query", email).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(email));

        mvc.perform(post("/api/admin/users/{id}/suspend", userId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"fraud check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        assertThat(redis.hasKey(TokenRevocationService.KEY_PREFIX + userId))
                .as("gateway marker: existing access tokens die now").isTrue();
        MockHttpServletResponse blockedLogin = login(email, PASSWORD);
        assertThat(blockedLogin.getStatus()).isEqualTo(403);
        assertThat(body(blockedLogin).get("errorCode").asString()).isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(mvc.perform(post("/api/auth/refresh").cookie(new Cookie("grocery_rt", oldRefresh))
                .header("X-Requested-With", "XMLHttpRequest")).andReturn().getResponse().getStatus())
                .as("refresh tokens were revoked").isEqualTo(401);
        Long suspendedEvents = JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM outbox_event WHERE event_type='UserSuspended' AND aggregate_id=?")
                .param(userId).query(Long.class).single();
        assertThat(suspendedEvents).isEqualTo(1);

        mvc.perform(post("/api/admin/users/{id}/reactivate", userId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
    }

    @Test
    void nonAdminsCannotUseAdminApisAndAdminsCannotSuspendThemselves() throws Exception {
        String customer = accessToken(register(uniqueEmail("cust"), "CUSTOMER"));
        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

        String admin = adminToken();
        String adminId = claims(admin).get("sub").asString();
        mvc.perform(post("/api/admin/users/{id}/suspend", adminId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"oops\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("CANNOT_SUSPEND_SELF"));
    }

    @Test
    void superAdminCreatesOpsAdminWhoCannotCreateAdmins() throws Exception {
        String email = uniqueEmail("ops");
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"email":"%s","fullName":"Ops Person","password":"OpsPass123","roles":["OPS_ADMIN"]}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles[0]").value("OPS_ADMIN"));

        String ops = accessToken(login(email, "OpsPass123"));
        mvc.perform(post("/api/admin/users").header("Authorization", "Bearer " + ops)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"email":"%s","fullName":"X","password":"OpsPass123","roles":["SUPPORT"]}
                                """.formatted(uniqueEmail("x"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvedVendorGetsVendorIdClaimAndLosesItWhenSuspended() throws Exception {
        String email = uniqueEmail("shop");
        UUID ownerId = UUID.fromString(body(register(email, "VENDOR")).at("/user/id").asString());
        UUID vendorId = Ids.newId();

        deliver("VendorRegistered", vendorId, ownerId);
        assertThat(claims(accessToken(login(email, PASSWORD))).has("vendor_id")).isFalse();

        deliver("VendorApproved", vendorId, ownerId);
        assertThat(redis.hasKey(TokenRevocationService.KEY_PREFIX + ownerId))
                .as("old tokens revoked so the client refreshes into the new claim").isTrue();
        JsonNode approved = claims(accessToken(login(email, PASSWORD)));
        assertThat(approved.get("vendor_id").asString()).isEqualTo(vendorId.toString());

        String token = accessToken(login(email, PASSWORD));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.businesses[0].status").value("ACTIVE"));

        deliver("VendorSuspended", vendorId, ownerId);
        assertThat(claims(accessToken(login(email, PASSWORD))).has("vendor_id")).isFalse();
    }

    @Test
    void duplicateEventsAreIgnored() throws Exception {
        String email = uniqueEmail("dupevt");
        UUID ownerId = UUID.fromString(body(register(email, "VENDOR")).at("/user/id").asString());
        UUID vendorId = Ids.newId();
        String eventId = Ids.newId().toString();
        String payload = "{\"vendorId\":\"%s\",\"ownerUserId\":\"%s\"}".formatted(vendorId, ownerId);
        EventEnvelope approved = new EventEnvelope(eventId, "VendorApproved", 1, "Vendor", vendorId.toString(),
                null, "vendor.events", payload);

        businessMemberships.handle(approved);
        deliver("VendorSuspended", vendorId, ownerId);
        businessMemberships.handle(approved);   // redelivery of an old event must not re-approve

        assertThat(claims(accessToken(login(email, PASSWORD))).has("vendor_id")).isFalse();
    }
}
