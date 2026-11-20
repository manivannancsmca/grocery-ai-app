package com.grocery.payment.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.payment.config.ProviderCredentials;
import com.grocery.payment.dto.PaymentDtos.PaymentView;
import com.grocery.payment.dto.PaymentDtos.VerifyRequest;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.PaymentGateway.ProviderPayment;
import com.grocery.payment.gateway.ProviderRejectedException;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.gateway.Signatures;
import com.grocery.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/payments/razorpay/verify} (contract §3.1 "Verify"): the browser's word is not trusted.
 * <ol>
 *   <li>Signature {@code hex(HMAC_SHA256(razorpayOrderId + "|" + razorpayPaymentId, keySecret))}, constant-time
 *       comparison → wrong: 400 INVALID_SIGNATURE, nothing changes.</li>
 *   <li>The Razorpay order must be one of the caller's payments → else 404 PAYMENT_NOT_FOUND.</li>
 *   <li>Already captured by this Razorpay payment → the payment as it is (idempotent; no provider call).</li>
 *   <li>Otherwise payment-service asks Razorpay for the payment and settles it ({@link CaptureService}):
 *       {@code captured} → captured; {@code authorized} → captured at Razorpay, then captured; {@code failed} → a
 *       declined attempt + {@code PaymentFailed}, answered 409 PAYMENT_FAILED. A late capture (the payment had
 *       expired or was cancelled) is accepted like any other and refunded at once; the answer is the PaymentView.</li>
 * </ol>
 */
@Service
public class VerifyService {

    private static final Logger log = LoggerFactory.getLogger(VerifyService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final CaptureService captures;
    private final PaymentQueryService queries;
    private final ProviderCredentials credentials;

    public VerifyService(PaymentRepository payments, PaymentGateway gateway, CaptureService captures,
                         PaymentQueryService queries, ProviderCredentials credentials) {
        this.payments = payments;
        this.gateway = gateway;
        this.captures = captures;
        this.queries = queries;
        this.credentials = credentials;
    }

    public PaymentView verify(VerifyRequest request, CurrentUser customer) {
        String razorpayOrderId = request.razorpayOrderId().strip();
        String razorpayPaymentId = request.razorpayPaymentId().strip();
        if (!Signatures.checkoutMatches(razorpayOrderId, razorpayPaymentId, request.razorpaySignature(),
                credentials.keySecret())) {
            log.info("Verify with an invalid signature for Razorpay order {}", razorpayOrderId);
            throw PaymentException.invalidSignature();
        }
        Payment payment = payments.findByRazorpayOrderId(razorpayOrderId)
                .filter(p -> p.getCustomerId().equals(customer.userId()))
                .orElseThrow(PaymentException::notFound);
        if (razorpayPaymentId.equals(payment.getRazorpayPaymentId())) {
            return queries.view(payment.getId());                         // verified before: unchanged
        }
        ProviderPayment reported;
        try {
            reported = gateway.fetchPayment(razorpayPaymentId);
        } catch (ProviderUnavailableException e) {
            throw PaymentException.providerUnavailable();
        } catch (ProviderRejectedException e) {
            log.warn("Razorpay does not know payment {} of order {}: {}", razorpayPaymentId, razorpayOrderId,
                    e.getMessage());
            throw PaymentException.notFound();
        }
        if (!razorpayOrderId.equals(reported.orderId())) {
            log.warn("Razorpay payment {} belongs to order {}, not {}", razorpayPaymentId, reported.orderId(),
                    razorpayOrderId);
            throw PaymentException.notFound();
        }
        CaptureService.Settlement settlement;
        try {
            settlement = captures.settle(payment.getId(), reported);
        } catch (ProviderUnavailableException e) {
            throw PaymentException.providerUnavailable();
        } catch (ProviderRejectedException e) {
            // capture refused and the payment could not be re-read: nothing was recorded, the webhook decides
            log.warn("Settling Razorpay payment {} failed: {}", razorpayPaymentId, e.getMessage());
            throw PaymentException.providerUnavailable();
        }
        if (settlement.result() == CaptureService.Result.FAILED) {
            ProviderPayment failed = settlement.payment();
            throw PaymentException.paymentFailed(failed.id(), failed.errorCode(), failed.errorDescription());
        }
        return queries.view(payment.getId());
    }
}
