package com.grocery.payment.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.common.security.CurrentUser;
import com.grocery.payment.dto.PaymentDtos.AdminRefundRequest;
import com.grocery.payment.dto.PaymentDtos.RefundView;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.entity.RefundReason;
import com.grocery.payment.repository.PaymentRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST /api/admin/refunds} (contract §4, SUPER_ADMIN / FINANCE_ADMIN). In order:
 * <ol>
 *   <li>{@code Idempotency-Key}: missing → 400 IDEMPOTENCY_KEY_REQUIRED, malformed → 400 IDEMPOTENCY_KEY_INVALID;
 *       then the body (400 VALIDATION_FAILED).</li>
 *   <li>A key this admin used before with the same body → the original refund, as it is now (201,
 *       {@code Idempotent-Replayed: true}); with a different body → 422 IDEMPOTENCY_KEY_REUSED. Keys are scoped per
 *       admin user.</li>
 *   <li>One transaction: the key is claimed for a pre-generated refund id ({@link IdempotencyKeys#claim}), the
 *       payment locked and checked (404 PAYMENT_NOT_FOUND, 409 PAYMENT_NOT_CAPTURED, 422 REFUND_EXCEEDS_CAPTURED with
 *       {@code refundable}), and the PENDING refund (reason ADMIN, {@code requestedBy}) inserted. An error rolls the
 *       claim back too, so the same key can be used again once the problem is fixed.</li>
 *   <li>After commit the refund goes to the provider ({@link RefundService#process}); the answer shows its status
 *       then (PROCESSED in test mode, FAILED with {@code failureReason}, or PENDING).</li>
 * </ol>
 * An admin refund never changes the order status; order-service shows it through {@code RefundCompleted}.
 */
@Service
public class AdminRefundService {

    static final String SCOPE_PREFIX = "refund:";
    private static final Logger log = LoggerFactory.getLogger(AdminRefundService.class);

    /** The refund and whether it is the answer to a retry ({@code Idempotent-Replayed: true}). */
    public record Requested(RefundView refund, boolean replayed) {
    }

    /** What identifies the request (the Idempotency-Key's request hash): amounts normalised to 2 decimals. */
    record Fingerprint(UUID orderId, BigDecimal amount, String note) {
    }

    private final IdempotencyKeys idempotencyKeys;
    private final PaymentRepository payments;
    private final PaymentLocks locks;
    private final RefundLedger ledger;
    private final RefundService refunds;
    private final PaymentQueryService queries;
    private final Validator validator;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AdminRefundService(IdempotencyKeys idempotencyKeys, PaymentRepository payments, PaymentLocks locks,
                              RefundLedger ledger, RefundService refunds, PaymentQueryService queries,
                              Validator validator, PlatformTransactionManager transactionManager, Clock clock) {
        this.idempotencyKeys = idempotencyKeys;
        this.payments = payments;
        this.locks = locks;
        this.ledger = ledger;
        this.refunds = refunds;
        this.queries = queries;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Requested refund(String idempotencyKeyHeader, AdminRefundRequest request, CurrentUser admin) {
        String key = IdempotencyKeys.requireKey(idempotencyKeyHeader);
        Fingerprint fingerprint = validated(request);
        String scope = SCOPE_PREFIX + admin.userId();
        String hash = idempotencyKeys.hash(fingerprint);

        Optional<String> earlier = idempotencyKeys.find(scope, key, hash);
        if (earlier.isPresent()) {
            return new Requested(queries.refund(UUID.fromString(earlier.get())), true);
        }
        UUID refundId = Ids.newId();
        Optional<String> claimed = transaction.execute(status -> {
            Optional<String> other = idempotencyKeys.claim(scope, key, hash, refundId.toString());
            if (other.isPresent()) {
                return other;                                   // a concurrent retry won: its refund is the answer
            }
            UUID paymentId = payments.findIdByOrderId(fingerprint.orderId()).orElseThrow(PaymentException::notFound);
            Payment payment = locks.payment(paymentId);
            if (payment.getStatus() != PaymentStatus.CAPTURED) {
                throw PaymentException.notCaptured();
            }
            BigDecimal refundable = ledger.refundable(payment);
            BigDecimal amount = fingerprint.amount() == null ? refundable : fingerprint.amount();
            if (amount.signum() <= 0 || amount.compareTo(refundable) > 0) {
                throw PaymentException.refundExceedsCaptured(refundable);
            }
            Instant now = clock.instant();
            ledger.open(payment, refundId, RefundReason.ADMIN, amount, fingerprint.note(), admin.userId(), now);
            log.info("Admin {} refunds {} of order {} ({})", admin.userId(), amount, payment.getOrderNumber(),
                    fingerprint.note());
            return Optional.<String>empty();
        });
        if (claimed != null && claimed.isPresent()) {
            return new Requested(queries.refund(UUID.fromString(claimed.get())), true);
        }
        refunds.processAfterCommit(refundId);
        return new Requested(queries.refund(refundId), false);
    }

    /** Bean Validation of the body (after the key check), then normalised. */
    private Fingerprint validated(AdminRefundRequest request) {
        if (request == null) {
            throw PaymentException.validation(List.of(Map.of("field", "orderId", "message", "must not be null")));
        }
        List<Map<String, String>> errors = validator.validate(request).stream()
                .sorted(Comparator.comparing((ConstraintViolation<AdminRefundRequest> v) ->
                        v.getPropertyPath().toString()))
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        if (!errors.isEmpty()) {
            throw PaymentException.validation(errors);
        }
        BigDecimal amount = request.amount() == null ? null : request.amount().setScale(2, RoundingMode.UNNECESSARY);
        return new Fingerprint(request.orderId(), amount, request.note().strip());
    }
}
