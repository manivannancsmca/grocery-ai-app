package com.grocery.vendor.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.common.security.CurrentUser;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderView;
import com.grocery.vendor.dto.PurchasingDtos.ReceiveLine;
import com.grocery.vendor.dto.PurchasingDtos.ReceiveRequest;
import com.grocery.vendor.entity.PurchaseOrder;
import com.grocery.vendor.entity.PurchaseOrder.Receiving;
import com.grocery.vendor.entity.PurchaseOrderLine;
import com.grocery.vendor.entity.PurchaseOrderStatus;
import com.grocery.vendor.entity.PurchaseReceipt;
import com.grocery.vendor.mapper.PurchasingMapper;
import com.grocery.vendor.repository.PurchaseOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST /api/vendor/purchase-orders/{id}/receive} (Phase 12 §4). In order:
 * <ol>
 *   <li>{@code Idempotency-Key}: missing → 400 IDEMPOTENCY_KEY_REQUIRED, malformed → 400 IDEMPOTENCY_KEY_INVALID;
 *       then the body (400 VALIDATION_FAILED: 1–100 lines, each product once, quantity 1–100000, note ≤ 300).</li>
 *   <li>A key this vendor used before for the same purchase order and body → the purchase order as it is now, nothing
 *       received again and no second {@code GoodsReceived} ({@code Idempotent-Replayed: true}); with a different
 *       purchase order or body → 422 IDEMPOTENCY_KEY_REUSED. Keys are scoped per vendor.</li>
 *   <li>One transaction: the key is claimed for a pre-generated receipt id ({@link IdempotencyKeys#claim}), the
 *       purchase order locked ({@code FOR UPDATE}) and checked (404 PURCHASE_ORDER_NOT_FOUND; 409
 *       INVALID_STATUS_TRANSITION with {@code currentStatus} unless SENT or PARTIALLY_RECEIVED; 422
 *       RECEIPT_EXCEEDS_ORDERED with {@code productId} and {@code openQuantity} when a line is for more than is still
 *       open, or for a product not on the purchase order), the quantities added, the receipt written and
 *       {@code GoodsReceived} put in the outbox. Any error rolls the claim back too, so the same key can be used
 *       again once the problem is fixed.</li>
 * </ol>
 * Status afterwards: RECEIVED when nothing is open any more, else PARTIALLY_RECEIVED.
 */
@Service
public class PurchaseReceivingService {

    static final String SCOPE_PREFIX = "purchase-receipt:";
    private static final Logger log = LoggerFactory.getLogger(PurchaseReceivingService.class);

    /** The purchase order after the receipt, and whether it is the answer to a retry. */
    public record Received(PurchaseOrderView purchaseOrder, boolean replayed) {
    }

    /** What identifies the request (the Idempotency-Key's request hash). */
    record Fingerprint(UUID purchaseOrderId, List<ReceiveLine> lines, String note) {
    }

    private final IdempotencyKeys idempotencyKeys;
    private final PurchaseOrderService purchaseOrders;
    private final PurchaseOrderRepository repository;
    private final PurchasingEventPublisher events;
    private final EntityManager entityManager;
    private final Validator validator;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public PurchaseReceivingService(IdempotencyKeys idempotencyKeys, PurchaseOrderService purchaseOrders,
                                    PurchaseOrderRepository repository, PurchasingEventPublisher events,
                                    EntityManager entityManager, Validator validator,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.idempotencyKeys = idempotencyKeys;
        this.purchaseOrders = purchaseOrders;
        this.repository = repository;
        this.events = events;
        this.entityManager = entityManager;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    public Received receive(CurrentUser user, UUID purchaseOrderId, String idempotencyKeyHeader,
                            ReceiveRequest request) {
        UUID vendorId = user.requireVendorId();
        String key = IdempotencyKeys.requireKey(idempotencyKeyHeader);
        Fingerprint fingerprint = validated(purchaseOrderId, request);
        String scope = SCOPE_PREFIX + vendorId;
        String hash = idempotencyKeys.hash(fingerprint);

        if (idempotencyKeys.find(scope, key, hash).isPresent()) {
            return new Received(view(vendorId, purchaseOrderId), true);
        }
        UUID receiptId = Ids.newId();
        Received received = transaction.execute(status -> {
            Optional<String> earlier = idempotencyKeys.claim(scope, key, hash, receiptId.toString());
            if (earlier.isPresent()) {
                return null;                                        // a concurrent retry won: its receipt is the answer
            }
            PurchaseOrder order = purchaseOrders.lockOwn(vendorId, purchaseOrderId);
            PurchaseOrderService.requireStatus(order, PurchaseOrderStatus.RECEIVABLE, "receive goods");
            List<Receiving> receiving = new ArrayList<>(fingerprint.lines().size());
            for (ReceiveLine line : fingerprint.lines()) {
                int open = order.line(line.productId()).map(PurchaseOrderLine::openQuantity).orElse(0);
                if (line.quantity() > open) {
                    throw PurchasingException.receiptExceedsOrdered(line.productId(), open, line.quantity());
                }
                receiving.add(new Receiving(line.productId(), line.quantity()));
            }
            PurchaseReceipt receipt = order.receive(receiptId, receiving, fingerprint.note(), user.userId(),
                    clock.instant());
            entityManager.persist(receipt);
            repository.flush();
            events.goodsReceived(order, receipt);
            log.info("Purchase order {}: receipt {} with {} unit(s), now {}", order.getNumber(), receiptId,
                    receipt.units(), order.getStatus());
            return new Received(PurchasingMapper.toView(order), false);
        });
        return received != null ? received : new Received(view(vendorId, purchaseOrderId), true);
    }

    private PurchaseOrderView view(UUID vendorId, UUID purchaseOrderId) {
        return readTransaction.execute(status -> PurchasingMapper.toView(repository
                .findByIdAndVendorId(purchaseOrderId, vendorId)
                .orElseThrow(PurchasingException::purchaseOrderNotFound)));
    }

    /** Bean Validation of the body (after the key check), each product once; the note trimmed (blank → null). */
    private Fingerprint validated(UUID purchaseOrderId, ReceiveRequest request) {
        if (request == null) {
            throw PurchasingException.validation("lines", "must not be null");
        }
        List<Map<String, String>> errors = validator.validate(request).stream()
                .sorted(Comparator.comparing((ConstraintViolation<ReceiveRequest> v) -> v.getPropertyPath().toString()))
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        if (!errors.isEmpty()) {
            throw PurchasingException.validation(errors);
        }
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < request.lines().size(); i++) {
            Integer previous = firstIndex.putIfAbsent(request.lines().get(i).productId(), i);
            if (previous != null) {
                throw PurchasingException.validation("lines[" + i + "].productId",
                        "the product is already on lines[" + previous + "]");
            }
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        return new Fingerprint(purchaseOrderId, List.copyOf(request.lines()), note);
    }
}
