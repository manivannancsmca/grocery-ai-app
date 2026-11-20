package com.grocery.identity;

import com.grocery.common.id.Ids;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.identity.security.TokenRevocationService;
import com.grocery.identity.service.CustomerProfileSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** customer.events CustomerProfileUpdated → the account's name and phone (header, JWT name claim, admin list). */
class CustomerProfileSyncIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CustomerProfileSyncService profiles;
    @Autowired
    StringRedisTemplate redis;

    private static EventEnvelope profileUpdated(String eventId, String customerId, String fullName, String phone,
                                                Instant updatedAt) {
        String payload = """
                {"customerId":"%s","fullName":"%s","phone":%s,"updatedAt":"%s"}"""
                .formatted(customerId, fullName, phone == null ? "null" : "\"" + phone + "\"", updatedAt);
        return new EventEnvelope(eventId, "CustomerProfileUpdated", 1, "Customer", customerId, null,
                "customer.events", payload);
    }

    private JsonNode me(String email) throws Exception {
        String token = accessToken(login(email, PASSWORD));
        return json.readTree(mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void aNewProfileNameReachesTheAccountTheTokenAndTheAdminList() throws Exception {
        String email = uniqueEmail("rename");
        MockHttpServletResponse registered = register(email, "CUSTOMER");
        String userId = body(registered).at("/user/id").asString();
        Instant savedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        profiles.handle(profileUpdated(Ids.newId().toString(), userId, "Kavya Sriram", "+919840066001", savedAt));

        assertThat(redis.hasKey(TokenRevocationService.KEY_PREFIX + userId))
                .as("old tokens carry the old name: the client refreshes into the new one").isTrue();
        MockHttpServletResponse signedIn = login(email, PASSWORD);
        assertThat(claims(accessToken(signedIn)).get("name").asString()).isEqualTo("Kavya Sriram");
        assertThat(body(signedIn).at("/user/fullName").asString()).isEqualTo("Kavya Sriram");
        JsonNode view = me(email);
        assertThat(view.get("fullName").asString()).isEqualTo("Kavya Sriram");
        assertThat(view.get("phone").asString()).isEqualTo("+919840066001");

        String admin = accessToken(login(ADMIN_EMAIL, ADMIN_PASSWORD));
        mvc.perform(get("/api/admin/users").param("query", email).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.content[0].fullName").value("Kavya Sriram"));
    }

    @Test
    void duplicatesAndOlderProfilesChangeNothing() throws Exception {
        String email = uniqueEmail("order");
        String userId = body(register(email, "CUSTOMER")).at("/user/id").asString();
        Instant first = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String eventId = Ids.newId().toString();

        profiles.handle(profileUpdated(eventId, userId, "Second Name", null, first.plusSeconds(10)));
        profiles.handle(profileUpdated(Ids.newId().toString(), userId, "First Name", "+919000000001", first));
        assertThat(me(email).get("fullName").asString()).as("an older profile arrived late").isEqualTo("Second Name");
        assertThat(me(email).get("phone").isNull()).isTrue();

        profiles.handle(profileUpdated(Ids.newId().toString(), userId, "Third Name", "+919000000003",
                first.plusSeconds(20)));
        profiles.handle(profileUpdated(eventId, userId, "Second Name", null, first.plusSeconds(10)));   // redelivery
        assertThat(me(email).get("fullName").asString()).isEqualTo("Third Name");
        assertThat(me(email).get("phone").asString()).isEqualTo("+919000000003");
    }

    @Test
    void aPhoneOnlyChangeKeepsTheTokens() throws Exception {
        String email = uniqueEmail("phone");
        String userId = body(register(email, "CUSTOMER")).at("/user/id").asString();

        profiles.handle(profileUpdated(Ids.newId().toString(), userId, "Test CUSTOMER", "+919811122233",
                Instant.now()));

        assertThat(me(email).get("phone").asString()).isEqualTo("+919811122233");
        assertThat(redis.hasKey(TokenRevocationService.KEY_PREFIX + userId))
                .as("the name claim did not change: no refresh needed").isFalse();
    }

    @Test
    void unknownUsersAndOtherEventTypesAreSkipped() {
        String stranger = UUID.randomUUID().toString();
        assertThatCode(() -> profiles.handle(profileUpdated(Ids.newId().toString(), stranger, "Nobody", null,
                Instant.now()))).doesNotThrowAnyException();
        assertThatCode(() -> profiles.handle(new EventEnvelope(Ids.newId().toString(), "CustomerAddressAdded", 1,
                "Customer", stranger, null, "customer.events", "{}"))).doesNotThrowAnyException();
    }
}
