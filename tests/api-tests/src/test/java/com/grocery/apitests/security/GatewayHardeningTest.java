package com.grocery.apitests.security;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Jwt;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge: identity headers from clients are stripped, service-to-service and key endpoints are not routed, a forged
 * approval token is refused by the target service, CORS only admits the app's origin, and errors leak no internals.
 */
@Tag("security")
@Order(13)
@DisplayName("Gateway hardening")
class GatewayHardeningTest {

    @Test
    void spoofed_identity_headers_are_ignored() {
        Session customer = Session.register("CUSTOMER", "Apit Spoof");
        Session admin = World.adminWithRole("SUPER_ADMIN");
        Api.as(customer).header("X-User-Id", admin.userId()).header("X-User-Roles", "SUPER_ADMIN")
                .header("X-Internal-Token", "letmein").get("/api/admin/users").problem(403, "FORBIDDEN");
        Api.as(customer).header("X-User-Id", admin.userId()).header("X-User-Roles", "SUPER_ADMIN")
                .get("/api/auth/me").ok().has("id", customer.userId()).has("email", customer.email());
        Api.anon().header("X-User-Id", admin.userId()).header("X-User-Roles", "SUPER_ADMIN")
                .header("X-Internal-Token", "letmein").get("/api/orders").problem(401, "UNAUTHENTICATED");
        Api.as(customer).header("X-User-Id", admin.userId()).header("X-User-Roles", "VENDOR")
                .get("/api/vendor/products").problem(403, null);
        Api.as(customer).header("X-Forwarded-User", "admin@grocery.local").header("X-Roles", "SUPER_ADMIN")
                .get("/api/admin/stats").problem(403, "FORBIDDEN");
    }

    @ParameterizedTest(name = "{0} is not routed")
    @ValueSource(strings = {"GET /internal/vendors/01a1d000-0000-7000-8000-00000000abcd/store",
            "POST /internal/prices/quote", "POST /internal/inventory/availability", "POST /internal/catalog/products/lookup",
            "POST /internal/coupons/evaluate", "POST /internal/coupons/redeem", "GET /internal/delivery-fees",
            "GET /.well-known/approval-jwks.json",
            "GET /api/prices/quote", "POST /api/prices/quote", "GET /actuator/prometheus", "GET /actuator/env",
            "GET /actuator/heapdump", "GET /actuator/loggers"})
    @Tag("smoke")
    void internal_and_key_endpoints_are_not_reachable_through_the_gateway(String request) {
        String method = request.substring(0, request.indexOf(' '));
        String path = request.substring(request.indexOf(' ') + 1);
        Api.Req req = Api.as(internalProbe());
        if (method.equals("POST")) {
            req.body(obj("productIds", List.of(UUID.randomUUID().toString())));
        }
        Res r = req.send(method, path);
        assertThat(r.status()).as(r.describe()).isEqualTo(404);
        assertThat(r.body()).doesNotContain("\"keys\"").doesNotContain("\"items\"");
        Res anonymous = Api.anon().send(method, path);
        assertThat(anonymous.status()).as(anonymous.describe()).isIn(401, 404);
    }

    @Test
    void a_forged_approval_token_is_refused_by_the_target_service() {
        World.Customer customer = World.newCustomer("Forger");
        World.fillCart(customer, World.storeA().rice(), 1);
        BigDecimal total = World.previewTotal(customer);
        String body = "{\"addressId\":\"" + customer.addressId() + "\",\"expectedTotal\":" + total + "}";
        Res garbage = Api.as(customer.session()).header("X-Approval-Token", "garbage").idempotencyKey(World.key("forged"))
                .body(body).post("/api/orders").problem(403, "APPROVAL_TOKEN_INVALID");
        assertThat(garbage.str("reason")).isEqualTo("SIGNATURE");

        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "approval+jwt");
        header.put("kid", "apit-forged");
        Map<String, Object> claims = obj("iss", "ai-orchestrator-service", "aud", "order-service",
                "jti", UUID.randomUUID().toString(), "sub", customer.userId(), "act", "PLACE_ORDER", "htm", "POST",
                "htu", "/api/orders", "bsh", sha256(body), "iat", now, "exp", now + 60);
        String forged = Jwt.rs256(header, claims, Jwt.attackerKey().getPrivate());
        Res refused = Api.as(customer.session()).header("X-Approval-Token", forged).idempotencyKey(World.key("forged2"))
                .body(body).post("/api/orders").problem(403, "APPROVAL_TOKEN_INVALID");
        assertThat(refused.str("reason")).isEqualTo("SIGNATURE");
        Api.as(customer.session()).get("/api/orders").ok().has("totalElements", 0);
        Api.as(customer.session()).delete("/api/cart").expect(204);

        Api.as(World.adminWithRole("FINANCE_ADMIN")).header("X-Approval-Token", forged).idempotencyKey(World.key("forged3"))
                .body(obj("orderId", UUID.randomUUID().toString(), "note", "x")).post("/api/admin/refunds")
                .problem(403, "APPROVAL_TOKEN_INVALID");
    }

    @Test
    void cors_admits_only_the_app_origin() {
        Res evil = Api.anon().header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
                .send("OPTIONS", "/api/orders");
        assertThat(evil.status()).as(evil.describe()).isEqualTo(403);
        assertThat(evil.header("Access-Control-Allow-Origin")).isNull();
        Res app = Api.anon().header("Origin", "http://localhost:6420").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
                .send("OPTIONS", "/api/orders");
        assertThat(app.status()).as(app.describe()).isEqualTo(200);
        assertThat(app.header("Access-Control-Allow-Origin")).isEqualTo("http://localhost:6420");
        assertThat(app.header("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void errors_leak_no_internals() {
        Session customer = World.newCustomer("Leak").session();
        Res malformed = Api.as(customer).body("{\"type\":\"PRODUCT\",").post("/api/cart/items");
        assertThat(malformed.status()).as(malformed.describe()).isEqualTo(400);
        malformed.problem(400, null);
        Res wrongType = Api.as(customer).body(obj("type", "PRODUCT", "productId", "x' OR 1=1 --", "quantity", "lots"))
                .post("/api/cart/items");
        assertThat(wrongType.status()).as(wrongType.describe()).isBetween(400, 499);
        for (Res r : List.of(malformed, wrongType,
                Api.anon().get("/api/search/products?q=%27%20OR%201%3D1%20--&size=1"),
                Api.anon().get("/api/catalog/products/%3Cscript%3E"))) {
            assertThat(r.status()).as(r.describe()).isLessThan(500);
            assertThat(r.body()).as(r.describe()).doesNotContain("Exception").doesNotContain("\tat ")
                    .doesNotContain("org.springframework").doesNotContain("SQL").doesNotContain("<script>");
        }
    }

    private static Session internalProbe;

    private static synchronized Session internalProbe() {
        if (internalProbe == null) {
            internalProbe = Session.register("CUSTOMER", "Apit Internal");
        }
        return internalProbe;
    }

    private static String sha256(String body) {
        try {
            return Jwt.b64(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
