package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.notification.dispatch.DispatchPlan.PlannedNotification;
import com.grocery.notification.entity.AiApprovalRef;
import com.grocery.notification.entity.NotificationType;
import com.grocery.notification.repository.AiApprovalRefRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 11 §8: the four AI_* texts, the reader-dependent link, the agent labels, the audiences of the handler's plan
 * and the AI_APPROVALS_CHANGED signal, without Spring (the approver projection is a mock).
 */
class AiApprovalTemplatesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TITLE = "Place order: 2 items from Fresh Mart, total 285.00 INR";

    private final AiApprovalRefRepository approvals = mock(AiApprovalRefRepository.class);
    private final AiApprovalEventHandler handler = new AiApprovalEventHandler(approvals, JSON);

    // ---------------------------------------------------------------- templates

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "shopping  | 2026-11-13T09:05:00Z | Proposed by the Shopping assistant. Expires at 14:35.",
            "admin-bi  | 2026-11-13T18:29:59Z | Proposed by the Business insights assistant. Expires at 23:59.",
            "admin-bi  | 2026-11-13T18:30:00Z | Proposed by the Business insights assistant. Expires at 00:00.",
            "vendor    | 2026-06-30T23:59:59Z | Proposed by the Vendor assistant. Expires at 05:29.",
            "restaurant| 2026-11-13T09:05:00Z | Proposed by the Restaurant assistant. Expires at 14:35.",
            "delivery  | 2026-11-13T09:05:00Z | Proposed by the Delivery assistant. Expires at 14:35.",
            "other-bot | 2026-06-30T23:59:59Z | Proposed by the assistant. Expires at 05:29."
    })
    void approvalRequestedNamesTheAssistantAndTheExpiryInIndiaTime(String agent, String expiresAt, String body) {
        UUID id = UUID.randomUUID();
        NotificationDraft draft = NotificationTemplates.aiApprovalRequested(id, "PLACE_ORDER", agent, TITLE,
                Instant.parse(expiresAt), true);
        assertThat(draft.type()).isEqualTo(NotificationType.AI_APPROVAL_REQUESTED);
        assertThat(draft.type().severity()).isEqualTo(NotificationType.Severity.INFO);
        assertThat(draft.type().category()).isEqualTo(NotificationType.Category.APPROVAL);
        assertThat(draft.title()).isEqualTo("Approval needed: " + TITLE);
        assertThat(draft.body()).isEqualTo(body);
        assertThat(draft.link()).isEqualTo("/assistant/approvals?id=" + id);
        assertThat(draft.data()).containsExactly(Map.entry("approvalId", id.toString()),
                Map.entry("action", "PLACE_ORDER"));
    }

    @Test
    void theLinkDependsOnTheReaderAndAMissingExpiryOrTitleIsLeftOut() {
        UUID id = UUID.randomUUID();
        NotificationDraft admin = NotificationTemplates.aiApprovalRequested(id, "REFUND_PAYMENT", "admin-bi", null,
                null, false);
        assertThat(admin.link()).isEqualTo("/admin/ai/approvals?id=" + id);
        assertThat(admin.title()).isEqualTo("Approval needed: an assistant action");
        assertThat(admin.body()).isEqualTo("Proposed by the Business insights assistant.");
        assertThat(NotificationTemplates.agentLabel("shopping")).isEqualTo("Shopping");
        assertThat(NotificationTemplates.agentLabel("admin-bi")).isEqualTo("Business insights");
        assertThat(NotificationTemplates.agentLabel(null)).isNull();
    }

    @Test
    void decidedExecutedAndFailedTexts() {
        UUID id = UUID.randomUUID();
        NotificationDraft rejected = NotificationTemplates.aiApprovalRejected(id, "REFUND_PAYMENT", "Refund ₹10.00",
                "  Not this one  ", false);
        assertThat(rejected.type()).isEqualTo(NotificationType.AI_APPROVAL_DECIDED);
        assertThat(rejected.type().severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(rejected.title()).isEqualTo("Not approved: Refund ₹10.00");
        assertThat(rejected.body()).isEqualTo("Not this one");
        assertThat(rejected.link()).isEqualTo("/admin/ai/approvals?id=" + id);
        assertThat(NotificationTemplates.aiApprovalRejected(id, "PLACE_ORDER", TITLE, null, true).body()).isEmpty();

        NotificationDraft expired = NotificationTemplates.aiApprovalExpired(id, "PLACE_ORDER", TITLE, true);
        assertThat(expired.type()).isEqualTo(NotificationType.AI_APPROVAL_DECIDED);
        assertThat(expired.title()).isEqualTo("Expired: " + TITLE);
        assertThat(expired.body()).isEqualTo("Nobody decided in time.");
        assertThat(expired.link()).isEqualTo("/assistant/approvals?id=" + id);

        NotificationDraft done = NotificationTemplates.aiActionCompleted(id, "PLACE_ORDER", TITLE, "GA-7K3F9Q2M", true);
        assertThat(done.type()).isEqualTo(NotificationType.AI_ACTION_COMPLETED);
        assertThat(done.type().severity()).isEqualTo(NotificationType.Severity.SUCCESS);
        assertThat(done.title()).isEqualTo("Done: " + TITLE);
        assertThat(done.body()).isEqualTo("GA-7K3F9Q2M");
        assertThat(NotificationTemplates.aiActionCompleted(id, "PLACE_ORDER", TITLE, null, true).body()).isEmpty();

        NotificationDraft failed = NotificationTemplates.aiActionFailed(id, "PLACE_ORDER", TITLE,
                "The order total changed from 285.00 to 290.00. Please review it.", "TOTAL_CHANGED", true);
        assertThat(failed.type()).isEqualTo(NotificationType.AI_ACTION_FAILED);
        assertThat(failed.type().severity()).isEqualTo(NotificationType.Severity.ERROR);
        assertThat(failed.title()).isEqualTo("Could not complete: " + TITLE);
        assertThat(failed.body()).isEqualTo("The order total changed from 285.00 to 290.00. Please review it.");
        assertThat(NotificationTemplates.aiActionFailed(id, "PLACE_ORDER", TITLE, " ", "CART_CHANGED", true).body())
                .isEqualTo("CART_CHANGED");
        assertThat(failed.data()).containsExactly(Map.entry("approvalId", id.toString()),
                Map.entry("action", "PLACE_ORDER"));
    }

    @Test
    void textsLongerThanTheColumnsAreShortened() {
        String longTitle = "x".repeat(400);
        NotificationDraft draft = NotificationTemplates.aiActionFailed(UUID.randomUUID(), "PLACE_ORDER", longTitle,
                "y".repeat(2000), "AI_ERROR", true);
        assertThat(draft.title()).hasSize(NotificationTemplates.MAX_TITLE).endsWith("x…");
        assertThat(draft.body()).hasSize(NotificationTemplates.MAX_BODY).endsWith("y…");
        assertThat(NotificationTemplates.shorten("short", 10)).isEqualTo("short");
        assertThat(NotificationTemplates.shorten("abcd😀efgh", 6)).isEqualTo("abcd…");   // no half emoji
    }

    // ---------------------------------------------------------------- the handler's plan

    @Test
    void aCustomersOrderAsksTheCustomerOnTheCustomerPage() {
        UUID customer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DispatchPlan plan = fresh();
        handler.handle(event("ApprovalRequested", payload(id, "PLACE_ORDER", "shopping", customer, customer,
                "approverUserId", customer, "approverRoles", List.of(), "expiresAt", "2026-11-13T09:05:00Z")), plan);

        verify(approvals).upsert(new AiApprovalRef(id, customer, List.of()), plan.now());
        PlannedNotification asked = single(plan);
        assertThat(asked.draft().type()).isEqualTo(NotificationType.AI_APPROVAL_REQUESTED);
        assertThat(asked.draft().link()).isEqualTo("/assistant/approvals?id=" + id);
        assertThat(asked.audiences()).containsExactly("user:" + customer);
        assertThat(plan.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.type()).isEqualTo(SignalType.AI_APPROVALS_CHANGED);
            assertThat(signal.data()).containsExactly(Map.entry("approvalId", id.toString()));
            assertThat(signal.audiences()).containsExactly("user:" + customer);   // requester = customer = approver
        });
    }

    @Test
    void anAdminActionAsksTheApproverRolesOnceOnTheAdminPage() {
        UUID ops = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DispatchPlan plan = fresh();
        handler.handle(event("ApprovalRequested", payload(id, "REFUND_PAYMENT", "admin-bi", ops, null,
                "approverUserId", null, "approverRoles", List.of("SUPER_ADMIN", "FINANCE_ADMIN", "FINANCE_ADMIN",
                        "not a role"), "expiresAt", "2026-11-13T09:05:00Z")), plan);

        verify(approvals).upsert(new AiApprovalRef(id, null, List.of("FINANCE_ADMIN", "SUPER_ADMIN")), plan.now());
        PlannedNotification asked = single(plan);
        assertThat(asked.draft().link()).isEqualTo("/admin/ai/approvals?id=" + id);
        assertThat(asked.draft().body()).isEqualTo("Proposed by the Business insights assistant. Expires at 14:35.");
        assertThat(asked.audiences()).containsExactly("role:FINANCE_ADMIN", "role:SUPER_ADMIN");
        assertThat(plan.signals()).singleElement().satisfies(signal -> assertThat(signal.audiences())
                .containsExactly("user:" + ops, "role:FINANCE_ADMIN", "role:SUPER_ADMIN"));
    }

    @Test
    void aNamedApproverWhoIsNotTheCustomerGetsTheAdminPage() {
        UUID customer = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DispatchPlan plan = fresh();
        handler.handle(event("ApprovalRequested", payload(id, "CANCEL_ORDER", "shopping", customer, customer,
                "approverUserId", approver, "approverRoles", List.of(), "expiresAt", "2026-11-13T09:05:00Z")), plan);
        assertThat(single(plan).draft().link()).isEqualTo("/admin/ai/approvals?id=" + id);
        assertThat(single(plan).audiences()).containsExactly("user:" + approver);

        DispatchPlan admin = fresh();
        handler.handle(event("ApprovalRequested", payload(id, "SUSPEND_VENDOR", "admin-bi", customer, null,
                "approverUserId", approver, "approverRoles", List.of(), "expiresAt", "2026-11-13T09:05:00Z")), admin);
        assertThat(single(admin).draft().link()).isEqualTo("/admin/ai/approvals?id=" + id);
    }

    @Test
    void laterEventsGoToTheRequesterAndTheCustomerEachOnTheirOwnPage() {
        UUID admin = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(approvals.find(id)).thenReturn(Optional.of(new AiApprovalRef(id, null, List.of("SUPPORT"))));
        DispatchPlan plan = fresh();
        handler.handle(event("ApprovalExecuted", payload(id, "CANCEL_ORDER", "admin-bi", admin, customer,
                "resultReference", "GA-7K3F9Q2M")), plan);

        assertThat(plan.notifications()).hasSize(2);
        assertThat(plan.notifications()).anySatisfy(n -> {
            assertThat(n.audiences()).containsExactly("user:" + admin);
            assertThat(n.draft().link()).isEqualTo("/admin/ai/approvals?id=" + id);
        });
        assertThat(plan.notifications()).anySatisfy(n -> {
            assertThat(n.audiences()).containsExactly("user:" + customer);
            assertThat(n.draft().link()).isEqualTo("/assistant/approvals?id=" + id);
        });
        assertThat(plan.signals()).singleElement().satisfies(signal -> assertThat(signal.audiences())
                .containsExactly("user:" + admin, "user:" + customer, "role:SUPPORT"));
    }

    @Test
    void theSameUserAsRequesterAndCustomerIsToldOnce() {
        UUID customer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(approvals.find(id)).thenReturn(Optional.of(new AiApprovalRef(id, customer, List.of())));
        for (String[] step : List.of(new String[]{"ApprovalDecided", "REJECTED"},
                new String[]{"ApprovalDecided", "EXPIRED"}, new String[]{"ApprovalExecuted", null},
                new String[]{"ApprovalFailed", null})) {
            DispatchPlan plan = fresh();
            handler.handle(event(step[0], payload(id, "PLACE_ORDER", "shopping", customer, customer,
                    "decision", step[1], "comment", "Not now", "errorCode", "CART_CHANGED")), plan);
            PlannedNotification told = single(plan);
            assertThat(told.audiences()).as(step[0]).containsExactly("user:" + customer);
            assertThat(told.draft().link()).isEqualTo("/assistant/approvals?id=" + id);
            assertThat(plan.signals()).singleElement().satisfies(signal ->
                    assertThat(signal.audiences()).containsExactly("user:" + customer));
        }
    }

    @Test
    void approvedAndCancelledOnlySignal() {
        UUID customer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(approvals.find(id)).thenReturn(Optional.of(new AiApprovalRef(id, customer, List.of())));
        for (String decision : List.of("APPROVED", "CANCELLED")) {
            DispatchPlan plan = fresh();
            handler.handle(event("ApprovalDecided", payload(id, "PLACE_ORDER", "shopping", customer, customer,
                    "decision", decision)), plan);
            assertThat(plan.notifications()).as(decision).isEmpty();
            assertThat(plan.signals()).singleElement().satisfies(signal ->
                    assertThat(signal.type()).isEqualTo(SignalType.AI_APPROVALS_CHANGED));
        }
    }

    @Test
    void anUnknownRequestSignalsTheRequesterAndCustomerOnly() {
        UUID admin = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(approvals.find(id)).thenReturn(Optional.empty());
        DispatchPlan plan = fresh();
        handler.handle(event("ApprovalFailed", payload(id, "REFUND_PAYMENT", "admin-bi", admin, null,
                "errorCode", "TARGET_UNAVAILABLE", "errorMessage", "Payments are not reachable right now.")), plan);
        assertThat(single(plan).audiences()).containsExactly("user:" + admin);
        assertThat(single(plan).draft().body()).isEqualTo("Payments are not reachable right now.");
        assertThat(plan.signals()).singleElement().satisfies(signal ->
                assertThat(signal.audiences()).containsExactly("user:" + admin));
    }

    @Test
    void oldEventsOnlyUpdateTheProjection() {
        UUID customer = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DispatchPlan old = new DispatchPlan(false, Instant.now());
        handler.handle(event("ApprovalRequested", payload(id, "PLACE_ORDER", "shopping", customer, customer,
                "approverUserId", customer, "approverRoles", List.of(), "expiresAt", "2026-11-13T09:05:00Z")), old);
        verify(approvals).upsert(any(), any());
        assertThat(old.isEmpty()).isTrue();

        DispatchPlan oldExecuted = new DispatchPlan(false, Instant.now());
        handler.handle(event("ApprovalExecuted", payload(id, "PLACE_ORDER", "shopping", customer, customer,
                "resultReference", "GA-1")), oldExecuted);
        assertThat(oldExecuted.isEmpty()).isTrue();
        verify(approvals, never()).find(any());
    }

    @Test
    void onlyTheFourAiEventsAreHandledAndBrokenOnesAreInvalid() {
        assertThat(handler.supports("ai.events", "ApprovalRequested")).isTrue();
        assertThat(handler.supports("ai.events", "ApprovalDecided")).isTrue();
        assertThat(handler.supports("ai.events", "ApprovalExecuted")).isTrue();
        assertThat(handler.supports("ai.events", "ApprovalFailed")).isTrue();
        assertThat(handler.supports("ai.events", "ConversationStarted")).isFalse();
        assertThat(handler.supports("order.events", "ApprovalRequested")).isFalse();

        UUID user = UUID.randomUUID();
        for (String missing : List.of("approvalId", "action", "requestedBy")) {
            Map<String, Object> payload = payload(UUID.randomUUID(), "PLACE_ORDER", "shopping", user, user,
                    "resultReference", "GA-1");
            payload.put(missing, null);
            assertThatThrownBy(() -> handler.handle(event("ApprovalExecuted", payload), fresh()))
                    .as("without %s", missing).isInstanceOf(InvalidEventException.class);
        }
    }

    // ---------------------------------------------------------------- Phase 12 §6: links per deciding role

    @Test
    void thePagesOfTheDecidingRolesAndAssistants() {
        UUID id = UUID.randomUUID();
        assertThat(ApprovalPage.ofRole("VENDOR").link(id)).isEqualTo("/vendor/assistant?id=" + id);
        assertThat(ApprovalPage.ofRole("RESTAURANT_OWNER").link(id)).isEqualTo("/restaurant/assistant?id=" + id);
        assertThat(ApprovalPage.ofRole("DELIVERY_PARTNER").link(id)).isEqualTo("/delivery/assistant?id=" + id);
        assertThat(ApprovalPage.ofRole("DELIVERY_MANAGER").link(id)).isEqualTo("/delivery/assistant?id=" + id);
        assertThat(ApprovalPage.ofRole("CUSTOMER").link(id)).isEqualTo("/assistant/approvals?id=" + id);
        for (String admin : List.of("SUPER_ADMIN", "OPS_ADMIN", "FINANCE_ADMIN", "SUPPORT", "SOMETHING_NEW")) {
            assertThat(ApprovalPage.ofRole(admin).link(id)).as(admin).isEqualTo("/admin/ai/approvals?id=" + id);
        }
        assertThat(ApprovalPage.ofAgent("vendor")).isEqualTo(ApprovalPage.VENDOR);
        assertThat(ApprovalPage.ofAgent("restaurant")).isEqualTo(ApprovalPage.RESTAURANT);
        assertThat(ApprovalPage.ofAgent("delivery")).isEqualTo(ApprovalPage.DELIVERY);
        assertThat(ApprovalPage.ofAgent("shopping")).isNull();
        assertThat(ApprovalPage.ofAgent("admin-bi")).isNull();
        assertThat(ApprovalPage.ofAgent(null)).isNull();
        assertThat(NotificationTemplates.agentLabel("vendor")).isEqualTo("Vendor");
        assertThat(NotificationTemplates.agentLabel("restaurant")).isEqualTo("Restaurant");
        assertThat(NotificationTemplates.agentLabel("delivery")).isEqualTo("Delivery");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "vendor     | ADJUST_STOCK               | /vendor/assistant",
            "vendor     | CHANGE_PRICE               | /vendor/assistant",
            "restaurant | SET_MENU_ITEM_AVAILABILITY | /restaurant/assistant",
            "restaurant | ACCEPT_STORE_ORDER         | /restaurant/assistant",
            "delivery   | SOME_PARTNER_ACTION        | /delivery/assistant"
    })
    void aSelfDecidedActionOfTheNewAssistantsUsesTheDecidersOwnPage(String agent, String action, String page) {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        // approverUserId = requester (contract §6); onBehalfOf null — or, to be safe, the requester as well
        for (UUID onBehalfOf : java.util.Arrays.asList(null, owner)) {
            DispatchPlan asked = fresh();
            handler.handle(event("ApprovalRequested", payload(id, action, agent, owner, onBehalfOf,
                    "approverUserId", owner, "approverRoles", List.of(), "expiresAt", "2026-11-13T09:05:00Z")), asked);
            assertThat(single(asked).draft().link()).as("%s, onBehalfOf %s", agent, onBehalfOf)
                    .isEqualTo(page + "?id=" + id);
            assertThat(single(asked).audiences()).containsExactly("user:" + owner);

            when(approvals.find(id)).thenReturn(Optional.of(new AiApprovalRef(id, owner, List.of())));
            for (String[] step : List.of(new String[]{"ApprovalDecided", "REJECTED"},
                    new String[]{"ApprovalDecided", "EXPIRED"}, new String[]{"ApprovalExecuted", null},
                    new String[]{"ApprovalFailed", null})) {
                DispatchPlan later = fresh();
                handler.handle(event(step[0], payload(id, action, agent, owner, onBehalfOf, "decision", step[1],
                        "resultReference", "done", "errorCode", "TARGET_UNAVAILABLE")), later);
                assertThat(single(later).audiences()).as(step[0]).containsExactly("user:" + owner);
                assertThat(single(later).draft().link()).as(step[0]).isEqualTo(page + "?id=" + id);
            }
        }
    }

    @Test
    void aDeliveryReassignmentAsksTheManagersOnTheDeliveryPageAndTheAdminsOnTheAdminPage() {
        UUID manager = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DispatchPlan plan = fresh();
        handler.handle(event("ApprovalRequested", payload(id, "REASSIGN_DELIVERY", "delivery", manager, null,
                "approverUserId", null, "approverRoles", List.of("SUPER_ADMIN", "DELIVERY_MANAGER", "OPS_ADMIN"),
                "expiresAt", "2026-11-13T09:05:00Z")), plan);

        assertThat(plan.notifications()).hasSize(2);
        assertThat(plan.notifications()).anySatisfy(n -> {
            assertThat(n.audiences()).containsExactly("role:DELIVERY_MANAGER");
            assertThat(n.draft().link()).isEqualTo("/delivery/assistant?id=" + id);
            assertThat(n.draft().body()).isEqualTo("Proposed by the Delivery assistant. Expires at 14:35.");
        });
        assertThat(plan.notifications()).anySatisfy(n -> {
            assertThat(n.audiences()).containsExactly("role:OPS_ADMIN", "role:SUPER_ADMIN");   // one row for both
            assertThat(n.draft().link()).isEqualTo("/admin/ai/approvals?id=" + id);
        });
        assertThat(plan.signals()).singleElement().satisfies(signal -> assertThat(signal.audiences())
                .containsExactly("user:" + manager, "role:DELIVERY_MANAGER", "role:OPS_ADMIN", "role:SUPER_ADMIN"));

        // the requesting manager hears the outcome on the delivery page
        when(approvals.find(id)).thenReturn(Optional.of(new AiApprovalRef(id, null,
                List.of("DELIVERY_MANAGER", "OPS_ADMIN", "SUPER_ADMIN"))));
        DispatchPlan executed = fresh();
        handler.handle(event("ApprovalExecuted", payload(id, "REASSIGN_DELIVERY", "delivery", manager, null,
                "resultReference", "GA-7K3F9Q2M")), executed);
        assertThat(single(executed).audiences()).containsExactly("user:" + manager);
        assertThat(single(executed).draft().link()).isEqualTo("/delivery/assistant?id=" + id);
    }

    @Test
    void roleAddressedStoreAndPartnerDecidersGetTheirPages() {
        UUID requester = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DispatchPlan plan = fresh();
        handler.handle(event("ApprovalRequested", payload(id, "SOME_ACTION", "admin-bi", requester, null,
                "approverUserId", null, "approverRoles", List.of("VENDOR", "RESTAURANT_OWNER", "DELIVERY_PARTNER",
                        "FINANCE_ADMIN"), "expiresAt", "2026-11-13T09:05:00Z")), plan);
        Map<String, String> linkByAudience = new LinkedHashMap<>();
        plan.notifications().forEach(n -> n.audiences().forEach(a -> linkByAudience.put(a, n.draft().link())));
        assertThat(linkByAudience).containsExactlyInAnyOrderEntriesOf(Map.of(
                "role:VENDOR", "/vendor/assistant?id=" + id,
                "role:RESTAURANT_OWNER", "/restaurant/assistant?id=" + id,
                "role:DELIVERY_PARTNER", "/delivery/assistant?id=" + id,
                "role:FINANCE_ADMIN", "/admin/ai/approvals?id=" + id));
        // the admin-bi requester (not a new assistant) keeps the admin page
        assertThat(AiApprovalEventHandler.readerPage(requester, requester, null, "admin-bi"))
                .isEqualTo(ApprovalPage.ADMIN);
        // the shopping customer keeps the customer page
        assertThat(AiApprovalEventHandler.readerPage(requester, requester, requester, "shopping"))
                .isEqualTo(ApprovalPage.CUSTOMER);
        // a customer an action of a new assistant is for (not its requester) gets the customer page
        UUID customer = UUID.randomUUID();
        assertThat(AiApprovalEventHandler.readerPage(customer, requester, customer, "restaurant"))
                .isEqualTo(ApprovalPage.CUSTOMER);
    }

    // ---------------------------------------------------------------- helpers

    private static DispatchPlan fresh() {
        return new DispatchPlan(true, Instant.parse("2026-11-13T09:00:00Z"));
    }

    private static PlannedNotification single(DispatchPlan plan) {
        assertThat(plan.notifications()).hasSize(1);
        return plan.notifications().getFirst();
    }

    private static Map<String, Object> payload(UUID approvalId, String action, String agent, UUID requestedBy,
                                               UUID onBehalfOf, Object... extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("action", action);
        payload.put("agent", agent);
        payload.put("requestedBy", requestedBy);
        payload.put("onBehalfOf", onBehalfOf);
        payload.put("title", TITLE);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            payload.put((String) extra[i], extra[i + 1]);
        }
        return payload;
    }

    private static EventEnvelope event(String eventType, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, 1, "ApprovalRequest",
                String.valueOf(payload.get("approvalId")), null, "ai.events", JSON.writeValueAsString(payload));
    }
}
