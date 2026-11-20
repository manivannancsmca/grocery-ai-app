package com.grocery.payment.config;

import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.RazorpayGateway;
import com.grocery.payment.gateway.SimulatorGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chooses the payment provider at startup ({@link ProviderMode#resolve}) and refuses unsafe setups:
 * <ul>
 *   <li>simulator mode only with the {@code docker} or {@code local} profile (or
 *       {@code grocery.payment.simulator.allow-any-profile=true}, for tests) — a deployed instance without keys must
 *       not silently accept fake payments;</li>
 *   <li>Razorpay mode needs a key id and a key secret; a missing webhook secret is logged (every webhook is then
 *       rejected with 400, verify and the poller still confirm payments).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class PaymentGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    @Bean
    ProviderMode providerMode(PaymentProperties properties, Environment environment) {
        ProviderMode mode = ProviderMode.resolve(properties);
        if (mode == ProviderMode.SIMULATOR) {
            if (!properties.simulator().allowAnyProfile()
                    && !environment.acceptsProfiles(Profiles.of("docker | local"))) {
                throw new IllegalStateException("Payment simulator mode (no RAZORPAY_KEY_ID, or "
                        + "grocery.payment.provider=simulator) is only allowed with the 'docker' or 'local' profile. "
                        + "Configure Razorpay test keys (RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, "
                        + "RAZORPAY_WEBHOOK_SECRET) instead.");
            }
            log.warn("Payment provider: SIMULATOR (local development only; no real payments are possible)");
        } else {
            PaymentProperties.Razorpay razorpay = properties.razorpay();
            if (!StringUtils.hasText(razorpay.keyId()) || !StringUtils.hasText(razorpay.keySecret())) {
                throw new IllegalStateException("Razorpay mode needs grocery.payment.razorpay.key-id and key-secret "
                        + "(RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET)");
            }
            if (!StringUtils.hasText(razorpay.webhookSecret())) {
                log.warn("RAZORPAY_WEBHOOK_SECRET is not set: every Razorpay webhook will be rejected (400)");
            }
            if (razorpay.keyId().startsWith("rzp_live_")) {
                log.warn("Razorpay LIVE key configured: real money will move");
            }
            log.info("Payment provider: RAZORPAY ({}, key {})", razorpay.baseUrl(), razorpay.keyId());
        }
        return mode;
    }

    @Bean
    ProviderCredentials providerCredentials(ProviderMode mode, PaymentProperties properties) {
        if (mode == ProviderMode.SIMULATOR) {
            return new ProviderCredentials(SimulatorGateway.KEY_ID, SimulatorGateway.KEY_SECRET,
                    SimulatorGateway.WEBHOOK_SECRET);
        }
        PaymentProperties.Razorpay razorpay = properties.razorpay();
        return new ProviderCredentials(razorpay.keyId(), razorpay.keySecret(), razorpay.webhookSecret());
    }

    @Bean
    PaymentGateway paymentGateway(ProviderMode mode, PaymentProperties properties,
                                  ObjectProvider<RestClient.Builder> restClientBuilder,
                                  ObjectProvider<CircuitBreakerFactory<?, ?>> breakers,
                                  ObjectProvider<JsonMapper> jsonMapper) {
        if (mode == ProviderMode.SIMULATOR) {
            return new SimulatorGateway();
        }
        return new RazorpayGateway(restClientBuilder.getIfAvailable(RestClient::builder), properties,
                breakers.getObject(), jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()));
    }
}
