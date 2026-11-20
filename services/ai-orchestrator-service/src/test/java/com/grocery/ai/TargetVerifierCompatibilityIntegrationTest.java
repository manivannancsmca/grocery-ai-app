package com.grocery.ai;

import com.grocery.ai.approval.ApprovalView;
import com.grocery.common.approval.ApprovalContext;
import com.grocery.common.approval.ApprovalTokenException;
import com.grocery.common.approval.ApprovalTokenVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tokens this service signs are accepted by the target side's verifier (grocery-common's
 * {@link ApprovalTokenVerifier}, contract §5) with our published JWKS — and a changed body, another method or path,
 * another audience or another user are refused.
 */
class TargetVerifierCompatibilityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void anOrderTokenVerifiesAtOrderServiceAndNowhereElse() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        GATEWAY.json("POST", "/api/orders", 201, Fixtures.json(Fixtures.order(UUID.randomUUID(), "GA-COMPAT1",
                "CREATED", true, "285.00")));
        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk());
        FakeGateway.Request call = GATEWAY.requests("POST", "/api/orders").getFirst();
        String token = call.header("X-Approval-Token");

        JWKSet jwks = JWKSet.parse(mvc.perform(get("/.well-known/approval-jwks.json")).andReturn().getResponse()
                .getContentAsString());
        ApprovalTokenVerifier orderService = new ApprovalTokenVerifier(new ImmutableJWKSet<>(jwks),
                "ai-orchestrator-service", "order-service", Duration.ofSeconds(5), Clock.systemUTC());

        ApprovalContext verified = orderService.verify(token, "POST", "/api/orders", call::body, customer.toString());
        assertThat(verified.approvalId()).isEqualTo(approval.id());
        assertThat(verified.action()).isEqualTo("PLACE_ORDER");
        assertThat(verified.approverId()).isEqualTo(customer);
        assertThat(verified.onBehalfOf()).isEqualTo(customer);

        byte[] tampered = call.bodyText().replace("285.00", "1.00").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> orderService.verify(token, "POST", "/api/orders", () -> tampered,
                customer.toString())).isInstanceOf(ApprovalTokenException.class);
        assertThatThrownBy(() -> orderService.verify(token, "POST", "/api/orders/x/cancel", call::body,
                customer.toString())).isInstanceOf(ApprovalTokenException.class);
        assertThatThrownBy(() -> orderService.verify(token, "POST", "/api/orders", call::body,
                UUID.randomUUID().toString())).isInstanceOf(ApprovalTokenException.class);
        ApprovalTokenVerifier paymentService = new ApprovalTokenVerifier(new ImmutableJWKSet<>(jwks),
                "ai-orchestrator-service", "payment-service", Duration.ofSeconds(5), Clock.systemUTC());
        assertThatThrownBy(() -> paymentService.verify(token, "POST", "/api/orders", call::body,
                customer.toString())).isInstanceOf(ApprovalTokenException.class);
        ApprovalTokenVerifier later = new ApprovalTokenVerifier(new ImmutableJWKSet<>(jwks),
                "ai-orchestrator-service", "order-service", Duration.ofSeconds(5),
                Clock.offset(Clock.systemUTC(), Duration.ofSeconds(70)));
        assertThatThrownBy(() -> later.verify(token, "POST", "/api/orders", call::body, customer.toString()))
                .isInstanceOf(ApprovalTokenException.class);
    }

    @Test
    void aVendorsStockTokenVerifiesAtInventoryServiceWithoutObo() throws Exception {
        UUID vendor = UUID.randomUUID();
        String path = "/api/vendor/inventory/" + Fixtures.TOMATO + "/adjustments";
        java.util.TreeMap<String, Object> body = new java.util.TreeMap<>();
        body.put("quantity", 25);
        body.put("reason", "STOCK_COUNT");
        body.put("type", "SET");
        ApprovalView approval = approvals.propose(new com.grocery.ai.approval.ApprovalService.Origin("vendor", null,
                        null, user(vendor, "VENDOR"), null),
                new com.grocery.ai.approval.ProposalDraft(com.grocery.ai.approval.ApprovalAction.ADJUST_STOCK, null,
                        "POST", path, body, "Set stock of Tomato (500 g) to 25",
                        java.util.List.of(new ApprovalView.Line("Product", "Tomato (500 g)")), null, null));
        assertThat(approval.approverUserId()).isEqualTo(vendor);
        assertThat(approval.onBehalfOf()).isNull();
        GATEWAY.json("POST", path, 200, Fixtures.json(Fixtures.stock(Fixtures.TOMATO, "Tomato", 25, 0, 5)));
        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(vendor, "VENDOR")))
                .andExpect(status().isOk());
        FakeGateway.Request call = GATEWAY.requests("POST", path).getFirst();

        JWKSet jwks = JWKSet.parse(mvc.perform(get("/.well-known/approval-jwks.json")).andReturn().getResponse()
                .getContentAsString());
        ApprovalTokenVerifier inventoryService = new ApprovalTokenVerifier(new ImmutableJWKSet<>(jwks),
                "ai-orchestrator-service", "inventory-service", Duration.ofSeconds(5), Clock.systemUTC());
        ApprovalContext verified = inventoryService.verify(call.header("X-Approval-Token"), "POST", path, call::body,
                vendor.toString());
        assertThat(verified.action()).isEqualTo("ADJUST_STOCK");
        assertThat(verified.approverId()).isEqualTo(vendor);
        assertThat(verified.onBehalfOf()).isNull();
        ApprovalTokenVerifier vendorService = new ApprovalTokenVerifier(new ImmutableJWKSet<>(jwks),
                "ai-orchestrator-service", "vendor-service", Duration.ofSeconds(5), Clock.systemUTC());
        assertThatThrownBy(() -> vendorService.verify(call.header("X-Approval-Token"), "POST", path, call::body,
                vendor.toString())).isInstanceOf(ApprovalTokenException.class);
    }
}
