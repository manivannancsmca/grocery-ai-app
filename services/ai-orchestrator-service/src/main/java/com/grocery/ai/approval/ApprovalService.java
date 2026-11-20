package com.grocery.ai.approval;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.gateway.CallContext;
import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Approval requests (contract §4): proposal with duplicate detection, the decision rules (eligible deciders,
 * four-eyes), exactly-once execution and the views.
 *
 * <p><b>Exactly once.</b> {@code approve} moves PENDING → APPROVED → EXECUTING in one short transaction under the
 * optimistic lock ({@code @Version}): of two simultaneous approvals one commits, the other gets 409
 * APPROVAL_NOT_PENDING. Only the winner calls the target, outside any transaction, and records the outcome in a second
 * transaction. The target additionally refuses a reused approval (single-use {@code jti}).
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final TypeReference<List<ApprovalView.Line>> LINES = new TypeReference<>() {
    };

    private final ApprovalRepository approvals;
    private final ApprovalEvents events;
    private final ApprovalExecutor executor;
    private final ProposalBuilder proposals;
    private final AiProperties properties;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;
    private final ExportQueries exports;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, ApprovalEvents events, ApprovalExecutor executor,
                           ProposalBuilder proposals, AiProperties properties, JsonMapper jsonMapper,
                           PlatformTransactionManager transactionManager, ExportQueries exports, Clock clock) {
        this.approvals = approvals;
        this.events = events;
        this.executor = executor;
        this.proposals = proposals;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.exports = exports;
        this.clock = clock;
    }

    /** Who proposes, from which conversation turn. */
    public record Origin(String agent, UUID conversationId, UUID messageId, CurrentUser requester, String rationale) {
    }

    // ---- proposal -----------------------------------------------------------------------------------------------

    /**
     * Stores the request (PENDING_APPROVAL) and publishes ApprovalRequested, or returns the requester's pending
     * request with the same action and body hash (duplicate).
     */
    public ApprovalView propose(Origin origin, ProposalDraft draft) {
        String body = canonicalJson(draft);
        String hash = sha256Hex(body);
        UUID requester = origin.requester().userId();
        try {
            ApprovalRequest stored = transaction.execute(status -> {
                Instant now = clock.instant();
                Optional<ApprovalRequest> pending = approvals.findFirstByRequestedByAndActionAndBodySha256AndStatus(
                        requester, draft.action(), hash, ApprovalStatus.PENDING_APPROVAL);
                if (pending.isPresent()) {
                    ApprovalRequest existing = pending.get();
                    if (!existing.isExpiredAt(now)) {
                        log.info("Duplicate proposal of {} by {}: returning pending approval {}", draft.action(),
                                requester, existing.getId());
                        return existing;
                    }
                    existing.expire(now);
                    approvals.saveAndFlush(existing);
                    events.decided(existing, ApprovalStatus.EXPIRED.name());
                }
                // self-decided (a customer's purchase or return, a store owner's own change): the requester is
                // the one decider, as for PLACE_ORDER; otherwise anyone with a decider role
                boolean selfDecided = draft.action().isSelfDecided();
                ApprovalRequest request = ApprovalRequest.propose(Ids.newId(), draft.action(), origin.agent(),
                        origin.conversationId(), origin.messageId(), requester,
                        String.join(",", origin.requester().roles().stream().sorted().toList()),
                        draft.onBehalfOf(), selfDecided ? requester : null,
                        draft.action().deciderRoles().stream().sorted().toList(), proposals.fourEyes(draft),
                        draft.method(), draft.path(), body, hash, truncate(draft.title(), 200),
                        jsonMapper.writeValueAsString(draft.lines()), draft.amount(),
                        draft.amount() == null ? null : draft.currency(), truncate(origin.rationale(), 500), now,
                        now.plus(selfDecided ? properties.approvals().ttlCustomer()
                                : properties.approvals().ttlAdmin()));
                approvals.saveAndFlush(request);
                events.requested(request);
                log.info("Approval {} proposed: {} by {} ({}), expires {}", request.getId(), request.getAction(),
                        requester, origin.agent(), request.getExpiresAt());
                return request;
            });
            return view(stored, origin.requester());
        } catch (DataIntegrityViolationException race) {
            // the same proposal committed concurrently (unique pending key): return that one
            return approvals.findFirstByRequestedByAndActionAndBodySha256AndStatus(requester, draft.action(), hash,
                            ApprovalStatus.PENDING_APPROVAL)
                    .map(existing -> view(existing, origin.requester()))
                    .orElseThrow(() -> race);
        }
    }

    /** The exact body bytes: compact JSON, keys sorted, money with 2 decimals. */
    String canonicalJson(ProposalDraft draft) {
        return jsonMapper.writeValueAsString(draft.body());
    }

    static String sha256Hex(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- queries ------------------------------------------------------------------------------------------------

    /** {@code scope=mine} (requested by me or for me) or {@code to-decide} (pending ones I may decide). */
    public PageResponse<ApprovalView> list(CurrentUser user, String scope, String status, Integer page, Integer size) {
        return Pages.toResponse(approvals.findAll(listFilter(user, scope, status),
                        Pages.request(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))),
                approval -> view(approval, user));
    }

    /**
     * {@code GET /api/approvals/export}: exactly the requests the caller's list shows ({@link #listFilter}: the same
     * scope, status and decider rules), every page, newest first or by {@code sort}, in keyset batches (no
     * transaction here: each batch has its own).
     */
    public RowSource<ApprovalView> listExport(CurrentUser user, String scope, String status, String sort) {
        return exports.specification(approvals, listFilter(user, scope, status))
                .orderBy(ApprovalExports.SORT.resolve(sort))
                .rows(approval -> view(approval, user));
    }

    /**
     * The list's filter (shared by the list and its export): {@code mine} = requested by or for the caller (any
     * status, or {@code status}); {@code to-decide} = pending, not expired, and the caller may decide it (the named
     * approver or one of the approver roles, never the requester of a four-eyes request). Anything else is 400.
     */
    private Specification<ApprovalRequest> listFilter(CurrentUser user, String scope, String status) {
        String effectiveScope = scope == null || scope.isBlank() ? "mine" : scope.trim().toLowerCase(Locale.ROOT);
        ApprovalStatus statusFilter = parseStatus(status);
        Instant now = clock.instant();
        return switch (effectiveScope) {
            case "mine" -> (root, query, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                predicates.add(cb.or(cb.equal(root.get("requestedBy"), user.userId()),
                        cb.equal(root.get("onBehalfOf"), user.userId())));
                if (statusFilter != null) {
                    predicates.add(cb.equal(root.get("status"), statusFilter));
                }
                return cb.and(predicates.toArray(Predicate[]::new));
            };
            case "to-decide" -> (root, query, cb) -> {
                List<Predicate> deciders = new ArrayList<>();
                deciders.add(cb.equal(root.get("approverUserId"), user.userId()));
                for (String role : user.roles()) {
                    deciders.add(cb.and(cb.isNull(root.get("approverUserId")),
                            cb.like(cb.concat(cb.concat(",", root.get("approverRoles")), ","), "%," + role + ",%")));
                }
                return cb.and(
                        cb.equal(root.get("status"), ApprovalStatus.PENDING_APPROVAL),
                        cb.greaterThan(root.get("expiresAt"), now),
                        cb.or(deciders.toArray(Predicate[]::new)),
                        cb.not(cb.and(cb.isTrue(root.get("fourEyes")),
                                cb.equal(root.get("requestedBy"), user.userId()))));
            };
            default -> throw ApprovalException.invalidParameter("Unknown scope '" + scope
                    + "' (use mine or to-decide)");
        };
    }

    public ApprovalView get(CurrentUser user, UUID id) {
        return view(visible(user, id), user);
    }

    /** The approvals proposed in these assistant messages, by message (conversation view). */
    public Map<UUID, List<ApprovalView>> byMessage(CurrentUser user, List<UUID> messageIds) {
        Map<UUID, List<ApprovalView>> byMessage = new LinkedHashMap<>();
        if (!messageIds.isEmpty()) {
            for (ApprovalRequest approval : approvals.findByMessageIdInOrderByCreatedAt(messageIds)) {
                byMessage.computeIfAbsent(approval.getMessageId(), id -> new ArrayList<>()).add(view(approval, user));
            }
        }
        return byMessage;
    }

    // ---- decisions ----------------------------------------------------------------------------------------------

    /** Approve and execute synchronously with the approver's authority; answers the view after execution. */
    public ApprovalView approve(CurrentUser user, CallContext context, UUID id, String comment) {
        ApprovalRequest approval = visible(user, id);
        requireDecider(user, approval);
        ApprovalRequest executing = decide(approval.getId(), current -> {
            current.approveAndStartExecution(user.userId(), comment, clock.instant());
            approvals.saveAndFlush(current);
            events.decided(current, ApprovalStatus.APPROVED.name());
            return current;
        });
        log.info("Approval {} ({}) approved by {}: executing {} {}", id, executing.getAction(), user.userId(),
                executing.getHttpMethod(), executing.getHttpPath());

        ApprovalExecutor.Outcome outcome = executor.execute(executing, user.userId(), context);

        ApprovalRequest finished = transaction.execute(status -> {
            ApprovalRequest current = approvals.findById(id).orElseThrow();
            if (current.getStatus() != ApprovalStatus.EXECUTING) {
                log.warn("Approval {} is {} after its execution ({}): keeping that state", id, current.getStatus(),
                        outcome);
                return current;
            }
            Instant now = clock.instant();
            switch (outcome) {
                case ApprovalExecutor.Outcome.Completed completed -> {
                    current.complete(completed.resultJson(), completed.reference(), now);
                    approvals.saveAndFlush(current);
                    events.executed(current);
                }
                case ApprovalExecutor.Outcome.Failed failed -> {
                    current.fail(failed.code(), failed.message(), failed.httpStatus(), now);
                    approvals.saveAndFlush(current);
                    events.failed(current);
                }
            }
            return current;
        });
        log.info("Approval {} ({}) {}", id, finished.getAction(), finished.getStatus()
                + (finished.getFailureCode() == null ? "" : " " + finished.getFailureCode()));
        return view(finished, user);
    }

    public ApprovalView reject(CurrentUser user, UUID id, String comment) {
        ApprovalRequest approval = visible(user, id);
        requireDecider(user, approval);
        ApprovalRequest rejected = decide(approval.getId(), current -> {
            current.reject(user.userId(), comment, clock.instant());
            approvals.saveAndFlush(current);
            events.decided(current, ApprovalStatus.REJECTED.name());
            return current;
        });
        return view(rejected, user);
    }

    /** By the requester or the customer it is for. */
    public ApprovalView cancel(CurrentUser user, UUID id) {
        ApprovalRequest approval = visible(user, id);
        if (!user.userId().equals(approval.getRequestedBy()) && !user.userId().equals(approval.getOnBehalfOf())) {
            throw ApprovalException.forbidden();
        }
        ApprovalRequest cancelled = decide(approval.getId(), current -> {
            current.cancel(user.userId(), clock.instant());
            approvals.saveAndFlush(current);
            events.decided(current, ApprovalStatus.CANCELLED.name());
            return current;
        });
        return view(cancelled, user);
    }

    @FunctionalInterface
    private interface Decision {
        ApprovalRequest apply(ApprovalRequest current);
    }

    /**
     * Applies a decision to the current row: not pending → 409 with the current status; past its expiry → marked
     * EXPIRED (ApprovalDecided) and 409 EXPIRED; lost the optimistic lock to a concurrent decision → 409 with the
     * winner's status.
     */
    private ApprovalRequest decide(UUID id, Decision decision) {
        Decided decided;
        try {
            decided = transaction.execute(status -> {
                ApprovalRequest current = approvals.findById(id).orElseThrow(ApprovalException::notFound);
                if (current.getStatus() != ApprovalStatus.PENDING_APPROVAL) {
                    throw ApprovalException.notPending(current.getStatus());
                }
                Instant now = clock.instant();
                if (current.isExpiredAt(now)) {
                    current.expire(now);
                    approvals.saveAndFlush(current);
                    events.decided(current, ApprovalStatus.EXPIRED.name());
                    return new Decided(current, true);
                }
                return new Decided(decision.apply(current), false);
            });
        } catch (OptimisticLockingFailureException lost) {
            ApprovalStatus now = approvals.findById(id).map(ApprovalRequest::getStatus)
                    .orElse(ApprovalStatus.PENDING_APPROVAL);
            log.info("Approval {}: concurrent decision lost (now {})", id, now);
            throw ApprovalException.notPending(now);
        }
        if (decided.expired()) {
            throw ApprovalException.notPending(ApprovalStatus.EXPIRED);    // the expiry itself is committed
        }
        return decided.approval();
    }

    private record Decided(ApprovalRequest approval, boolean expired) {
    }

    // ---- rules --------------------------------------------------------------------------------------------------

    /** Visible: requester, the customer it is for, an eligible decider, any admin for admin actions; else 404. */
    private ApprovalRequest visible(CurrentUser user, UUID id) {
        ApprovalRequest approval = approvals.findById(id).orElseThrow(ApprovalException::notFound);
        boolean visible = user.userId().equals(approval.getRequestedBy())
                || user.userId().equals(approval.getOnBehalfOf())
                || eligible(user, approval)
                || (approval.getAction().isAdminAction() && user.isAdmin());
        if (!visible) {
            throw ApprovalException.notFound();
        }
        return approval;
    }

    private static boolean eligible(CurrentUser user, ApprovalRequest approval) {
        if (approval.getApproverUserId() != null) {
            return approval.getApproverUserId().equals(user.userId());
        }
        return approval.getApproverRoles().stream().anyMatch(user::hasRole);
    }

    private static void requireDecider(CurrentUser user, ApprovalRequest approval) {
        if (!eligible(user, approval)) {
            throw ApprovalException.forbidden();
        }
        if (approval.isFourEyes() && user.userId().equals(approval.getRequestedBy())) {
            throw ApprovalException.fourEyesRequired();
        }
    }

    private boolean canDecide(CurrentUser user, ApprovalRequest approval) {
        return approval.getStatus() == ApprovalStatus.PENDING_APPROVAL
                && !approval.isExpiredAt(clock.instant())
                && eligible(user, approval)
                && !(approval.isFourEyes() && user.userId().equals(approval.getRequestedBy()));
    }

    private static ApprovalStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ApprovalStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApprovalException.invalidParameter("Unknown status '" + status + "'");
        }
    }

    // ---- view ---------------------------------------------------------------------------------------------------

    public ApprovalView view(ApprovalRequest a, CurrentUser user) {
        List<ApprovalView.Line> lines = jsonMapper.readValue(a.getSummaryLines(), LINES);
        JsonNode result = a.getResultJson() == null ? null : jsonMapper.readTree(a.getResultJson());
        ApprovalView.Failure failure = a.getFailureCode() == null ? null
                : new ApprovalView.Failure(a.getFailureCode(), a.getFailureMessage(), a.getFailureHttpStatus());
        return new ApprovalView(a.getId(), a.getAction(), a.getStatus(), a.getAgent(), a.getConversationId(),
                a.getRequestedBy(), a.getOnBehalfOf(), a.getApproverUserId(), a.getApproverRoles(), a.isFourEyes(),
                new ApprovalView.Summary(a.getTitle(), lines, a.getAmount(), a.getCurrency()), a.getRationale(),
                a.getBodySha256().substring(0, 8), user != null && canDecide(user, a), a.getCreatedAt(),
                a.getExpiresAt(), a.getDecidedBy(), a.getDecidedAt(), a.getDecisionComment(), a.getExecutedAt(),
                result, failure);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }
}
