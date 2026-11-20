package com.grocery.inventory;

import com.grocery.common.approval.ApprovalToken;
import com.grocery.common.approval.ApprovalTokenVerifier;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 §6: inventory-service is an approval-token target (grocery-common 0.8.0 filter, {@code consumed_approval}
 * from Flyway V5). The vendor assistant's ADJUST_STOCK calls {@code POST /api/vendor/inventory/{id}/adjustments} with an
 * {@code X-Approval-Token} whose {@code aud} is {@code inventory-service} (= {@code spring.application.name}); the filter
 * verifies it before the controller runs and records it, so it works once. The service's own auto-configured verifier
 * is used; only its JWKS location points at a local HTTP server publishing the test key.
 */
class ApprovalTokenIntegrationTest extends AbstractIntegrationTest {

    static final RSAKey KEY = generateKey();
    static final HttpServer JWKS = startJwks();

    @DynamicPropertySource
    static void approvalKeys(DynamicPropertyRegistry registry) {
        registry.add("grocery.approvals.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort()
                + "/.well-known/approval-jwks.json");
    }

    private static HttpServer startJwks() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/approval-jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-approval").keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An ADJUST_STOCK approval of {@code approver} for exactly this request (audience inventory-service). */
    private static String token(UUID approvalId, UUID approver, String path, String body) throws JOSEException {
        return token(approvalId, "inventory-service", approver, path, body);
    }

    private static String token(UUID approvalId, String audience, UUID approver, String path, String body)
            throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ApprovalToken.DEFAULT_ISSUER)
                .audience(audience)
                .jwtID(approvalId.toString())
                .subject(approver.toString())
                .claim(ApprovalToken.ON_BEHALF_OF, approver.toString())
                .claim(ApprovalToken.ACTION, "ADJUST_STOCK")
                .claim(ApprovalToken.HTTP_METHOD, "POST")
                .claim(ApprovalToken.HTTP_URI, path)
                .claim(ApprovalToken.BODY_HASH, ApprovalTokenVerifier.bodyHash(body.getBytes(StandardCharsets.UTF_8)))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new JOSEObjectType(ApprovalToken.TYPE)).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private ResultActions adjustWithToken(UUID productId, String token, String body) throws Exception {
        return mvc.perform(post("/api/vendor/inventory/{id}/adjustments", productId).with(vendor(vendorId))
                .header(ApprovalToken.HEADER, token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void anApprovedAdjustmentRunsOnceAndIsRecorded() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Rice", 5);
        String path = "/api/vendor/inventory/" + productId + "/adjustments";
        String body = "{\"type\":\"SET\",\"quantity\":12,\"reason\":\"STOCK_COUNT\",\"note\":\"assistant\"}";
        UUID approvalId = UUID.randomUUID();
        String token = token(approvalId, ownerOf(vendorId), path, body);

        adjustWithToken(productId, token, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(12));
        assertThat(onHand(productId)).isEqualTo(12);
        assertThat(jdbc().sql("SELECT action FROM consumed_approval WHERE approval_id = UUID_TO_BIN(?)")
                .param(approvalId.toString()).query(String.class).single()).isEqualTo("ADJUST_STOCK");

        // the same approval again → 409, nothing runs
        adjustWithToken(productId, token, body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_ALREADY_USED"));
        assertThat(movementCount(productId)).isEqualTo(2);
    }

    @Test
    void aTokenForAnotherBodyOrAForgedOneIsRefusedBeforeTheHandler() throws Exception {
        UUID productId = newProductWithStock(vendorId, "Dal", 5);
        String path = "/api/vendor/inventory/" + productId + "/adjustments";
        String approved = "{\"type\":\"DECREASE\",\"quantity\":1,\"reason\":\"DAMAGED\"}";
        String token = token(UUID.randomUUID(), ownerOf(vendorId), path, approved);

        adjustWithToken(productId, token, "{\"type\":\"DECREASE\",\"quantity\":5,\"reason\":\"DAMAGED\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_BODY_MISMATCH"));
        adjustWithToken(productId, "not-a-token", approved)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_TOKEN_INVALID"));
        // a genuine token meant for another service (aud vendor-service)
        adjustWithToken(productId, token(UUID.randomUUID(), "vendor-service", ownerOf(vendorId), path, approved), approved)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_TOKEN_INVALID"))
                .andExpect(jsonPath("$.reason").value("AUDIENCE"));
        // another user (not the approver) presenting it
        mvc.perform(post("/api/vendor/inventory/{id}/adjustments", productId)
                        .with(as(UUID.randomUUID(), vendorId, "VENDOR"))
                        .header(ApprovalToken.HEADER, token).contentType(MediaType.APPLICATION_JSON).content(approved))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_TOKEN_INVALID"));
        assertThat(onHand(productId)).isEqualTo(5);

        // without the header the request is an ordinary one
        adjust(vendorId, productId, "DECREASE", 1, "DAMAGED");
        assertThat(onHand(productId)).isEqualTo(4);
    }
}
