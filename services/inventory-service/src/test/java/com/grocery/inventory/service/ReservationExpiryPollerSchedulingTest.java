package com.grocery.inventory.service;

import com.grocery.inventory.config.InventoryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The poller's scheduling without a database (the integration tests switch it off and run pollers themselves):
 * the interval is a duration string ({@code grocery.inventory.expiry.interval}, default {@code 30s}), and
 * {@code grocery.inventory.expiry.enabled=false} leaves the instance without a poller.
 */
class ReservationExpiryPollerSchedulingTest {

    private final ReservationService reservations = mock(ReservationService.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(InventoryConfig.class)
                .withBean(ReservationService.class, () -> reservations)
                .withBean(ReservationExpiryPoller.class);
    }

    @Test
    void runsEveryIntervalWithTheConfiguredBatchSize() {
        runner().withPropertyValues("grocery.inventory.expiry.interval=100ms",
                        "grocery.inventory.expiry.batch-size=7")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                    verify(reservations, timeout(5_000).atLeast(2)).releaseExpired(any(Instant.class), eq(7));
                });
    }

    @Test
    void theDefaultIntervalIsThirtySeconds() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);         // "30s" parses
            Thread.sleep(300);
            verify(reservations, never()).releaseExpired(any(Instant.class), anyInt());   // first run after 30 s
        });
    }

    @Test
    void canBeSwitchedOffPerInstance() {
        runner().withPropertyValues("grocery.inventory.expiry.enabled=false",
                        "grocery.inventory.expiry.interval=50ms")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    Thread.sleep(300);
                    verify(reservations, never()).releaseExpired(any(Instant.class), anyInt());
                });
    }
}
