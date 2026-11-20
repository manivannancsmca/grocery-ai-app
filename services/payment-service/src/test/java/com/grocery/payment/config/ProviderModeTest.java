package com.grocery.payment.config;

import com.grocery.payment.gateway.PaymentGateway;
import com.grocery.payment.gateway.RazorpayGateway;
import com.grocery.payment.gateway.SimulatorGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider selection at startup ({@link PaymentGatewayConfig}): Razorpay when a key id is configured, otherwise the
 * simulator — which refuses to start without the {@code docker} or {@code local} profile.
 */
class ProviderModeTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentProperties.class)
    static class Properties {

        @Bean
        Resilience4JCircuitBreakerFactory circuitBreakerFactory() {
            return new Resilience4JCircuitBreakerFactory(CircuitBreakerRegistry.ofDefaults(),
                    TimeLimiterRegistry.ofDefaults(), null);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, PaymentGatewayConfig.class);

    @Test
    void noKeysAndNoLocalProfileRefusesToStart() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("only allowed with the 'docker' or 'local' profile");
        });
        runner.withPropertyValues("grocery.payment.provider=simulator").run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void noKeysWithTheLocalOrDockerProfileRunsTheSimulator() {
        for (String profile : new String[]{"local", "docker"}) {
            runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile)).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(ProviderMode.class)).isEqualTo(ProviderMode.SIMULATOR);
                assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(SimulatorGateway.class);
                ProviderCredentials credentials = context.getBean(ProviderCredentials.class);
                assertThat(credentials.keyId()).isEqualTo("rzp_test_simulator");
                assertThat(credentials.keySecret()).isEqualTo("simulator_key_secret");
                assertThat(credentials.webhookSecret()).isEqualTo("simulator_webhook_secret");
                assertThat(credentials.toString()).doesNotContain("simulator_key_secret");
            });
        }
    }

    @Test
    void testsMayAllowTheSimulatorExplicitly() {
        runner.withPropertyValues("grocery.payment.provider=simulator",
                "grocery.payment.simulator.allow-any-profile=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProviderMode.class)).isEqualTo(ProviderMode.SIMULATOR);
        });
    }

    @Test
    void aKeyIdSelectsRazorpay() {
        runner.withPropertyValues("grocery.payment.razorpay.key-id=rzp_test_abc",
                "grocery.payment.razorpay.key-secret=secret", "grocery.payment.razorpay.webhook-secret=wh").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProviderMode.class)).isEqualTo(ProviderMode.RAZORPAY);
            assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(RazorpayGateway.class);
            assertThat(context.getBean(ProviderCredentials.class).keyId()).isEqualTo("rzp_test_abc");
        });
        // an explicit provider wins over the keys
        runner.withPropertyValues("grocery.payment.razorpay.key-id=rzp_test_abc", "grocery.payment.provider=simulator",
                "grocery.payment.simulator.allow-any-profile=true").run(context ->
                assertThat(context.getBean(ProviderMode.class)).isEqualTo(ProviderMode.SIMULATOR));
    }

    @Test
    void razorpayWithoutSecretOrAnUnknownProviderRefusesToStart() {
        runner.withPropertyValues("grocery.payment.razorpay.key-id=rzp_test_abc").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("key-secret");
        });
        runner.withPropertyValues("grocery.payment.provider=stripe").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("'razorpay' or 'simulator'");
        });
    }
}
