package com.grocery.apitests.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Json;
import com.grocery.apitests.support.Jwt;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT tampering at the edge: a modified payload, a broken signature, {@code alg: none}, HS256 "algorithm confusion",
 * and tokens signed with an attacker's key (valid-looking, expired, wrong audience, wrong issuer) are all 401
 * {@code INVALID_TOKEN}/{@code TOKEN_EXPIRED} ProblemDetails — never a 200 and never a 403 (which would mean the
 * claims were believed). The identity signing key is not available to tests, so "expired" and "wrong audience" are
 * forged with the attacker key: they must fail at the signature already.
 */
@Tag("security")
@Order(12)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("JWT tampering")
class JwtTamperingTest {

    Session customer;
    String token;
    Map<String, Object> claims;
    Map<String, Object> header;
    KeyPair attacker;

    @BeforeAll
    @SuppressWarnings("unchecked")
    void setUp() {
        customer = Session.register("CUSTOMER", "Apit Jwt");
        token = customer.rawToken();
        claims = Json.MAPPER.convertValue(Jwt.claims(token), LinkedHashMap.class);
        header = Json.MAPPER.convertValue(Jwt.header(token), LinkedHashMap.class);
        attacker = Jwt.attackerKey();
    }

    private Map<String, Object> claims(Object... overrides) {
        Map<String, Object> copy = new LinkedHashMap<>(claims);
        for (int i = 0; i < overrides.length; i += 2) {
            copy.put((String) overrides[i], overrides[i + 1]);
        }
        return copy;
    }

    private static void rejected(String forged) {
        for (String path : List.of("/api/auth/me", "/api/admin/users", "/api/orders")) {
            Res r = Api.asToken(forged).get(path);
            assertThat(r.status()).as("forged token on %s: %s", path, r.describe()).isEqualTo(401);
            r.problem(401, null);
            assertThat(r.errorCode()).isIn("INVALID_TOKEN", "TOKEN_EXPIRED");
        }
    }

    @Test
    @Tag("smoke")
    void the_genuine_token_works() {
        Api.asToken(token).get("/api/auth/me").ok().has("id", customer.userId());
    }

    @Test
    void a_payload_promoted_to_super_admin_keeps_no_valid_signature() {
        rejected(Jwt.withPayload(token, claims("roles", List.of("SUPER_ADMIN"))));
        rejected(Jwt.withPayload(token, claims("sub", UUID.randomUUID().toString())));
        rejected(Jwt.withPayload(token, claims("exp", Instant.now().plusSeconds(86_400).getEpochSecond())));
    }

    @Test
    void a_broken_signature_is_rejected() {
        rejected(Jwt.withBrokenSignature(token));
        Api.asToken(token.substring(0, token.lastIndexOf('.'))).get("/api/auth/me").problem(401, "INVALID_TOKEN");
        Api.asToken("not-a-jwt").get("/api/auth/me").problem(401, "INVALID_TOKEN");
    }

    @Test
    void alg_none_is_rejected() {
        rejected(Jwt.unsigned(claims("roles", List.of("SUPER_ADMIN"))));
    }

    @Test
    void hs256_signed_with_the_public_key_is_rejected() {
        JsonNode jwks = Json.read(Api.anon().get("/.well-known/jwks.json").ok().body());
        byte[] publicKeyAsSecret = Json.write(jwks.path("keys").get(0)).getBytes(StandardCharsets.UTF_8);
        Map<String, Object> hsHeader = new LinkedHashMap<>(header);
        hsHeader.put("alg", "HS256");
        rejected(Jwt.hs256(hsHeader, claims("roles", List.of("SUPER_ADMIN")), publicKeyAsSecret));
        rejected(Jwt.hs256(hsHeader, claims(), jwks.path("keys").get(0).path("n").asText().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void tokens_signed_with_an_attacker_key_are_rejected_even_with_the_real_kid() {
        long now = Instant.now().getEpochSecond();
        rejected(Jwt.rs256(header, claims("roles", List.of("SUPER_ADMIN"), "iat", now, "exp", now + 600), attacker.getPrivate()));
        rejected(Jwt.rs256(header, claims("iat", now - 3600, "exp", now - 1800), attacker.getPrivate()));        // expired
        rejected(Jwt.rs256(header, claims("aud", "someone-else"), attacker.getPrivate()));                      // audience
        rejected(Jwt.rs256(header, claims("iss", "https://evil.example"), attacker.getPrivate()));             // issuer
        Map<String, Object> jku = new LinkedHashMap<>(header);
        jku.put("jku", "https://evil.example/jwks.json");
        jku.put("kid", "evil");
        rejected(Jwt.rs256(jku, claims(), attacker.getPrivate()));
    }

    @Test
    void a_bearer_token_in_the_query_string_is_ignored() {
        Api.anon().get("/api/auth/me?access_token=" + token).problem(401, "UNAUTHENTICATED");
    }
}
