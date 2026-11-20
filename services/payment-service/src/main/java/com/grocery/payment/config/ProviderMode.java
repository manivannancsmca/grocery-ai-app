package com.grocery.payment.config;

import org.springframework.util.StringUtils;

import java.util.Locale;

/** Which {@link com.grocery.payment.gateway.PaymentGateway} this instance talks to. */
public enum ProviderMode {
    RAZORPAY,
    SIMULATOR;

    /**
     * {@code grocery.payment.provider} when set; otherwise Razorpay if a key id is configured
     * ({@code RAZORPAY_KEY_ID}), else the simulator.
     */
    public static ProviderMode resolve(PaymentProperties properties) {
        String provider = properties.provider();
        if (!StringUtils.hasText(provider)) {
            return StringUtils.hasText(properties.razorpay().keyId()) ? RAZORPAY : SIMULATOR;
        }
        return switch (provider.strip().toLowerCase(Locale.ROOT)) {
            case "razorpay" -> RAZORPAY;
            case "simulator" -> SIMULATOR;
            default -> throw new IllegalStateException(
                    "grocery.payment.provider must be 'razorpay' or 'simulator', not '" + provider + "'");
        };
    }
}
