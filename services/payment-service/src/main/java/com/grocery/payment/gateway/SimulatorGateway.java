package com.grocery.payment.gateway;

import com.grocery.payment.config.ProviderMode;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for Razorpay in local development without test keys (simulator mode, docs/api/phase-6 §3.3). Behaves
 * like Razorpay with auto-capture: a successful simulated payment is {@code captured}, a declined one {@code failed};
 * refunds are {@code processed} at once (like Razorpay test mode). Ids look like Razorpay's ({@code order_…},
 * {@code pay_…}, {@code rfnd_…}); checkout signatures use {@link #KEY_SECRET}, webhooks {@link #WEBHOOK_SECRET}.
 *
 * <p><b>Single-instance development only:</b> everything lives in this JVM's memory and is lost on restart. A
 * Razorpay order unknown after a restart is re-registered when it is paid ({@link #simulatePayment}), so a payment
 * row created before the restart can still be paid. Never used in Razorpay mode.
 */
public class SimulatorGateway implements PaymentGateway {

    public static final String KEY_ID = "rzp_test_simulator";
    public static final String KEY_SECRET = "simulator_key_secret";
    public static final String WEBHOOK_SECRET = "simulator_webhook_secret";

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, ProviderOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, ProviderPayment> payments = new ConcurrentHashMap<>();
    private final Map<String, ProviderRefund> refunds = new ConcurrentHashMap<>();
    private final Map<String, String> refundsByIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public ProviderMode mode() {
        return ProviderMode.SIMULATOR;
    }

    @Override
    public ProviderOrder createOrder(NewOrder order) {
        ProviderOrder created = new ProviderOrder(newId("order"), order.amount(), order.currency(), order.receipt(),
                "created");
        orders.put(created.id(), created);
        return created;
    }

    @Override
    public ProviderPayment fetchPayment(String razorpayPaymentId) {
        ProviderPayment payment = payments.get(razorpayPaymentId);
        if (payment == null) {
            throw notFound();
        }
        return payment;
    }

    @Override
    public synchronized ProviderPayment capturePayment(String razorpayPaymentId, BigDecimal amount, String currency) {
        ProviderPayment payment = fetchPayment(razorpayPaymentId);
        if (payment.status() != ProviderPaymentStatus.AUTHORIZED) {
            throw new ProviderRejectedException(400, "BAD_REQUEST_ERROR",
                    "Only authorized payments can be captured (payment is " + payment.status().wire() + ")");
        }
        ProviderPayment captured = withStatus(payment, ProviderPaymentStatus.CAPTURED);
        payments.put(captured.id(), captured);
        return captured;
    }

    @Override
    public List<ProviderPayment> listOrderPayments(String razorpayOrderId) {
        return payments.values().stream().filter(p -> razorpayOrderId.equals(p.orderId())).toList();
    }

    @Override
    public synchronized ProviderRefund createRefund(NewRefund refund) {
        String existing = refund.idempotencyKey() == null ? null
                : refundsByIdempotencyKey.get(refund.idempotencyKey());
        if (existing != null) {
            return refunds.get(existing);
        }
        ProviderPayment payment = fetchPayment(refund.razorpayPaymentId());
        if (!payment.status().wasCaptured()) {
            throw new ProviderRejectedException(400, "BAD_REQUEST_ERROR", "The payment has not been captured");
        }
        BigDecimal alreadyRefunded = refunds.values().stream()
                .filter(r -> r.paymentId().equals(payment.id()) && r.status() != ProviderRefundStatus.FAILED)
                .map(ProviderRefund::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = alreadyRefunded.add(refund.amount());
        if (total.compareTo(payment.amount()) > 0) {
            throw new ProviderRejectedException(400, "BAD_REQUEST_ERROR",
                    "The total refund amount is greater than the refund payment amount");
        }
        String refundId = refund.notes() == null ? null : refund.notes().get("refundId");
        ProviderRefund created = new ProviderRefund(newId("rfnd"), payment.id(), refund.amount(),
                ProviderRefundStatus.PROCESSED, refundId);
        refunds.put(created.id(), created);
        if (refund.idempotencyKey() != null) {
            refundsByIdempotencyKey.put(refund.idempotencyKey(), created.id());
        }
        if (total.compareTo(payment.amount()) == 0) {
            payments.put(payment.id(), withStatus(payment, ProviderPaymentStatus.REFUNDED));
        }
        return created;
    }

    @Override
    public ProviderRefund fetchRefund(String razorpayRefundId) {
        ProviderRefund refund = refunds.get(razorpayRefundId);
        if (refund == null) {
            throw notFound();
        }
        return refund;
    }

    /**
     * What the customer does in Razorpay Checkout: pays the order ({@code captured}, method {@code upi}) or is
     * declined ({@code failed}). Works on any order that has no successful payment yet — including one whose
     * payment already expired or was cancelled on our side, like a Checkout tab left open (late capture).
     *
     * @throws ProviderRejectedException 400 when the order was already paid
     */
    public synchronized ProviderPayment simulatePayment(String razorpayOrderId, BigDecimal amount, String currency,
                                                        boolean success) {
        orders.computeIfAbsent(razorpayOrderId, id -> new ProviderOrder(id, amount, currency, null, "created"));
        boolean paid = payments.values().stream()
                .anyMatch(p -> razorpayOrderId.equals(p.orderId()) && p.status().wasCaptured());
        if (paid) {
            throw new ProviderRejectedException(400, "BAD_REQUEST_ERROR", "Order is already paid");
        }
        ProviderPayment payment = success
                ? new ProviderPayment(newId("pay"), razorpayOrderId, ProviderPaymentStatus.CAPTURED, "upi", amount,
                currency, null, null)
                : new ProviderPayment(newId("pay"), razorpayOrderId, ProviderPaymentStatus.FAILED, "card", amount,
                currency, "BAD_REQUEST_ERROR", "Payment declined by the simulator");
        payments.put(payment.id(), payment);
        orders.computeIfPresent(razorpayOrderId, (id, order) -> success
                ? new ProviderOrder(id, order.amount(), order.currency(), order.receipt(), "paid")
                : new ProviderOrder(id, order.amount(), order.currency(), order.receipt(), "attempted"));
        return payment;
    }

    /** The Razorpay order, if this simulator created (or re-registered) it. */
    public Optional<ProviderOrder> findOrder(String razorpayOrderId) {
        return Optional.ofNullable(orders.get(razorpayOrderId));
    }

    private static ProviderPayment withStatus(ProviderPayment p, ProviderPaymentStatus status) {
        return new ProviderPayment(p.id(), p.orderId(), status, p.method(), p.amount(), p.currency(), p.errorCode(),
                p.errorDescription());
    }

    private static ProviderRejectedException notFound() {
        return new ProviderRejectedException(400, "BAD_REQUEST_ERROR", "The id provided does not exist");
    }

    static String newId(String prefix) {
        StringBuilder id = new StringBuilder(prefix).append('_');
        for (int i = 0; i < 14; i++) {
            id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return id.toString();
    }

    /** Notes as Razorpay stores them (string values). */
    public static Map<String, String> notes(Object... keyValues) {
        Map<String, String> notes = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            notes.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
        }
        return notes;
    }
}
