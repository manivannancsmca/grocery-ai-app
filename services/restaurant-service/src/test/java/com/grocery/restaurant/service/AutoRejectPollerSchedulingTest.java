package com.grocery.restaurant.service;

import com.grocery.restaurant.config.RestaurantConfig;
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
 * The poller's scheduling without a database (the integration tests switch it off and run pollers themselves): the
 * interval is a duration string ({@code grocery.fulfillment.poll-interval}, default {@code 15s}), and
 * {@code grocery.fulfillment.auto-reject-enabled=false} leaves the instance without a poller.
 */
class AutoRejectPollerSchedulingTest {

    private final StoreOrderService storeOrders = mock(StoreOrderService.class);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RestaurantConfig.class)
                .withBean(StoreOrderService.class, () -> storeOrders)
                .withBean(AutoRejectPoller.class);
    }

    @Test
    void runsEveryIntervalWithTheConfiguredBatchSize() {
        runner().withPropertyValues("grocery.fulfillment.poll-interval=100ms", "grocery.fulfillment.poll-batch-size=7")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                    verify(storeOrders, timeout(5_000).atLeast(2)).autoRejectExpired(any(Instant.class), eq(7));
                });
    }

    @Test
    void theDefaultIntervalIsFifteenSeconds() {
        runner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);   // "15s" parses
            Thread.sleep(300);
            verify(storeOrders, never()).autoRejectExpired(any(Instant.class), anyInt());   // first run after 15 s
        });
    }

    @Test
    void canBeSwitchedOffPerInstance() {
        runner().withPropertyValues("grocery.fulfillment.auto-reject-enabled=false",
                        "grocery.fulfillment.poll-interval=50ms")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    Thread.sleep(300);
                    verify(storeOrders, never()).autoRejectExpired(any(Instant.class), anyInt());
                });
    }

    @Test
    void invalidSettingsFailTheStartup() {
        runner().withPropertyValues("grocery.fulfillment.acceptance-timeout=0s")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("grocery.fulfillment.timezone=Mars/Olympus")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("grocery.fulfillment.default-ready-in-minutes=200")
                .run(context -> assertThat(context).hasFailed());
    }
}
