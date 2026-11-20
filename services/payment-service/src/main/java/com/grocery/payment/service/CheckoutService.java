package com.grocery.payment.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.payment.config.PaymentProperties;
import com.grocery.payment.config.ProviderCredentials;
import com.grocery.payment.dto.PaymentDtos.CheckoutSession;
import com.grocery.payment.dto.PaymentDtos.Prefill;
import com.grocery.payment.entity.Payment;
import com.grocery.payment.gateway.Paise;
import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.ProviderRejectedException;
import com.grocery.payment.gateway.ProviderUnavailableException;
import com.grocery.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /api/payments/orders/{orderId}/checkout} (contract §3): everything Razorpay Checkout needs. Creates the
 * Razorpay order on demand when the {@code OrderAwaitingPayment} consumer has not (yet) — see
 * {@link ProviderOrderService} for why that never creates a second one.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final PaymentRepository payments;
    private final ProviderOrderService providerOrders;
    private final PaymentGateway gateway;
    private final ProviderCredentials credentials;
    private final PaymentProperties properties;
    private final Clock clock;

    public CheckoutService(PaymentRepository payments, ProviderOrderService providerOrders, PaymentGateway gateway,
                           ProviderCredentials credentials, PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.providerOrders = providerOrders;
        this.gateway = gateway;
        this.credentials = credentials;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws PaymentException 409 PAYMENT_NOT_READY (no payment yet), 404 PAYMENT_NOT_FOUND (another customer's),
     *                          409 PAYMENT_ALREADY_CAPTURED / PAYMENT_EXPIRED / PAYMENT_CANCELLED, 503
     *                          PAYMENT_PROVIDER_UNAVAILABLE (Razorpay unreachable while creating its order)
     */
    public CheckoutSession session(UUID orderId, CurrentUser customer) {
        Payment payment = payments.findByOrderId(orderId).orElseThrow(PaymentException::notReady);
        if (!payment.getCustomerId().equals(customer.userId())) {
            throw PaymentException.notFound();
        }
        requirePayable(payment, clock.instant());
        if (payment.getRazorpayOrderId() == null) {
            try {
                providerOrders.ensure(payment.getId());
            } catch (ProviderUnavailableException e) {
                throw PaymentException.providerUnavailable();
            } catch (ProviderRejectedException e) {
                log.error("Razorpay refused to create the order of payment {}: {}", payment.getId(), e.getMessage());
                throw PaymentException.providerUnavailable();
            }
            payment = payments.findById(payment.getId()).orElseThrow();
            requirePayable(payment, clock.instant());
            if (payment.getRazorpayOrderId() == null) {
                throw PaymentException.notReady();
            }
        }
        return new CheckoutSession(gateway.mode(), credentials.keyId(), payment.getRazorpayOrderId(),
                Paise.of(payment.getAmount()), payment.getAmount(), payment.getCurrency(), payment.getOrderId(),
                payment.getOrderNumber(), properties.merchantName(), "Order " + payment.getOrderNumber(),
                new Prefill(customer.name(), customer.email(), payment.getContactPhone()), payment.getExpiresAt());
    }

    private static void requirePayable(Payment payment, Instant now) {
        switch (payment.getStatus()) {
            case CAPTURED -> throw PaymentException.alreadyCaptured();
            case CANCELLED -> throw PaymentException.cancelled();
            case EXPIRED -> throw PaymentException.expired();
            case CREATED, PENDING -> {
                if (payment.isPastWindow(now)) {
                    throw PaymentException.expired();
                }
            }
        }
    }
}
