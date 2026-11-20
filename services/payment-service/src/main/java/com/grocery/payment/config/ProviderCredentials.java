package com.grocery.payment.config;

/**
 * The keys of the active provider: Razorpay's test keys, or the simulator's fixed ones. The key secret and the
 * webhook secret never leave payment-service; only {@code keyId} goes into the checkout session.
 */
public record ProviderCredentials(String keyId, String keySecret, String webhookSecret) {

    @Override
    public String toString() {
        return "ProviderCredentials[keyId=" + keyId + ", secrets hidden]";
    }
}
