package com.grocery.vendor;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 §6: the vendor assistant's SEND_PURCHASE_ORDER calls {@code POST /api/vendor/purchase-orders/{id}/send} with
 * the body {@code {}} and an {@code X-Approval-Token} whose {@code aud} is {@code vendor-service} (=
 * {@code spring.application.name}). The service's own auto-configured verifier is used; only its JWKS location points at
 * a local HTTP server publishing the test key.
 */
class PurchaseOrderApprovalIntegrationTest extends PurchasingTestSupport {

    static final RSAKey KEY = generateKey();
    static final HttpServer JWKS = startJwks();

    @DynamicPropertySource
    static void approvalKeys(DynamicPropertyRegistry registry) {
        registry.add("grocery.approvals.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort()
                + "/.well-known/approval-jwks.json");
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-approval").keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
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

    /** An approval of {@code approver} for exactly this request, for the audience {@code audience}. */
    private static String token(UUID approvalId, String audience, String action, UUID approver, String path,
                                String body) throws JOSEException {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ApprovalToken.DEFAULT_ISSUER)
                .audience(audience)
                .jwtID(approvalId.toString())
                .subject(approver.toString())
                .claim(ApprovalToken.ON_BEHALF_OF, approver.toString())
                .claim(ApprovalToken.ACTION, action)
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

    private ResultActions sendWithToken(Store store, UUID poId, String token) throws Exception {
        return mvc.perform(post("/api/vendor/purchase-orders/{id}/send", poId).with(vendor(store))
                .header(ApprovalToken.HEADER, token).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void anApprovedSendWithAnEmptyObjectBodyRunsOnce() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Rice");
        UUID supplierId = supplier(store, "S");
        UUID poId = UUID.fromString(createPo(store, supplierId, List.of(poLine(rice, 10, "40.00"))).get("id")
                .asString());
        String path = "/api/vendor/purchase-orders/" + poId + "/send";
        UUID approvalId = UUID.randomUUID();
        String token = token(approvalId, "vendor-service", "SEND_PURCHASE_ORDER", store.ownerUserId(), path, "{}");

        sendWithToken(store, poId, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
        assertThat(poStatus(poId)).isEqualTo("SENT");
        assertThat(jdbc().sql("SELECT action FROM consumed_approval WHERE approval_id = UUID_TO_BIN(?)")
                .param(approvalId.toString()).query(String.class).single()).isEqualTo("SEND_PURCHASE_ORDER");

        // the same approval again → 409 before the controller (not INVALID_STATUS_TRANSITION)
        sendWithToken(store, poId, token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_ALREADY_USED"));
    }

    @Test
    void aTokenForAnotherServiceIsRefusedAndAPlainEmptyBodySendWorks() throws Exception {
        Store store = newStore();
        UUID rice = product(store, "Rice");
        UUID supplierId = supplier(store, "S");
        UUID poId = UUID.fromString(createPo(store, supplierId, List.of(poLine(rice, 10, "40.00"))).get("id")
                .asString());
        String path = "/api/vendor/purchase-orders/" + poId + "/send";

        sendWithToken(store, poId, token(UUID.randomUUID(), "inventory-service", "SEND_PURCHASE_ORDER",
                store.ownerUserId(), path, "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_TOKEN_INVALID"))
                .andExpect(jsonPath("$.reason").value("AUDIENCE"));
        assertThat(poStatus(poId)).isEqualTo("DRAFT");

        // without a token, `{}` as the body is accepted too
        mvc.perform(post("/api/vendor/purchase-orders/{id}/send", poId).with(vendor(store))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
    }
}
