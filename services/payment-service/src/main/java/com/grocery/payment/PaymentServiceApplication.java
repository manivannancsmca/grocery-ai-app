package com.grocery.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Payment service (Phase 6): one payment per order, its Razorpay order, checkout verification, Razorpay webhooks,
 * the 15-minute payment window and refunds. Orders arrive from {@code order.events} ({@code OrderAwaitingPayment},
 * {@code OrderCancelled}); every payment fact leaves on {@code payment.events} through the outbox. The provider
 * is {@link com.grocery.payment.gateway.PaymentGateway}: Razorpay, or the local simulator without test keys.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
