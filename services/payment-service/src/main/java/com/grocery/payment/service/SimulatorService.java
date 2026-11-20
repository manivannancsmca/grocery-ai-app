package com.grocery.payment.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.NotFoundException;
import com.grocery.payment.config.ProviderCredentials;
import com.grocery.payment.dto.PaymentDtos.SimulatedOutcome;
import com.grocery.payment.dto.PaymentDtos.SimulatedPayment;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.entity.PaymentStatus;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.ProviderRejectedException;
import com.grocery.payment.gateway.Signatures;
import com.grocery.payment.gateway.SimulatorGateway;
import com.grocery.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * {@code POST /api/payments/simulator/orders/{orderId}/pay} (contract §3.3) — what the customer does in Razorpay
 * Checkout, in simulator mode only (404 in Razorpay mode).
 *
 * <p>It behaves like a Checkout tab left open: it needs only the caller's payment and its (simulator) Razorpay order —
 * the payment may already be EXPIRED or CANCELLED on our side, which is how a real late capture is produced. It
 * changes nothing on our side for SUCCESS: the answer is the signed result the app then posts to verify, exactly as
 * after Razorpay Checkout. FAIL is recorded at once, the same way as a {@code payment.failed} webhook.
 */
@Service
public class SimulatorService {

    private static final Logger log = LoggerFactory.getLogger(SimulatorService.class);

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final CaptureService captures;
    private final ProviderCredentials credentials;

    public SimulatorService(PaymentGateway gateway, PaymentRepository payments, CaptureService captures,
                            ProviderCredentials credentials) {
        this.gateway = gateway;
        this.payments = payments;
        this.captures = captures;
        this.credentials = credentials;
    }

    /**
     * @throws NotFoundException 404 in Razorpay mode
     * @throws PaymentException  409 PAYMENT_NOT_READY (no payment / no Razorpay order yet), 404 PAYMENT_NOT_FOUND
     *                           (another customer's), 409 PAYMENT_ALREADY_CAPTURED
     */
    public SimulatedPayment pay(UUID orderId, SimulatedOutcome outcome, CurrentUser customer) {
        if (!(gateway instanceof SimulatorGateway simulator)) {
            throw new NotFoundException("NOT_FOUND", "The payment simulator is not enabled");
        }
        Payment payment = payments.findByOrderId(orderId).orElseThrow(PaymentException::notReady);
        if (!payment.getCustomerId().equals(customer.userId())) {
            throw PaymentException.notFound();
        }
        if (payment.getStatus() == PaymentStatus.CAPTURED) {
            throw PaymentException.alreadyCaptured();
        }
        String razorpayOrderId = payment.getRazorpayOrderId();
        if (razorpayOrderId == null) {
            throw PaymentException.notReady();
        }
        boolean success = outcome == SimulatedOutcome.SUCCESS;
        ProviderPayment simulated;
        try {
            simulated = simulator.simulatePayment(razorpayOrderId, payment.getAmount(), payment.getCurrency(),
                    success);
        } catch (ProviderRejectedException e) {
            throw PaymentException.alreadyCaptured();
        }
        log.info("Simulator: payment {} of order {} {} (our payment is {})", simulated.id(),
                payment.getOrderNumber(), success ? "succeeded" : "declined", payment.getStatus());
        if (success) {
            return new SimulatedPayment(razorpayOrderId, simulated.id(),
                    Signatures.checkout(razorpayOrderId, simulated.id(), credentials.keySecret()));
        }
        captures.settle(payment.getId(), simulated);
        return new SimulatedPayment(razorpayOrderId, simulated.id(), null);
    }
}
