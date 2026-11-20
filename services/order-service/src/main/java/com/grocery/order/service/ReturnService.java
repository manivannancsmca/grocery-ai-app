package com.grocery.order.service;

import com.grocery.common.id.Ids;
import com.grocery.order.dto.ReturnDtos.ReturnLineRequest;
import com.grocery.order.dto.ReturnDtos.ReturnRequest;
import com.grocery.order.dto.ReturnDtos.ReturnView;
import com.grocery.order.dto.ReturnMapper;
import com.grocery.order.entity.CustomerOrder;
import com.grocery.order.entity.DeciderRole;
import com.grocery.order.entity.OrderReturn;
import com.grocery.order.entity.OrderType;
import com.grocery.order.entity.ReturnStatus;
import com.grocery.order.repository.OrderReturnRepository;
import com.grocery.order.settings.ReturnSettings;
import com.grocery.order.settings.ReturnSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Return requests and their decisions (docs/api/phase-12-post-mvp-features.md §3).
 *
 * <h2>Locking rule</h2>
 * Every change takes the order's row lock first ({@link OrderLocks}), then — for an existing return — the return's
 * ({@code SELECT … FOR UPDATE}), checks on the locked rows, changes, and writes the outbox event (and so the order's
 * event timeline) in the same transaction. So a request and a decision of the same order never interleave, and the
 * quantities a request sees are the committed ones. The escalation poller ({@link ReturnEscalationPoller}) locks only
 * REQUESTED return rows, with {@code SKIP LOCKED}: a store deciding one right now is skipped, and a decision waiting
 * for the poller's lock then sees ESCALATED (409 RETURN_NOT_PENDING for the store). The unique {@code open_order_id}
 * (one open request per order) and {@code return_number} keys are the database's second line of defence.
 * <ul>
 *   <li><b>Request</b> (the customer of the order): the rules of {@link ReturnRules} — 422 RETURN_NOT_ALLOWED (not
 *       delivered, window over, a request open, fully refunded, nothing left), 422 RETURN_QUANTITY_EXCEEDED — then
 *       REQUESTED with {@code decideBy = now + decision-timeout} + {@code ReturnRequested}.</li>
 *   <li><b>Store</b> (the order's vendor / restaurant): approve or reject a REQUESTED return; another store's → 404
 *       RETURN_NOT_FOUND, anything but REQUESTED (ESCALATED included) → 409 RETURN_NOT_PENDING.</li>
 *   <li><b>Admin</b>: approve or reject a REQUESTED or ESCALATED return.</li>
 *   <li>Approve → APPROVED + {@code ReturnApproved} (restock forced false for a restaurant order); reject → REJECTED +
 *       {@code ReturnRejected} with the comment (its quantities can be requested again).</li>
 *   <li><b>Refunded</b>: payment-service's {@code RefundCompleted} with the return's id → REFUNDED
 *       ({@link #refunded}, inside the event's transaction).</li>
 * </ul>
 */
@Service
public class ReturnService {

    static final int NUMBER_ATTEMPTS = 5;
    static final String RETURN_NUMBER_KEY = "uk_order_return_number";
    static final String OPEN_RETURN_KEY = "uk_order_return_open";
    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    /** What {@link #refunded} did: applied (with the timeline detail) or not (with the reason). */
    public record RefundMark(boolean applied, String detail) {
    }

    private final OrderLocks locks;
    private final OrderReturnRepository returns;
    private final OrderEventPublisher events;
    private final OrderNumbers numbers;
    private final ReturnSettingsService settings;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ReturnService(OrderLocks locks, OrderReturnRepository returns, OrderEventPublisher events,
                         OrderNumbers numbers, ReturnSettingsService settings,
                         PlatformTransactionManager transactionManager, Clock clock) {
        this.locks = locks;
        this.returns = returns;
        this.events = events;
        this.numbers = numbers;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---- the customer -------------------------------------------------------------------------------------------

    /** {@code POST /api/orders/{id}/returns}: the customer's own order only (another's → 404 ORDER_NOT_FOUND). */
    public ReturnView request(UUID customerId, UUID orderId, ReturnRequest request) {
        List<ReturnRules.Requested> lines = merged(request.items());
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        for (int attempt = 1; ; attempt++) {
            try {
                ReturnView created = transaction.execute(status -> {
                    CustomerOrder order = locks.lock(orderId);                       // 1. the order first
                    if (order == null || !order.getCustomerId().equals(customerId)) {
                        throw OrderException.orderNotFound();
                    }
                    List<OrderReturn> earlier = returns.findByOrderIdOrderByRequestedAtAscIdAsc(orderId);
                    Instant now = clock.instant();
                    ReturnSettings rules = settings.current();              // the admin settings in force now
                    ReturnRules.refusal(order, earlier, now, rules.returnWindow()).ifPresent(refusal -> {
                        throw OrderException.returnNotAllowed(refusal.message(), refusal.properties());
                    });
                    Map<UUID, Integer> remaining = ReturnRules.remaining(order, earlier);
                    List<Map<String, Object>> exceeded = ReturnRules.exceeded(remaining, lines);
                    if (!exceeded.isEmpty()) {
                        throw OrderException.returnQuantityExceeded(exceeded);
                    }
                    ReturnRules.Evaluated evaluated = ReturnRules.evaluate(order, remaining, lines);
                    OrderReturn requestRow = OrderReturn.request(Ids.newId(), numbers.nextReturn(), order,
                            evaluated.items(), evaluated.deliveryFeeRefund(), request.reason(), note,
                            now.plus(rules.storeDecisionTime()), now);
                    returns.saveAndFlush(requestRow);                                   // a taken number fails here
                    events.returnRequested(requestRow);
                    log.info("Return {} ({}) requested for order {}: {} line(s), refund {}, decide by {}",
                            requestRow.getReturnNumber(), requestRow.getId(), order.getOrderNumber(),
                            requestRow.getItems().size(), requestRow.getRefundAmount(), requestRow.getDecideBy());
                    return ReturnMapper.view(requestRow);
                });
                return created;
            } catch (DataIntegrityViolationException e) {
                if (violates(e, OPEN_RETURN_KEY)) {
                    // only possible without the order lock (it serialises requests); answered like the rule
                    throw OrderException.returnNotAllowed("A return of this order is still open", Map.of());
                }
                if (!violates(e, RETURN_NUMBER_KEY) || attempt >= NUMBER_ATTEMPTS) {
                    throw e;
                }
                log.warn("Return number taken; retrying with a new one (attempt {})", attempt);
            }
        }
    }

    // ---- the store ----------------------------------------------------------------------------------------------

    public ReturnView approveByStore(StoreRef store, UUID userId, UUID returnId, Boolean restock) {
        return decide(returnId, ofStore(store), ReturnStatus.REQUESTED::equals, request -> {
            request.approve(restockOf(request, restock), userId, DeciderRole.STORE, clock.instant());
            events.returnApproved(request);
        });
    }

    public ReturnView rejectByStore(StoreRef store, UUID userId, UUID returnId, String comment) {
        return decide(returnId, ofStore(store), ReturnStatus.REQUESTED::equals, request -> {
            request.reject(comment.strip(), userId, DeciderRole.STORE, clock.instant());
            events.returnRejected(request);
        });
    }

    // ---- admins -------------------------------------------------------------------------------------------------

    public ReturnView approveByAdmin(UUID adminId, UUID returnId, Boolean restock) {
        return decide(returnId, request -> true, ReturnStatus::isOpen, request -> {
            request.approve(restockOf(request, restock), adminId, DeciderRole.ADMIN, clock.instant());
            events.returnApproved(request);
        });
    }

    public ReturnView rejectByAdmin(UUID adminId, UUID returnId, String comment) {
        return decide(returnId, request -> true, ReturnStatus::isOpen, request -> {
            request.reject(comment.strip(), adminId, DeciderRole.ADMIN, clock.instant());
            events.returnRejected(request);
        });
    }

    // ---- payment-service refunded it ----------------------------------------------------------------------------

    /**
     * {@code RefundCompleted} with {@code returnId}, inside the event's transaction, which holds the order's lock:
     * APPROVED → REFUNDED. A return of another order, an unknown one, or one in any other status is left alone (the
     * reason goes to the timeline).
     */
    public RefundMark refunded(CustomerOrder order, UUID returnId, UUID refundId, Instant now) {
        OrderReturn request = returns.lockById(returnId).orElse(null);
        if (request == null || !request.getOrderId().equals(order.getId())) {
            return new RefundMark(false, "no return " + returnId + " of this order");
        }
        if (request.getStatus() != ReturnStatus.APPROVED) {
            return new RefundMark(false, "return " + request.getReturnNumber() + " is " + request.getStatus()
                    + ", not APPROVED");
        }
        request.refunded(refundId, now);
        log.info("Return {} of order {} refunded ({})", request.getReturnNumber(), order.getOrderNumber(),
                request.getRefundAmount());
        return new RefundMark(true, "return " + request.getReturnNumber() + " APPROVED → REFUNDED");
    }

    // ---- the rule -----------------------------------------------------------------------------------------------

    /**
     * Locks the return's order, then the return; 404 RETURN_NOT_FOUND unless {@code visible}, 409 RETURN_NOT_PENDING
     * unless {@code decidable}; then {@code change} (transition + event) and the view.
     */
    private ReturnView decide(UUID returnId, Predicate<OrderReturn> visible, Predicate<ReturnStatus> decidable,
                              java.util.function.Consumer<OrderReturn> change) {
        UUID orderId = returns.findOrderIdById(returnId).orElseThrow(OrderException::returnNotFound);
        return transaction.execute(status -> {
            locks.lock(orderId);                                                    // 1. the order first
            OrderReturn request = returns.lockById(returnId).orElseThrow(OrderException::returnNotFound);
            if (!visible.test(request)) {
                throw OrderException.returnNotFound();
            }
            if (!decidable.test(request.getStatus())) {
                throw OrderException.returnNotPending(request.getStatus());
            }
            ReturnStatus before = request.getStatus();
            change.accept(request);
            log.info("Return {} of order {}: {} → {} by {} {}", request.getReturnNumber(), request.getOrderNumber(),
                    before, request.getStatus(), request.getDecidedByRole(), request.getDecidedBy());
            return ReturnMapper.view(request);
        });
    }

    /** The store's own returns only: its type and id. */
    private static Predicate<OrderReturn> ofStore(StoreRef store) {
        return request -> request.getOrderType() == store.type() && request.getStoreId().equals(store.storeId());
    }

    /** A restaurant order never restocks; a grocery one as the decider says (absent = false). */
    private static boolean restockOf(OrderReturn request, Boolean restock) {
        return request.getOrderType() == OrderType.GROCERY && Boolean.TRUE.equals(restock);
    }

    /** The same item twice is added up; first-seen order kept. */
    static List<ReturnRules.Requested> merged(List<ReturnLineRequest> items) {
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (ReturnLineRequest item : items) {
            quantities.merge(item.itemId(), item.quantity(), Integer::sum);
        }
        List<ReturnRules.Requested> lines = new ArrayList<>();
        quantities.forEach((itemId, quantity) -> lines.add(new ReturnRules.Requested(itemId, quantity)));
        return lines;
    }

    private static boolean violates(Throwable failure, String key) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(key)) {
                return true;
            }
        }
        return false;
    }
}
