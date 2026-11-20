package com.grocery.ai;

import com.grocery.ai.approval.ApprovalView;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Execution of an approved action (contract §4, §5): exactly the stored call through the gateway with the approver's
 * token, a signed X-Approval-Token that verifies against our JWKS, Idempotency-Key = approvalId; the target's answer
 * decides COMPLETED or FAILED.
 */
class ApprovalExecutionIntegrationTest extends AbstractIntegrationTest {

    private JWKSet jwks() throws Exception {
        String body = mvc.perform(get("/.well-known/approval-jwks.json"))          // no token needed
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300"))
                .andReturn().getResponse().getContentAsString();
        return JWKSet.parse(body);
    }

    private static String sha256Base64Url(byte[] body) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(body));
    }

    @Test
    void approvingAnOrderPostsExactlyTheStoredCallWithAVerifiableToken() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        String storedBody = (String) approvalRow(approval.id()).get("request_body");
        GATEWAY.json("POST", "/api/orders", 201, Fixtures.json(Fixtures.order(orderId, "GA-7K3F9Q2M", "CREATED",
                true, "285.00")));

        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER"))
                        .header("X-Correlation-Id", "approve-corr-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.result.orderNumber").value("GA-7K3F9Q2M"))
                .andExpect(jsonPath("$.failure").doesNotExist())
                .andExpect(jsonPath("$.executedAt").isNotEmpty())
                .andExpect(jsonPath("$.canDecide").value(false));

        FakeGateway.Request call = GATEWAY.requests("POST", "/api/orders").getFirst();
        assertThat(call.body()).isEqualTo(storedBody.getBytes(StandardCharsets.UTF_8));
        assertThat(call.header("Content-Type")).startsWith("application/json");
        assertThat(call.header("Authorization")).isEqualTo("Bearer token-of-" + customer);   // the approver's own
        assertThat(call.header("Idempotency-Key")).isEqualTo(approval.id().toString());
        assertThat(call.header("X-Correlation-Id")).isEqualTo("approve-corr-1");

        SignedJWT token = SignedJWT.parse(call.header("X-Approval-Token"));
        assertThat(token.getHeader().getType()).isEqualTo(new JOSEObjectType("approval+jwt"));
        assertThat(token.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        RSAKey key = (RSAKey) jwks().getKeyByKeyId(token.getHeader().getKeyID());
        assertThat(key).as("the token's kid is published").isNotNull();
        assertThat(key.isPrivate()).isFalse();
        assertThat(key.getKeyID()).isEqualTo(key.computeThumbprint().toString());
        assertThat(token.verify(new RSASSAVerifier(key))).isTrue();

        JWTClaimsSet claims = token.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("ai-orchestrator-service");
        assertThat(claims.getAudience()).containsExactly("order-service");
        assertThat(claims.getJWTID()).isEqualTo(approval.id().toString());
        assertThat(claims.getSubject()).isEqualTo(customer.toString());
        assertThat(claims.getStringClaim("obo")).isEqualTo(customer.toString());
        assertThat(claims.getStringClaim("act")).isEqualTo("PLACE_ORDER");
        assertThat(claims.getStringClaim("htm")).isEqualTo("POST");
        assertThat(claims.getStringClaim("htu")).isEqualTo("/api/orders");
        assertThat(claims.getStringClaim("bsh")).isEqualTo(sha256Base64Url(call.body()));
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofSeconds(60));

        Map<String, Object> row = approvalRow(approval.id());
        assertThat(row).containsEntry("status", "COMPLETED").containsEntry("result_reference", "GA-7K3F9Q2M")
                .containsEntry("decided_by", customer.toString());
        assertThat(eventTypes(approval.id())).containsExactly("ApprovalRequested", "ApprovalDecided",
                "ApprovalExecuted");
        assertThat(event(approval.id(), "ApprovalDecided").get("decision").asString()).isEqualTo("APPROVED");
        JsonNode executed = event(approval.id(), "ApprovalExecuted");
        assertThat(executed.get("resultReference").asString()).isEqualTo("GA-7K3F9Q2M");
        assertThat(executed.get("decidedBy").asString()).isEqualTo(customer.toString());
    }

    @Test
    void anAdminActionCarriesNoOboAndNoIdempotencyKeyWhereTheEndpointTakesNone() throws Exception {
        UUID requester = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        ApprovalView suspension = proposeSuspension(requester, vendorId);
        GATEWAY.json("POST", "/api/admin/vendors/" + vendorId + "/suspend", 200, Fixtures.json(Fixtures.map(
                "id", vendorId.toString(), "businessName", "Fresh Mart", "status", "SUSPENDED")));

        mvc.perform(post("/api/approvals/{id}/approve", suspension.id()).with(as(approver, "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.vendorId").value(vendorId.toString()));

        FakeGateway.Request call = GATEWAY.requests("POST", "/api/admin/vendors/.*/suspend").getFirst();
        assertThat(call.header("Idempotency-Key")).isNull();
        assertThat(call.bodyText()).isEqualTo("{\"reason\":\"Repeated hygiene complaints\"}");
        JWTClaimsSet claims = SignedJWT.parse(call.header("X-Approval-Token")).getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly("vendor-service");
        assertThat(claims.getSubject()).isEqualTo(approver.toString());
        assertThat(claims.getClaim("obo")).isNull();
        assertThat(claims.getStringClaim("htu")).isEqualTo("/api/admin/vendors/" + vendorId + "/suspend");
        assertThat(event(suspension.id(), "ApprovalExecuted").get("resultReference").asString())
                .isEqualTo("Fresh Mart");
    }

    @Test
    void refundsSendTheIdempotencyKeyToPaymentService() throws Exception {
        UUID refundId = UUID.randomUUID();
        ApprovalView refund = proposeRefund(UUID.randomUUID(), "SUPPORT", "150.00");
        GATEWAY.json("POST", "/api/admin/refunds", 201, Fixtures.json(Fixtures.map("id", refundId.toString(),
                "amount", 150.00, "status", "PROCESSED")));

        mvc.perform(post("/api/approvals/{id}/approve", refund.id()).with(as(UUID.randomUUID(), "FINANCE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.refundId").value(refundId.toString()));

        FakeGateway.Request call = GATEWAY.requests("POST", "/api/admin/refunds").getFirst();
        assertThat(call.header("Idempotency-Key")).isEqualTo(refund.id().toString());
        assertThat(SignedJWT.parse(call.header("X-Approval-Token")).getJWTClaimsSet().getAudience())
                .containsExactly("payment-service");
    }

    @Test
    void aTargetRefusalFailsTheRequestWithTheTargetsCode() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        GATEWAY.on("POST", "/api/orders", request -> FakeGateway.Response.problem(409, "TOTAL_CHANGED",
                "The order total changed from 285.00 to 290.00. Please review it."));

        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("TOTAL_CHANGED"))
                .andExpect(jsonPath("$.failure.httpStatus").value(409))
                .andExpect(jsonPath("$.failure.message").value("The order total changed from 285.00 to 290.00. "
                        + "Please review it."))
                .andExpect(jsonPath("$.result").doesNotExist());

        JsonNode failed = event(approval.id(), "ApprovalFailed");
        assertThat(failed.get("errorCode").asString()).isEqualTo("TOTAL_CHANGED");
        assertThat(failed.get("httpStatus").asInt()).isEqualTo(409);
        assertThat(failed.get("decidedBy").asString()).isEqualTo(customer.toString());
        // never retried: a second approve is 409
        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.currentStatus").value("FAILED"));
        assertThat(GATEWAY.requests("POST", "/api/orders")).hasSize(1);
    }

    @Test
    void aTamperedBodyIsRefusedByTheTarget() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        // the stored body is changed after the proposal (e.g. a cheaper total)
        jdbc().sql("UPDATE approval_request SET request_body = REPLACE(request_body, '285.00', '1.00') "
                + "WHERE id = UUID_TO_BIN(?)").param(approval.id().toString()).update();
        // the target's filter (grocery-common 0.8.0) compares the token's bsh with the body it received
        GATEWAY.on("POST", "/api/orders", request -> {
            try {
                String bsh = SignedJWT.parse(request.header("X-Approval-Token")).getJWTClaimsSet()
                        .getStringClaim("bsh");
                return bsh.equals(sha256Base64Url(request.body()))
                        ? FakeGateway.Response.json(201, Fixtures.json(Fixtures.order(UUID.randomUUID(),
                        "GA-TAMPER1", "CREATED", true, "1.00")))
                        : FakeGateway.Response.problem(403, "APPROVAL_BODY_MISMATCH",
                        "The request body does not match the approved one.");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("APPROVAL_BODY_MISMATCH"))
                .andExpect(jsonPath("$.failure.httpStatus").value(403));
        assertThat(GATEWAY.requests("POST", "/api/orders").getFirst().bodyText()).contains("\"expectedTotal\":1.00");
    }

    @Test
    void anUnreachableOrSlowTargetFailsAsTargetUnavailable() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView down = proposeOrder(customer, "285.00");
        GATEWAY.on("POST", "/api/orders", request -> FakeGateway.Response.problem(503, "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable. Please try again."));
        mvc.perform(post("/api/approvals/{id}/approve", down.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("TARGET_UNAVAILABLE"))
                .andExpect(jsonPath("$.failure.httpStatus").value(503));

        ApprovalView slow = proposeOrder(customer, "190.00");
        GATEWAY.on("POST", "/api/orders", request -> FakeGateway.Response.json(201, "{}")
                .delayed(Duration.ofSeconds(4)));                       // execution timeout is 2 s in the tests
        mvc.perform(post("/api/approvals/{id}/approve", slow.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("TARGET_UNAVAILABLE"))
                .andExpect(jsonPath("$.failure.httpStatus").doesNotExist());
        assertThat(event(slow.id(), "ApprovalFailed").get("httpStatus").isNull()).isTrue();
    }

    @Test
    void theJwksPublishesOneRs256SigningKey() throws Exception {
        List<com.nimbusds.jose.jwk.JWK> keys = jwks().getKeys();
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst().getKeyUse().identifier()).isEqualTo("sig");
        assertThat(keys.getFirst().getAlgorithm().getName()).isEqualTo("RS256");
    }
}
