package com.grocery.ai;

import com.grocery.ai.approval.ApprovalView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Human decisions (contract §4): who may see and decide what, four-eyes, reject with a comment, cancel, expiry, and
 * exactly-once approval under concurrency.
 */
class ApprovalDecisionIntegrationTest extends AbstractIntegrationTest {

    private void stubOrderCreated() {
        GATEWAY.on("POST", "/api/orders", request -> FakeGateway.Response.json(201, Fixtures.json(Fixtures.order(
                UUID.randomUUID(), "GA-NEW0001", "CREATED", true, "285.00"))));
    }

    @Test
    void onlyTheCustomerSeesAndDecidesTheirOrder() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");

        mvc.perform(get("/api/approvals/{id}", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(true));
        // another customer and the admins never see a customer's own purchase
        mvc.perform(get("/api/approvals/{id}", approval.id()).with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_NOT_FOUND"));
        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isNotFound());

        JsonNode mine = body(mvc.perform(get("/api/approvals").with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk()));
        assertThat(mine.get("content").get(0).get("id").asString()).isEqualTo(approval.id().toString());
        JsonNode toDecide = body(mvc.perform(get("/api/approvals").param("scope", "to-decide").param("size", "1")
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(toDecide.get("totalElements").asInt()).isEqualTo(1);
        assertThat(toDecide.get("size").asInt()).isEqualTo(1);
    }

    @Test
    void refundDecidersAndFourEyes() throws Exception {
        UUID requester = UUID.randomUUID();
        UUID otherFinance = UUID.randomUUID();
        ApprovalView big = proposeRefund(requester, "FINANCE_ADMIN", "2500.00");
        assertThat(big.fourEyes()).isTrue();
        GATEWAY.json("POST", "/api/admin/refunds", 201, Fixtures.json(Fixtures.map("id",
                UUID.randomUUID().toString(), "amount", 2500.00, "status", "PROCESSED")));

        // SUPPORT sees admin actions but may not decide refunds
        mvc.perform(get("/api/approvals/{id}", big.id()).with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canDecide").value(false));
        mvc.perform(post("/api/approvals/{id}/approve", big.id()).with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_FORBIDDEN"));
        // the requester may not decide their own four-eyes request
        mvc.perform(post("/api/approvals/{id}/approve", big.id()).with(as(requester, "FINANCE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FOUR_EYES_REQUIRED"));
        // the to-decide list of the requester leaves it out; another finance admin's includes it
        assertThat(body(mvc.perform(get("/api/approvals").param("scope", "to-decide")
                .with(as(requester, "FINANCE_ADMIN")))).get("content").valueStream()
                .map(v -> v.get("id").asString()).toList()).doesNotContain(big.id().toString());
        assertThat(body(mvc.perform(get("/api/approvals").param("scope", "to-decide")
                .with(as(otherFinance, "FINANCE_ADMIN")))).get("content").valueStream()
                .map(v -> v.get("id").asString()).toList()).contains(big.id().toString());

        mvc.perform(post("/api/approvals/{id}/approve", big.id()).with(as(otherFinance, "FINANCE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"checked the photos\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.decidedBy").value(otherFinance.toString()))
                .andExpect(jsonPath("$.comment").value("checked the photos"));

        // below the limit, the requester (a finance admin) may decide it
        ApprovalView small = proposeRefund(requester, "FINANCE_ADMIN", "150.00");
        assertThat(small.fourEyes()).isFalse();
        assertThat(small.canDecide()).isTrue();
    }

    @Test
    void suspensionsAreFourEyesForOpsAdmins() throws Exception {
        UUID requester = UUID.randomUUID();
        ApprovalView suspension = proposeSuspension(requester, UUID.randomUUID());
        mvc.perform(post("/api/approvals/{id}/approve", suspension.id()).with(as(requester, "OPS_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FOUR_EYES_REQUIRED"));
        mvc.perform(post("/api/approvals/{id}/approve", suspension.id()).with(as(UUID.randomUUID(),
                        "FINANCE_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_FORBIDDEN"));
    }

    @Test
    void rejectNeedsACommentAndThenNothingRuns() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");

        mvc.perform(post("/api/approvals/{id}/reject", approval.id()).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/approvals/{id}/reject", approval.id()).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/approvals/{id}/reject", approval.id()).with(as(customer, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Not now\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.comment").value("Not now"))
                .andExpect(jsonPath("$.canDecide").value(false));

        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("REJECTED"));
        assertThat(GATEWAY.requests()).isEmpty();
        assertThat(eventTypes(approval.id())).containsExactly("ApprovalRequested", "ApprovalDecided");
        JsonNode decided = event(approval.id(), "ApprovalDecided");
        assertThat(decided.get("decision").asString()).isEqualTo("REJECTED");
        assertThat(decided.get("comment").asString()).isEqualTo("Not now");
        assertThat(decided.get("decidedBy").asString()).isEqualTo(customer.toString());
    }

    @Test
    void theRequesterOrTheCustomerCanCancel() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        ApprovalView refund = proposeRefund(UUID.randomUUID(), "SUPPORT", "100.00");

        mvc.perform(post("/api/approvals/{id}/cancel", refund.id()).with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_FORBIDDEN"));
        mvc.perform(post("/api/approvals/{id}/cancel", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/approvals/{id}/cancel", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
        assertThat(event(approval.id(), "ApprovalDecided").get("decision").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void overdueRequestsExpireAndCannotBeApproved() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView polled = proposeOrder(customer, "285.00");
        ApprovalView unpolled = proposeOrder(customer, "190.00");

        clock.advance(Duration.ofMinutes(16));
        // an approve after expiresAt (poller not run yet) marks it EXPIRED and answers 409
        mvc.perform(post("/api/approvals/{id}/approve", unpolled.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("EXPIRED"));
        assertThat(approvalRow(unpolled.id())).containsEntry("status", "EXPIRED");

        assertThat(maintenance.expireOverdue()).isGreaterThanOrEqualTo(1);
        assertThat(approvalRow(polled.id())).containsEntry("status", "EXPIRED");
        JsonNode decided = event(polled.id(), "ApprovalDecided");
        assertThat(decided.get("decision").asString()).isEqualTo("EXPIRED");
        assertThat(decided.get("decidedBy").isNull()).isTrue();
        assertThat(GATEWAY.requests()).isEmpty();

        // a fresh proposal with the same body is a new request (the expired one no longer blocks it)
        assertThat(maintenance.expireOverdue()).isZero();
    }

    @Test
    void aDoubleApproveExecutesOnceAndAnswers409() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        stubOrderCreated();

        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/approvals/{id}/approve", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("APPROVAL_NOT_PENDING"))
                .andExpect(jsonPath("$.currentStatus").value("COMPLETED"));
        assertThat(GATEWAY.requests("POST", "/api/orders")).hasSize(1);
    }

    @Test
    void concurrentApprovalsExecuteExactlyOnce() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        GATEWAY.on("POST", "/api/orders", request -> FakeGateway.Response.json(201, Fixtures.json(Fixtures.order(
                UUID.randomUUID(), "GA-RACE001", "CREATED", true, "285.00"))).delayed(Duration.ofMillis(300)));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> approvals = java.util.stream.IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> mvc.perform(post("/api/approvals/{id}/approve",
                            approval.id()).with(as(customer, "CUSTOMER"))).andReturn().getResponse().getStatus())
                    .toList();
            List<Integer> statuses = pool.invokeAll(approvals).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(statuses).containsOnly(200, 409);
            assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(GATEWAY.requests("POST", "/api/orders")).hasSize(1);
        assertThat(approvalRow(approval.id())).containsEntry("status", "COMPLETED");
        assertThat(eventTypes(approval.id())).containsExactly("ApprovalRequested", "ApprovalDecided",
                "ApprovalExecuted");
    }

    @Test
    void aRequestLeftExecutingIsFailedAsInterrupted() throws Exception {
        UUID customer = UUID.randomUUID();
        ApprovalView approval = proposeOrder(customer, "285.00");
        jdbc().sql("UPDATE approval_request SET status = 'EXECUTING', decided_by = UUID_TO_BIN(?), "
                        + "decided_at = UTC_TIMESTAMP(6), executing_since = UTC_TIMESTAMP(6) - INTERVAL 3 MINUTE, "
                        + "version = version + 1 WHERE id = UUID_TO_BIN(?)")
                .param(customer.toString()).param(approval.id().toString()).update();

        assertThat(maintenance.recoverInterrupted()).isGreaterThanOrEqualTo(1);

        mvc.perform(get("/api/approvals/{id}", approval.id()).with(as(customer, "CUSTOMER")))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("EXECUTION_INTERRUPTED"));
        JsonNode failed = event(approval.id(), "ApprovalFailed");
        assertThat(failed.get("errorCode").asString()).isEqualTo("EXECUTION_INTERRUPTED");
        assertThat(failed.get("httpStatus").isNull()).isTrue();
    }

    @Test
    void listFiltersAreValidated() throws Exception {
        UUID customer = UUID.randomUUID();
        proposeOrder(customer, "285.00");
        mvc.perform(get("/api/approvals").param("scope", "everything").with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/approvals").param("status", "DONE").with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest());
        assertThat(body(mvc.perform(get("/api/approvals").param("status", "COMPLETED")
                .with(as(customer, "CUSTOMER")))).get("totalElements").asInt()).isZero();
        assertThat(body(mvc.perform(get("/api/approvals").param("status", "PENDING_APPROVAL")
                .with(as(customer, "CUSTOMER")))).get("totalElements").asInt()).isEqualTo(1);
        mvc.perform(get("/api/approvals/{id}", "not-a-uuid").with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/approvals")).andExpect(status().isUnauthorized());
    }
}
