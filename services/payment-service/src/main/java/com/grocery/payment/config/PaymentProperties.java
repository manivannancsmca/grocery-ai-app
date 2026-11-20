package com.grocery.payment.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code grocery.payment.*}
 *
 * @param provider            {@code razorpay} | {@code simulator}; blank = razorpay when a key id is configured,
 *                            otherwise simulator (see {@link ProviderMode#resolve})
 * @param merchantName        shown in Razorpay Checkout
 * @param razorpay            API keys (test mode), webhook secret and API base URL
 * @param http                timeouts of every provider call
 * @param simulator           simulator switches
 * @param expiryPollInterval  how often the payment window poller runs (env GROCERY_PAYMENT_EXPIRY_POLL_INTERVAL)
 * @param expiryBatchSize     payments claimed per poller round
 * @param refundRetryInterval how often PENDING refunds are retried / re-checked at the provider (env
 *                            GROCERY_PAYMENT_REFUND_RETRY_INTERVAL); also the first step of the refund backoff
 * @param refundBatchSize     refunds claimed per refund poller round
 * @param pollLease           how long a poller claim (payment) or an in-flight refund call (refund) is exclusive
 * @param pollersEnabled      false = no {@code @Scheduled} pollers (tests run them by hand)
 * @param reconcileInterval   Phase 10: how often PENDING payments are reconciled with the provider (env
 *                            PAYMENT_RECONCILE_INTERVAL, fixed delay)
 * @param reconcileAfter      Phase 10: only payments whose Razorpay order is at least this old are reconciled (env
 *                            PAYMENT_RECONCILE_AFTER): younger ones are still in the customer's hands
 * @param reconcileBatchSize  payments claimed per reconciliation batch
 */
@Validated
@ConfigurationProperties(prefix = "grocery.payment")
public record PaymentProperties(
        String provider,
        @DefaultValue("Grocery AI") @NotBlank String merchantName,
        @DefaultValue @Valid Razorpay razorpay,
        @DefaultValue @Valid Http http,
        @DefaultValue Simulator simulator,
        @DefaultValue("30s") @NotNull Duration expiryPollInterval,
        @DefaultValue("50") @Min(1) @Max(1000) int expiryBatchSize,
        @DefaultValue("60s") @NotNull Duration refundRetryInterval,
        @DefaultValue("20") @Min(1) @Max(1000) int refundBatchSize,
        @DefaultValue("2m") @NotNull Duration pollLease,
        @DefaultValue("true") boolean pollersEnabled,
        @DefaultValue("2m") @NotNull Duration reconcileInterval,
        @DefaultValue("2m") @NotNull Duration reconcileAfter,
        @DefaultValue("50") @Min(1) @Max(1000) int reconcileBatchSize) {

    /**
     * @param keyId         {@code rzp_test_…} (env RAZORPAY_KEY_ID)
     * @param keySecret     signs checkout results; never leaves this service (env RAZORPAY_KEY_SECRET)
     * @param webhookSecret the secret set on the Razorpay webhook (env RAZORPAY_WEBHOOK_SECRET)
     * @param baseUrl       Razorpay REST API, {@code https://api.razorpay.com/v1}
     */
    public record Razorpay(String keyId, String keySecret, String webhookSecret,
                           @DefaultValue("https://api.razorpay.com/v1") @NotBlank String baseUrl) {
    }

    public record Http(@DefaultValue("2s") @NotNull Duration connectTimeout,
                       @DefaultValue("10s") @NotNull Duration readTimeout) {
    }

    /**
     * @param allowAnyProfile let simulator mode start without the {@code docker} / {@code local} profile (tests only)
     */
    public record Simulator(@DefaultValue("false") boolean allowAnyProfile) {
    }
}
