package com.grocery.ai;

import com.grocery.ai.approval.ApprovalView;
import com.grocery.ai.audit.AiMonitoringService;
import com.grocery.ai.audit.AiMonitoringService.ToolInvocationView;
import com.grocery.ai.audit.ToolInvocation;
import com.grocery.ai.audit.ToolInvocationRepository;
import com.grocery.ai.tool.InvocationStatus;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The AI exports: the admin audit list ({@code GET /api/admin/ai/tool-invocations/export}, its filters, its order or a
 * chosen one, the model's arguments only for support and operations admins) and the approval list
 * ({@code GET /api/approvals/export}: exactly what each caller's list shows — a customer's own requests, an admin's
 * queue).
 */
class AiExportIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Kolkata"));

    @Autowired
    private ToolInvocationRepository invocations;
    @Autowired
    private AiMonitoringService monitoring;

    private MockHttpServletResponse export(String path, RequestPostProcessor caller) throws Exception {
        return mvc.perform(get(path).with(caller)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private static List<String> csvLines(MockHttpServletResponse response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿");
        return Arrays.asList(body.substring(1).split("\r\n"));
    }

    private static <R> List<String> read(RowSource<R> rows, int batchSize, Function<R, Object> key) throws Exception {
        List<String> keys = new ArrayList<>();
        rows.forEachBatch(batchSize, batch -> {
            batch.forEach(row -> keys.add(String.valueOf(key.apply(row))));
            return true;
        });
        return keys;
    }

    // ---- the audit list -------------------------------------------------------------------------------------------

    private record Calls(Instant at, UUID approvalId) {
    }

    /** A cart look-up, an order waiting for approval (with the customer's own words) and a refused assistant. */
    private Calls threeCalls(UUID customer) {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(2));
        UUID approvalId = UUID.randomUUID();
        invocations.save(new ToolInvocation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), customer,
                "CUSTOMER", "shopping", "getCart", "{}", InvocationStatus.SUCCEEDED, null, "Cart: 2 items", 200, 120,
                null, null, at));
        invocations.save(new ToolInvocation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), customer,
                "CUSTOMER", "shopping", "placeOrder", "{\"note\":\"Call +91 98765 43210 at the gate\"}",
                InvocationStatus.APPROVAL_REQUIRED, null, "Waiting for your approval", null, 80, approvalId, null,
                at.plus(Duration.ofMinutes(1))));
        invocations.save(new ToolInvocation(UUID.randomUUID(), null, null, customer, "CUSTOMER", "admin-bi", null,
                null, InvocationStatus.BLOCKED, "AGENT_NOT_ALLOWED", "The assistant is not available to this user.",
                403, null, null, null, at.plus(Duration.ofMinutes(2))));
        return new Calls(at, approvalId);
    }

    @Test
    void theAuditExportFollowsTheListsFiltersAndOrder() throws Exception {
        UUID customer = UUID.randomUUID();
        Calls calls = threeCalls(customer);
        threeCalls(UUID.randomUUID());
        String path = "/api/admin/ai/tool-invocations/export?format=csv&userId=" + customer;

        MockHttpServletResponse response = export(path, as(UUID.randomUUID(), "SUPPORT"));
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment; filename=\"ai-tool-calls-");
        assertThat(response.getHeader("X-Export-Rows")).isEqualTo("3");
        List<String> lines = csvLines(response);
        assertThat(lines.getFirst()).isEqualTo("Time,Assistant,Tool,Arguments,User ID,Roles,Status,HTTP status,"
                + "Reason,Result,Latency (ms),Approval ID");
        assertThat(lines).containsExactly(lines.getFirst(),
                SHOWN.format(calls.at().plus(Duration.ofMinutes(2))) + ",admin-bi,,," + customer + ",Customer,Blocked,"
                        + "403,Agent not allowed,The assistant is not available to this user.,,",
                SHOWN.format(calls.at().plus(Duration.ofMinutes(1))) + ",shopping,placeOrder,"
                        + "\"{\"\"note\"\":\"\"Call +91 98765 43210 at the gate\"\"}\"," + customer
                        + ",Customer,Approval required,,,Waiting for your approval,80," + calls.approvalId(),
                SHOWN.format(calls.at()) + ",shopping,getCart,," + customer + ",Customer,Succeeded,200,,"
                        + "Cart: 2 items,120,");

        // the customer's own words are personal: finance gets the calls without them
        List<String> finance = csvLines(export(path, as(UUID.randomUUID(), "FINANCE_ADMIN")));
        assertThat(finance.getFirst()).isEqualTo("Time,Assistant,Tool,User ID,Roles,Status,HTTP status,Reason,Result,"
                + "Latency (ms),Approval ID");
        assertThat(finance).hasSize(4);
        assertThat(String.join("\n", finance)).doesNotContain("98765");

        List<String> blocked = csvLines(export(path + "&status=blocked", as(UUID.randomUUID(), "OPS_ADMIN")));
        assertThat(blocked).hasSize(2);
        assertThat(blocked.get(1)).contains(",admin-bi,");
        List<String> cart = csvLines(export(path + "&agent=shopping&tool=getCart&from=2026-01-01",
                as(UUID.randomUUID(), "SUPPORT")));
        assertThat(cart).hasSize(2);
        assertThat(cart.get(1)).contains(",getCart,");
        assertThat(csvLines(export(path + "&to=2026-01-01", as(UUID.randomUUID(), "SUPPORT")))).hasSize(1);

        // a chosen order (the page's column keys), then the id
        List<String> byStatus = csvLines(export(path + "&sort=status", as(UUID.randomUUID(), "SUPPORT")));
        assertThat(byStatus.subList(1, 4)).extracting(line -> line.split(",")[6])
                .containsExactly("Approval required", "Blocked", "Succeeded");
        List<String> oldestFirst = csvLines(export(path + "&sort=when,asc", as(UUID.randomUUID(), "SUPPORT")));
        assertThat(oldestFirst.get(1)).contains(",getCart,");
        assertThat(oldestFirst.get(3)).contains(",admin-bi,");

        // every batch size reads every call once, in the same order
        for (String sort : new String[]{null, "when", "when,desc", "user", "user,desc", "status", "status,desc"}) {
            List<String> whole = read(monitoring.invocationsExport(null, customer, null, null, null, null, sort), 1000,
                    ToolInvocationView::id);
            assertThat(whole).as(sort).hasSize(3).doesNotHaveDuplicates();
            assertThat(read(monitoring.invocationsExport(null, customer, null, null, null, null, sort), 1,
                    ToolInvocationView::id)).as(sort).isEqualTo(whole);
        }
    }

    @Test
    void onlyAdminsExportTheAuditListAndWrongParametersAreRefused() throws Exception {
        String path = "/api/admin/ai/tool-invocations/export";
        mvc.perform(get(path + "?format=csv").with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "?format=csv").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "?format=csv")).andExpect(status().isUnauthorized());
        mvc.perform(get(path + "?format=docx").with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get(path + "?format=csv&sort=latency").with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get(path + "?format=csv&status=STARTED").with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        UUID customer = UUID.randomUUID();
        threeCalls(customer);
        MockHttpServletResponse pdf = export(path + "?format=pdf&userId=" + customer,
                as(UUID.randomUUID(), "SUPER_ADMIN"));
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    // ---- approvals ------------------------------------------------------------------------------------------------

    private static List<String> ids(List<String> lines) {
        return lines.subList(1, lines.size()).stream().map(line -> line.substring(line.lastIndexOf(',') + 1)).toList();
    }

    @Test
    void eachCallerExportsExactlyTheApprovalsTheirListShows() throws Exception {
        UUID asha = UUID.randomUUID();
        UUID ravi = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        UUID otherFinance = UUID.randomUUID();
        ApprovalView ashas = proposeOrder(asha, "285.00");
        ApprovalView ravis = proposeOrder(ravi, "120.00");
        ApprovalView big = proposeRefund(requester, "FINANCE_ADMIN", "2500.00");      // four-eyes
        ApprovalView small = proposeRefund(requester, "FINANCE_ADMIN", "150.00");

        // a customer: only their own requests, whatever the scope
        List<String> mine = csvLines(export("/api/approvals/export?format=csv", as(asha, "CUSTOMER")));
        assertThat(mine.getFirst()).isEqualTo("Requested,Request,Action,Assistant,Amount (INR),Status,Four-eyes,"
                + "Requested by,Expires,Decided,Executed,Failure,Approval ID");
        assertThat(mine).hasSize(2);
        assertThat(mine.get(1)).isEqualTo(SHOWN.format(ashas.createdAt()) + ",Place order: 3 items from Fresh Mart,"
                + "Place order,shopping,285.00,Pending approval,No," + asha + "," + SHOWN.format(ashas.expiresAt())
                + ",,,," + ashas.id());
        assertThat(ids(csvLines(export("/api/approvals/export?format=csv&scope=to-decide", as(asha, "CUSTOMER")))))
                .containsExactly(ashas.id().toString());
        assertThat(ids(csvLines(export("/api/approvals/export?format=csv&status=COMPLETED", as(asha, "CUSTOMER")))))
                .isEmpty();
        assertThat(ids(csvLines(export("/api/approvals/export?format=csv", as(ravi, "CUSTOMER")))))
                .containsExactly(ravis.id().toString());

        // admins: the requester's own (newest first, or by title), another finance admin's queue
        List<String> requested = csvLines(export("/api/approvals/export?format=csv&scope=mine",
                as(requester, "FINANCE_ADMIN")));
        assertThat(ids(requested)).containsExactly(small.id().toString(), big.id().toString());
        assertThat(requested.get(2)).contains(",Refund payment,admin-bi,2500.00,Pending approval,Yes," + requester);
        assertThat(ids(csvLines(export("/api/approvals/export?format=csv&scope=mine&sort=request,desc",
                as(requester, "FINANCE_ADMIN"))))).containsExactly(big.id().toString(), small.id().toString());
        assertThat(ids(csvLines(export("/api/approvals/export?format=csv&scope=to-decide",
                as(requester, "FINANCE_ADMIN"))))).contains(small.id().toString())
                .doesNotContain(big.id().toString(), ashas.id().toString(), ravis.id().toString());

        MockHttpServletResponse queue = export("/api/approvals/export?format=csv&scope=to-decide",
                as(otherFinance, "FINANCE_ADMIN"));
        List<String> queueIds = ids(csvLines(queue));
        assertThat(queueIds).contains(big.id().toString(), small.id().toString())
                .doesNotContain(ashas.id().toString(), ravis.id().toString());
        JsonNode list = body(mvc.perform(get("/api/approvals").param("scope", "to-decide").param("size", "100")
                .with(as(otherFinance, "FINANCE_ADMIN"))).andExpect(status().isOk()));
        assertThat(queue.getHeader("X-Export-Rows")).isEqualTo(list.get("totalElements").asString());
        assertThat(queueIds).containsAll(list.get("content").valueStream().map(v -> v.get("id").asString()).toList());
        assertThat(ids(csvLines(export("/api/approvals/export?format=csv", as(otherFinance, "FINANCE_ADMIN")))))
                .isEmpty();
        assertThat(ids(csvLines(export("/api/approvals/export?format=csv&scope=to-decide",
                as(UUID.randomUUID(), "SUPPORT"))))).doesNotContain(big.id().toString(), small.id().toString());

        // every batch size reads every request once, in the same order
        CurrentUser finance = user(requester, "FINANCE_ADMIN");
        for (String sort : new String[]{null, "requested", "requested,desc", "request", "request,desc", "requester"}) {
            List<String> whole = read(approvals.listExport(finance, "mine", null, sort), 1000, ApprovalView::id);
            assertThat(whole).as(sort).hasSize(2).doesNotHaveDuplicates();
            assertThat(read(approvals.listExport(finance, "mine", null, sort), 1, ApprovalView::id)).as(sort)
                    .isEqualTo(whole);
        }
    }

    @Test
    void approvalExportParametersAreValidatedAndNeedAToken() throws Exception {
        UUID customer = UUID.randomUUID();
        proposeOrder(customer, "99.00");
        mvc.perform(get("/api/approvals/export?format=docx").with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/approvals/export?format=csv&sort=status").with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/approvals/export?format=csv&scope=everything").with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/approvals/export?format=csv&status=DONE").with(as(customer, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/approvals/export?format=csv")).andExpect(status().isUnauthorized());

        MockHttpServletResponse xlsx = export("/api/approvals/export?format=xlsx", as(customer, "CUSTOMER"));
        assertThat(xlsx.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(new String(xlsx.getContentAsByteArray(), 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }
}
