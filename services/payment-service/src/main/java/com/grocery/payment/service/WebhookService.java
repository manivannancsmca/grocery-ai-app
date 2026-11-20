package com.grocery.payment.service;

import com.grocery.payment.config.ProviderCredentials;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.PaymentGateway.ProviderPaymentStatus;
import com.grocery.payment.gateway.PaymentGateway.ProviderRefund;
import com.grocery.payment.gateway.RazorpayJson;
import com.grocery.payment.gateway.Signatures;
import com.grocery.payment.repository.PaymentRepository;
import com.grocery.payment.repository.WebhookEventStore;
import com.grocery.payment.service.PaymentRecorder.Capture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST /api/payments/razorpay/webhook} (contract §3.2). Webhooks are the source of truth.
 * <ol>
 *   <li>The raw body bytes (before any parsing) are checked against {@code X-Razorpay-Signature} =
 *       {@code hex(HMAC_SHA256(rawBody, webhookSecret))}, constant time → wrong / missing: 400 INVALID_SIGNATURE,
 *       nothing stored.</li>
 *   <li>One transaction: the event is stored in {@code webhook_event} (unique {@code X-Razorpay-Event-Id}; a repeat
 *       → 200, nothing done) and applied with the payment locked:
 *       <ul>
 *         <li>{@code payment.captured} → captured (late capture included), like verify;</li>
 *         <li>{@code payment.authorized} → attempt AUTHORIZED; captured at Razorpay after commit;</li>
 *         <li>{@code payment.failed} → declined attempt + {@code PaymentFailed};</li>
 *         <li>{@code refund.processed} / {@code refund.failed} → finishes a PENDING refund;</li>
 *         <li>anything else, or an unknown Razorpay order / refund → stored, {@code processed = false}, logged.</li>
 *       </ul></li>
 *   <li>After commit, provider calls the event triggered: the capture of an authorized payment, a late capture's
 *       refund. Their failures never fail the webhook (pollers retry them).</li>
 * </ol>
 */
@Service
public class WebhookService {

    static final String PAYMENT_CAPTURED = "payment.captured";
    static final String PAYMENT_AUTHORIZED = "payment.authorized";
    static final String PAYMENT_FAILED = "payment.failed";
    static final String REFUND_PROCESSED = "refund.processed";
    static final String REFUND_FAILED = "refund.failed";
    static final String UNREADABLE = "unreadable";
    static final int MAX_EVENT_ID = 100;
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    /** What applying an event did, and what to do with the provider after commit. */
    private record Applied(boolean processed, String outcome, Runnable afterCommit) {

        static Applied ignored(String outcome) {
            return new Applied(false, outcome, null);
        }

        static Applied done(String outcome, Runnable afterCommit) {
            return new Applied(true, outcome, afterCommit);
        }
    }

