package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.AiApprovalRef;
import com.grocery.notification.event.EventPayloads.ApprovalEvent;
import com.grocery.notification.repository.AiApprovalRefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * ai.events (Phase 11 contract §8), the assistants' approval requests:
 * <ul>
 *   <li>ApprovalRequested → {@code AI_APPROVAL_REQUESTED} · INFO to {@code approverUserId}, or else to the
 *       {@code approverRoles} (one row for all roles); it also records the approvers in the {@code ai_approval_ref}
 *       projection (old events too);</li>
 *   <li>ApprovalDecided REJECTED / EXPIRED → {@code AI_APPROVAL_DECIDED} · WARNING; APPROVED and CANCELLED notify
 *       nobody (APPROVED is followed by ApprovalExecuted or ApprovalFailed; a cancel is the reader's own act);</li>
 *   <li>ApprovalExecuted → {@code AI_ACTION_COMPLETED} · SUCCESS, ApprovalFailed → {@code AI_ACTION_FAILED} · ERROR;</li>
 *   <li>decided, executed and failed go to the requester and the customer it is for ({@code onBehalfOf}), each user
 *       once;</li>
 *   <li>every event → signal {@code AI_APPROVALS_CHANGED {approvalId}} to the requester, the customer it is for and the
 *       approver user or roles (for the later events, from the projection; an unknown request signals the requester
 *       and the customer only, logged).</li>
 * </ul>
 *
 * <p><b>Which link a reader gets</b> ({@link ApprovalPage}, every link with {@code ?id={id}}):
 * <ul>
 *   <li>Phase 12 §6: the requester of the vendor, restaurant and delivery assistants — and so the self-deciding
 *       {@code approverUserId}, who is that requester — gets their assistant's page: {@code /vendor/assistant},
 *       {@code /restaurant/assistant}, {@code /delivery/assistant};</li>
 *   <li>approver roles get their role's page: VENDOR → {@code /vendor/assistant}, RESTAURANT_OWNER →
 *       {@code /restaurant/assistant}, DELIVERY_PARTNER and DELIVERY_MANAGER → {@code /delivery/assistant}, every
 *       other (admin) role → {@code /admin/ai/approvals} — one notification per page (so REASSIGN_DELIVERY asks
 *       DELIVERY_MANAGER on the delivery page and OPS_ADMIN + SUPER_ADMIN, in one row, on the admin page);</li>
 *   <li>unchanged from Phase 11: the customer the action is for — the user {@code onBehalfOf} — gets the customer page
 *       {@code /assistant/approvals}; every other reader the admin page {@code /admin/ai/approvals} (a requester of
 *       the Business-insights assistant, a named approver who is neither the requester nor that customer).</li>
 * </ul>
 *
 * <p>The event-age rule applies as everywhere: an event older than {@code max-event-age} only updates the projection.
 * Missing {@code approvalId}, {@code action} or {@code requestedBy} → {@code InvalidEventException} (DLT); a missing
 * title falls back to "an assistant action".
 */
@Component
public class AiApprovalEventHandler implements EventHandler {

    static final String TOPIC = "ai.events";
    static final String REQUESTED = "ApprovalRequested";
    static final String DECIDED = "ApprovalDecided";
    static final String EXECUTED = "ApprovalExecuted";
    static final String FAILED = "ApprovalFailed";
    private static final Set<String> TYPES = Set.of(REQUESTED, DECIDED, EXECUTED, FAILED);
    private static final Logger log = LoggerFactory.getLogger(AiApprovalEventHandler.class);

    /** One notification text, for a reader of the given approvals page. */
    @FunctionalInterface
    private interface Draft {
        NotificationDraft forReader(ApprovalPage page);
    }

    private final AiApprovalRefRepository approvals;
    private final JsonMapper json;

    public AiApprovalEventHandler(AiApprovalRefRepository approvals, JsonMapper json) {
        this.approvals = approvals;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && TYPES.contains(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        ApprovalEvent approval = event.payloadAs(json, ApprovalEvent.class);
        UUID approvalId = Payloads.require(event, approval.approvalId(), "approvalId");
        String action = Payloads.require(event, approval.action(), "action");
        UUID requestedBy = Payloads.require(event, approval.requestedBy(), "requestedBy");
        boolean requested = REQUESTED.equals(event.eventType());
        AiApprovalRef approvers = null;
        if (requested) {
            approvers = new AiApprovalRef(approvalId, approval.approverUserId(),
                    approval.approverUserId() == null ? Audiences.wellFormedRoles(approval.approverRoles()) : List.of());
            approvals.upsert(approvers, plan.now());
        }
        if (!plan.fresh()) {
            return;                                     // old news: the projection only
        }
        if (!requested) {
            approvers = approvals.find(approvalId).orElse(null);
            if (approvers == null) {
                log.warn("Approval {} is unknown ({} {}): its approvers are not signalled", approvalId,
                        event.eventType(), event.eventId());
            }
        }
        UUID customer = approval.onBehalfOf();
        String title = approval.title();
        String agent = approval.agent();
        switch (event.eventType()) {
            case REQUESTED -> askApprovers(plan, approvers, requestedBy, customer, agent, page -> NotificationTemplates
                    .aiApprovalRequested(approvalId, action, agent, title, approval.expiresAt(), page));
            case DECIDED -> {
                switch (approval.decision() == null ? "" : approval.decision()) {
                    case "REJECTED" -> tellRequesterAndCustomer(plan, requestedBy, customer, agent, page ->
                            NotificationTemplates.aiApprovalRejected(approvalId, action, title, approval.comment(),
                                    page));
                    case "EXPIRED" -> tellRequesterAndCustomer(plan, requestedBy, customer, agent, page ->
                            NotificationTemplates.aiApprovalExpired(approvalId, action, title, page));
                    default -> {
                        // APPROVED (execution follows) and CANCELLED (the reader's own act): signal only
                    }
                }
            }
            case EXECUTED -> tellRequesterAndCustomer(plan, requestedBy, customer, agent, page ->
                    NotificationTemplates.aiActionCompleted(approvalId, action, title, approval.resultReference(),
                            page));
            case FAILED -> tellRequesterAndCustomer(plan, requestedBy, customer, agent, page ->
                    NotificationTemplates.aiActionFailed(approvalId, action, title, approval.errorMessage(),
                            approval.errorCode(), page));
        }
        plan.signal(SignalType.AI_APPROVALS_CHANGED, data("approvalId", approvalId),
                parties(requestedBy, customer, approvers));
    }

    /**
     * The approvals page of one user reader: the requester of the vendor / restaurant / delivery assistant gets that
     * assistant's page (Phase 12 §6); otherwise the customer the action is for gets the customer page and everybody
     * else the admin page (Phase 11).
     */
    static ApprovalPage readerPage(UUID reader, UUID requestedBy, UUID customer, String agent) {
        if (reader.equals(requestedBy)) {
            ApprovalPage own = ApprovalPage.ofAgent(agent);
            if (own != null) {
                return own;
            }
        }
        return reader.equals(customer) ? ApprovalPage.CUSTOMER : ApprovalPage.ADMIN;
    }

    /**
     * ApprovalRequested: the named approver (on their page, see {@link #readerPage}), else the approver roles — one
     * notification per page their roles use (all admin roles share one row).
     */
    private static void askApprovers(DispatchPlan plan, AiApprovalRef approvers, UUID requestedBy, UUID customer,
                                     String agent, Draft draft) {
        if (approvers.approverUserId() != null) {
            plan.notify(draft.forReader(readerPage(approvers.approverUserId(), requestedBy, customer, agent)),
                    List.of(Audiences.user(approvers.approverUserId())));
        } else if (!approvers.approverRoles().isEmpty()) {
            Map<ApprovalPage, List<String>> rolesByPage = new EnumMap<>(ApprovalPage.class);
            for (String role : approvers.approverRoles()) {
                rolesByPage.computeIfAbsent(ApprovalPage.ofRole(role), page -> new ArrayList<>()).add(role);
            }
            rolesByPage.forEach((page, roles) -> plan.notify(draft.forReader(page), Audiences.roles(roles)));
        } else {
            log.warn("Approval {} names no approver: nobody is asked to decide it", approvers.approvalId());
        }
    }

    /** The requester and the customer it is for, each once, each on their own page ({@link #readerPage}). */
    private static void tellRequesterAndCustomer(DispatchPlan plan, UUID requestedBy, UUID customer, String agent,
                                                 Draft draft) {
        Set<UUID> readers = new LinkedHashSet<>();
        readers.add(requestedBy);
        if (customer != null) {
            readers.add(customer);
        }
        for (UUID reader : readers) {
            plan.notify(draft.forReader(readerPage(reader, requestedBy, customer, agent)),
                    List.of(Audiences.user(reader)));
        }
    }

    /** Everyone whose approval screens show this request: requester, customer, approver user or roles. */
    private static List<String> parties(UUID requestedBy, UUID customer, AiApprovalRef approvers) {
        List<String> audiences = new ArrayList<>();
        audiences.add(Audiences.user(requestedBy));
        if (customer != null) {
            audiences.add(Audiences.user(customer));
        }
        if (approvers != null) {
            if (approvers.approverUserId() != null) {
                audiences.add(Audiences.user(approvers.approverUserId()));
            } else {
                audiences.addAll(Audiences.roles(approvers.approverRoles()));
            }
        }
        return audiences;
    }
}
