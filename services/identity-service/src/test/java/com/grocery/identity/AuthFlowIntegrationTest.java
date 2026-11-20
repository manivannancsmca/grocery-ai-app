package com.grocery.identity;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    private MockHttpServletResponse refresh(String cookieValue) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("grocery_rt", cookieValue))
                .header("X-Requested-With", "XMLHttpRequest")).andReturn().getResponse();
    }

    @Test
    void registerReturnsTokenSecureCookieAndPublishesUserRegistered() throws Exception {
        String email = uniqueEmail("vendor");
        MockHttpServletResponse response = register(email, "VENDOR");

        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode body = body(response);
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.at("/user/roles/0").asString()).isEqualTo("VENDOR");
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("grocery_rt=").contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");

        JsonNode claims = claims(accessToken(response));
        assertThat(claims.get("sub").asString()).isEqualTo(body.at("/user/id").asString());
        assertThat(claims.get("iss").asString()).isEqualTo("https://identity.grocery.local");
        // A single audience is serialised as a string (RFC 7519 allows string or array)
        assertThat(claims.get("aud").asString()).isEqualTo("grocery-api");
        assertThat(claims.at("/roles/0").asString()).isEqualTo("VENDOR");
        assertThat(claims.has("iat_ms")).isTrue();
        assertThat(claims.has("vendor_id")).as("not approved yet").isFalse();

        Long events = JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM outbox_event WHERE event_type = 'UserRegistered' AND aggregate_id = ?")
                .param(body.at("/user/id").asString()).query(Long.class).single();
        assertThat(events).isEqualTo(1);
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String email = uniqueEmail("dup");
        register(email, "CUSTOMER");
        MockHttpServletResponse second = register(email.toUpperCase(), "CUSTOMER");

        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(body(second).get("errorCode").asString()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void adminRolesCannotSelfRegisterAndWeakPasswordsFail() throws Exception {
        MockHttpServletResponse admin = register(uniqueEmail("x"), "SUPER_ADMIN");
        assertThat(admin.getStatus()).isEqualTo(400);
        assertThat(body(admin).get("errorCode").asString()).isEqualTo("VALIDATION_FAILED");

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"not-an-email","password":"short","fullName":"","role":"CUSTOMER"}"""))
                .andExpect(status().isBadRequest())
                // email format, blank name, and the password breaks two rules (length, letter+digit)
                .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountTemporarily() throws Exception {
        String email = uniqueEmail("lock");
        register(email, "CUSTOMER");
        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "WrongPass1").getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse locked = login(email, PASSWORD);
        assertThat(locked.getStatus()).as("correct password while locked").isEqualTo(401);
        assertThat(body(locked).get("errorCode").asString()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void unknownEmailGetsTheSameAnswerAsAWrongPassword() throws Exception {
        MockHttpServletResponse response = login(uniqueEmail("nobody"), PASSWORD);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body(response).get("errorCode").asString()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void meRequiresAValidToken() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));

        String email = uniqueEmail("me");
        String token = accessToken(register(email, "CUSTOMER"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void refreshRotatesTheTokenAndDetectsReuse() throws Exception {
        String first = refreshCookie(register(uniqueEmail("rot"), "CUSTOMER"));

        MockHttpServletResponse rotated = refresh(first);
        assertThat(rotated.getStatus()).isEqualTo(200);
        String second = refreshCookie(rotated);
        assertThat(second).isNotEqualTo(first);

        // Same old token again right away = another tab refreshing concurrently → retryable, no revocation
        MockHttpServletResponse race = refresh(first);
        assertThat(race.getStatus()).isEqualTo(409);
        assertThat(body(race).get("errorCode").asString()).isEqualTo("REFRESH_RACE");

        // Later reuse of the rotated token = stolen copy → the whole session family is revoked
        JdbcClient.create(dataSource).sql("""
                UPDATE refresh_token SET revoked_at = revoked_at - INTERVAL 1 MINUTE
                WHERE revoke_reason = 'ROTATED' AND revoked_at IS NOT NULL""").update();
        MockHttpServletResponse reuse = refresh(first);
        assertThat(reuse.getStatus()).isEqualTo(401);
        assertThat(body(reuse).get("errorCode").asString()).isEqualTo("REFRESH_TOKEN_INVALID");
        assertThat(refresh(second).getStatus()).as("legitimate token of the same family").isEqualTo(401);
    }

    @Test
    void refreshRequiresTheCsrfHeader() throws Exception {
        String cookie = refreshCookie(register(uniqueEmail("csrf"), "CUSTOMER"));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("grocery_rt", cookie)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CSRF_HEADER_MISSING"));
    }

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
        String cookie = refreshCookie(register(uniqueEmail("out"), "CUSTOMER"));
        MockHttpServletResponse logout = mvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("grocery_rt", cookie)).header("X-Requested-With", "XMLHttpRequest"))
                .andReturn().getResponse();

        assertThat(logout.getStatus()).isEqualTo(204);
        assertThat(logout.getHeader("Set-Cookie")).contains("Max-Age=0");
        assertThat(refresh(cookie).getStatus()).isEqualTo(401);
    }

    @Test
    void jwksPublishesTheSigningKey() throws Exception {
        String token = accessToken(register(uniqueEmail("jwks"), "CUSTOMER"));
        String kid = json.readTree(new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0])))
                .get("kid").asString();

        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value(kid))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());   // never the private part
    }
}