    private final WebhookEventStore store;
    private final PaymentRepository payments;
    private final PaymentLocks locks;
    private final PaymentRecorder recorder;
    private final CaptureService captures;
    private final RefundService refunds;
    private final ProviderCredentials credentials;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public WebhookService(WebhookEventStore store, PaymentRepository payments, PaymentLocks locks,
                          PaymentRecorder recorder, CaptureService captures, RefundService refunds,
                          ProviderCredentials credentials, JsonMapper jsonMapper,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.store = store;
        this.payments = payments;
        this.locks = locks;
        this.recorder = recorder;
        this.captures = captures;
        this.refunds = refunds;
        this.credentials = credentials;
        this.jsonMapper = jsonMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param rawBody   the request body exactly as received
     * @param eventId   {@code X-Razorpay-Event-Id}; when absent, the SHA-256 of the body stands in for it (identical
     *                  redeliveries are still recognised)
     * @throws PaymentException 400 INVALID_SIGNATURE
     */
    public void receive(byte[] rawBody, String signature, String eventId) {
        if (!Signatures.webhookMatches(rawBody, signature, credentials.webhookSecret())) {
            log.warn("Webhook with an invalid or missing signature rejected");
            throw PaymentException.invalidSignature();
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode root = parse(body);
        String eventType = root == null ? UNREADABLE : Optional.ofNullable(RazorpayJson.text(root, "event"))
                .orElse(UNREADABLE);
        String id = eventId(eventId, rawBody);
        List<Runnable> afterCommit = new ArrayList<>();
        Boolean fresh = transaction.execute(status -> {
            if (!store.insertIfNew(id, eventType, body, clock.instant())) {
                return false;
            }
            Applied applied = apply(eventType, root);
            store.recordOutcome(id, applied.processed(), applied.outcome());
            if (applied.afterCommit() != null) {
                afterCommit.add(applied.afterCommit());
            }
            return true;
        });
        if (!Boolean.TRUE.equals(fresh)) {
            log.info("Webhook {} ({}) received again: ignored", id, eventType);
            return;
        }
        for (Runnable action : afterCommit) {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Follow-up of webhook {} ({}) failed; the pollers retry it: {}", id, eventType, e.toString());
            }
        }
    }

    private Applied apply(String eventType, JsonNode root) {
        return switch (eventType) {
            case PAYMENT_CAPTURED -> paymentEvent(root, ProviderPaymentStatus.CAPTURED);
            case PAYMENT_AUTHORIZED -> paymentEvent(root, ProviderPaymentStatus.AUTHORIZED);
            case PAYMENT_FAILED -> paymentEvent(root, ProviderPaymentStatus.FAILED);
            case REFUND_PROCESSED -> refundEvent(root, true);
            case REFUND_FAILED -> refundEvent(root, false);
            default -> {
                log.info("Webhook event {} stored and ignored", eventType);
                yield Applied.ignored("event type not handled");
            }
        };
    }

    private Applied paymentEvent(JsonNode root, ProviderPaymentStatus status) {
        JsonNode entity = entity(root, "payment");
        if (entity == null || RazorpayJson.text(entity, "id") == null) {
            return Applied.ignored("no payload.payment.entity");
        }
        ProviderPayment parsed = RazorpayJson.payment(entity);
        // The event type is what happened; the entity's status field says the same (or something newer)
        ProviderPayment reported = new ProviderPayment(parsed.id(), parsed.orderId(), status, parsed.method(),
                parsed.amount(), parsed.currency(), parsed.errorCode(), parsed.errorDescription());
        Optional<UUID> paymentId = Optional.ofNullable(reported.orderId()).flatMap(payments::findIdByRazorpayOrderId);
        if (paymentId.isEmpty()) {
            log.warn("Webhook for unknown Razorpay order {} (payment {}) stored and ignored", reported.orderId(),
                    reported.id());
            return Applied.ignored("unknown Razorpay order " + reported.orderId());
        }
        Payment payment = locks.payment(paymentId.get());
        Instant now = clock.instant();
        return switch (status) {
            case CAPTURED -> {
                Capture capture = recorder.captured(payment, reported, now);
                UUID lateRefund = capture.lateRefundId();
                yield Applied.done("capture: " + capture.outcome(),
                        lateRefund == null ? null : () -> refunds.processAfterCommit(lateRefund));
            }
            case FAILED -> Applied.done(recorder.failed(payment, reported, now) ? "declined" : "decline known",
                    null);
            default -> {
                recorder.authorized(payment, reported, now);
                if (reported.id().equals(payment.getRazorpayPaymentId())) {
                    yield Applied.done("authorized, already captured", null);
                }
                UUID id = payment.getId();
                yield Applied.done("authorized, capturing", () -> captures.settle(id, reported));
            }
        };
    }

    private Applied refundEvent(JsonNode root, boolean processed) {
        JsonNode entity = entity(root, "refund");
        if (entity == null) {
            return Applied.ignored("no payload.refund.entity");
        }
        ProviderRefund reported = RazorpayJson.refund(entity);
        return refunds.applyWebhook(reported, processed)
                .map(outcome -> Applied.done(outcome, null))
                .orElseGet(() -> {
                    log.warn("Webhook for unknown refund {} stored and ignored", reported.id());
                    return Applied.ignored("unknown refund " + reported.id());
                });
    }

    /** {@code payload.<name>.entity}. */
    private static JsonNode entity(JsonNode root, String name) {
        JsonNode payload = root == null ? null : root.get("payload");
        JsonNode wrapper = payload == null ? null : payload.get(name);
        JsonNode entity = wrapper == null ? null : wrapper.get("entity");
        return entity == null || !entity.isObject() ? null : entity;
    }

    private JsonNode parse(String body) {
        try {
            JsonNode root = jsonMapper.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (RuntimeException e) {
            log.warn("Signed webhook body is not JSON: {}", e.getMessage());
            return null;
        }
    }

    private static String eventId(String header, byte[] rawBody) {
        String id = header == null ? "" : header.strip();
        if (!id.isEmpty() && id.length() <= MAX_EVENT_ID) {
            return id;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.isEmpty() ? rawBody
                    : id.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
