package com.grocery.common.kafka;

import com.grocery.common.autoconfigure.GroceryKafkaAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRetryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, GroceryKafkaAutoConfiguration.class))
            .withPropertyValues("grocery.events.avro.enabled=false");

    @Test
    void theDefaultsAreThePolicyOfEarlierVersions() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DefaultErrorHandler.class);
            KafkaRetryProperties retry = context.getBean(KafkaRetryProperties.class);
            assertThat(retry.getMaxAttempts()).isEqualTo(4);
            assertThat(retry.getInitialInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(retry.getMultiplier()).isEqualTo(2.0);
            assertThat(retry.getMaxInterval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(retry.getOutageMaxInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(retry.getOutageMaxElapsed()).isEqualTo(Duration.ofMinutes(30));

            // processing errors: 1 s, 2 s, 4 s, 8 s, then the DLT
            assertThat(waits(retry.processingBackOff(), 10)).containsExactly(1_000L, 2_000L, 4_000L, 8_000L);
            // outages: 1 s, 2 s, 4 s ... capped at 30 s, for 30 minutes
            ExponentialBackOff outage = retry.outageBackOff();
            assertThat(outage.getInitialInterval()).isEqualTo(1_000);
            assertThat(outage.getMultiplier()).isEqualTo(2.0);
            assertThat(outage.getMaxInterval()).isEqualTo(30_000);
            assertThat(outage.getMaxElapsedTime()).isEqualTo(1_800_000);
            assertThat(waits(outage, 8)).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L);
        });
    }

    @Test
    void thePolicyIsConfigurable() {
        runner.withPropertyValues("grocery.kafka.retry.max-attempts=2", "grocery.kafka.retry.initial-interval=500ms",
                        "grocery.kafka.retry.multiplier=3", "grocery.kafka.retry.max-interval=1s",
                        "grocery.kafka.retry.outage-max-interval=5s", "grocery.kafka.retry.outage-max-elapsed=1m")
                .run(context -> {
                    assertThat(context).hasSingleBean(DefaultErrorHandler.class);
                    KafkaRetryProperties retry = context.getBean(KafkaRetryProperties.class);
                    assertThat(waits(retry.processingBackOff(), 10)).containsExactly(500L, 1_000L);
                    ExponentialBackOff outage = retry.outageBackOff();
                    assertThat(outage.getMaxElapsedTime()).isEqualTo(60_000);
                    assertThat(waits(outage, 4)).containsExactly(500L, 1_500L, 4_500L, 5_000L);
                });
    }

    @Test
    void anInvalidPolicyFailsTheStart() {
        runner.withPropertyValues("grocery.kafka.retry.multiplier=0.5").run(context -> assertThat(context).hasFailed());
    }

    private static List<Long> waits(BackOff backOff, int max) {
        BackOffExecution execution = backOff.start();
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            long next = execution.nextBackOff();
            if (next == BackOffExecution.STOP) {
                break;
            }
            waits.add(next);
        }
        return waits;
    }
}
