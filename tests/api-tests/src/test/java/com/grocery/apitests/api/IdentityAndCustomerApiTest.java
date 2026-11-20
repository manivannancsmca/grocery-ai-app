package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2: registration, login, refresh cookie, admin users, suspension and customer addresses. */
@Tag("api")
@Order(2)
@DisplayName("Identity and customer API")
class IdentityAndCustomerApiTest {

    @Test
    @Tag("smoke")
    void register_returns_an_AuthResponse_and_an_httpOnly_refresh_cookie() {
        String email = Session.uniqueEmail("CUSTOMER");
        Res r = Api.anon().body(obj("email", email, "password", "Secret123", "fullName", "Apit Register",
                "phone", "+919876543210", "role", "CUSTOMER")).post("/api/auth/register").created();
        r.has("tokenType", "Bearer").has("expiresIn", 900).has("user.email", email).has("user.status", "ACTIVE");
        assertThat(r.<String>list("user.roles")).containsExactly("CUSTOMER");
        assertThat(r.str("accessToken")).matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");
        String cookie = String.join(";", r.headers("Set-Cookie"));
        assertThat(cookie).contains("grocery_rt=").containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Strict").contains("Path=/api/auth");

        Session session = Session.login(email, "Secret123");
        Api.as(session).get("/api/auth/me").ok().has("email", email).has("fullName", "Apit Register");
    }

    @Test
    void the_access_token_has_the_documented_claims() {
        Session session = Session.register("CUSTOMER", "Apit Claims");
        var claims = com.grocery.apitests.support.Jwt.claims(session.rawToken());
        assertThat(claims.path("iss").asText()).isEqualTo("https://identity.grocery.local");
        assertThat(claims.path("aud").isArray() ? claims.path("aud").get(0).asText() : claims.path("aud").asText())
                .isEqualTo("grocery-api");
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(900);
        assertThat(claims.path("jti").asText()).isNotBlank();
        assertThat(claims.path("iat_ms").asLong()).isPositive();
        assertThat(com.grocery.apitests.support.Jwt.header(session.rawToken()).path("alg").asText()).isEqualTo("RS256");
        Api.anon().get("/.well-known/jwks.json").ok().bodyContains("\"kty\":\"RSA\"").bodyDoesNotContain("\"d\":");
    }

    @Test
    void duplicate_email_wrong_password_and_invalid_input_are_problem_details() {
        Session session = Session.register("CUSTOMER", "Apit Dup");
        Api.anon().body(obj("email", session.email(), "password", "Secret123", "fullName", "X", "role", "CUSTOMER"))
                .post("/api/auth/register").problem(409, "EMAIL_ALREADY_REGISTERED");
        Api.anon().body(obj("email", session.email(), "password", "Wrong1234")).post("/api/auth/login")
                .problem(401, "INVALID_CREDENTIALS");

        Res invalid = Api.anon().body(obj("email", "not-an-email", "password", "short", "fullName", "",
                "role", "CUSTOMER")).post("/api/auth/register").problem(400, "VALIDATION_FAILED");
        List<Map<String, String>> errors = invalid.list("errors");
        assertThat(errors).as("field errors of %s", invalid).isNotEmpty()
                .allSatisfy(e -> assertThat(e).containsKeys("field", "message"));
        assertThat(errors).extracting(e -> e.get("field")).contains("email", "password");
    }

    @Test
    void an_admin_role_cannot_be_self_registered() {
        Res r = Api.anon().body(obj("email", Session.uniqueEmail("SUPER_ADMIN"), "password", "Secret123",
                "fullName", "Apit Sneaky", "role", "SUPER_ADMIN")).post("/api/auth/register");
        assertThat(r.status()).as(r.describe()).isEqualTo(400);
        assertThat(r.contentType()).startsWith("application/problem+json");
    }

    @Test
    void refresh_needs_the_csrf_header_and_rotates_the_cookie() {
        String email = Session.uniqueEmail("CUSTOMER");
        Res registered = Api.anon().body(obj("email", email, "password", "Secret123", "fullName", "Apit Refresh",
                "role", "CUSTOMER")).post("/api/auth/register").created();
        String cookie = registered.raw().getCookie("grocery_rt");
        assertThat(cookie).isNotBlank();

        Api.anon().header("Cookie", "grocery_rt=" + cookie).post("/api/auth/refresh")
                .problem(403, "CSRF_HEADER_MISSING");
        Res refreshed = Api.anon().header("Cookie", "grocery_rt=" + cookie).header("X-Requested-With", "XMLHttpRequest")
                .post("/api/auth/refresh").ok();
        assertThat(refreshed.str("accessToken")).isNotBlank();
        assertThat(refreshed.raw().getCookie("grocery_rt")).isNotBlank().isNotEqualTo(cookie);
        Api.anon().header("X-Requested-With", "XMLHttpRequest").post("/api/auth/refresh")
                .problem(401, "REFRESH_TOKEN_INVALID");
    }

    @Test
    void admin_user_list_is_a_page_with_size_capped_at_100_and_bad_filters_rejected() {
        Res page = Api.as(World.admin()).get("/api/admin/users?size=500").ok().page();
        assertThat(page.integer("size")).isEqualTo(100);
        Api.as(World.admin()).get("/api/admin/users?page=0&size=5").ok().page().has("size", 5).has("page", 0);
        Api.as(World.admin()).get("/api/admin/users?status=BOGUS").problem(400, "INVALID_PARAMETER");
    }

    @Test
    void suspension_revokes_tokens_and_blocks_login_until_reactivated() {
        Session user = Session.register("CUSTOMER", "Apit Suspend");
        String oldToken = user.rawToken();
        String userId = user.userId();
        Session superAdmin = World.adminWithRole("SUPER_ADMIN");
        Api.as(superAdmin).body(obj("reason", "API test")).post("/api/admin/users/" + userId + "/suspend").ok()
                .has("status", "SUSPENDED");
        Await.until("old token revoked", Await.seconds(20), () -> Api.asToken(oldToken).get("/api/auth/me"),
                r -> r.status() == 401).problem(401, "TOKEN_REVOKED");
        Api.anon().body(obj("email", user.email(), "password", user.password())).post("/api/auth/login")
                .problem(403, "ACCOUNT_SUSPENDED");
        Api.as(superAdmin).post("/api/admin/users/" + userId + "/reactivate").ok().has("status", "ACTIVE");
        Api.anon().body(obj("email", user.email(), "password", user.password())).post("/api/auth/login").ok();

        Api.as(superAdmin).body(obj("reason", "self")).post("/api/admin/users/" + superAdmin.userId() + "/suspend")
                .problem(422, "CANNOT_SUSPEND_SELF");
        Api.as(superAdmin).body(obj("reason", "nobody"))
                .post("/api/admin/users/00000000-0000-7000-8000-000000000000/suspend").problem(404, "USER_NOT_FOUND");
    }

    @Test
    void customer_profile_and_addresses() {
        World.Customer customer = World.newCustomer("Address");
        Api.as(customer.session()).get("/api/customers/me").ok().has("id", customer.userId());
        Res list = Api.as(customer.session()).get("/api/customers/me/addresses").ok();
        assertThat(list.<Map<String, Object>>list("")).hasSize(1).first()
                .satisfies(a -> assertThat(a.get("isDefault")).isEqualTo(true));

        Map<String, Object> bad = World.address("X", 12.97, 77.59);
        bad.put("postalCode", "012345");
        Res invalid = Api.as(customer.session()).body(bad).post("/api/customers/me/addresses")
                .problem(400, "VALIDATION_FAILED");
        assertThat(invalid.<Map<String, String>>list("errors")).extracting(e -> e.get("field")).contains("postalCode");

        String second = Api.as(customer.session()).body(World.address("Office", World.HOME_LAT, World.HOME_LNG))
                .post("/api/customers/me/addresses").created().has("isDefault", false).str("id");
        Api.as(customer.session()).post("/api/customers/me/addresses/" + second + "/default").ok().has("isDefault", true);
        Api.as(customer.session()).delete("/api/customers/me/addresses/" + second).expect(204);
        assertThat(Api.as(customer.session()).get("/api/customers/me/addresses").ok().<Map<String, Object>>list(""))
                .singleElement().satisfies(a -> assertThat(a.get("isDefault")).isEqualTo(true));
    }
}
