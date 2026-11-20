package com.grocery.common.autoconfigure;

import com.grocery.common.observability.GroceryEventMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GroceryObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GroceryObservabilityAutoConfiguration.class,
                    MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class))
            .withPropertyValues("spring.application.name=order-service", "GROCERY_ENV=dev", "HOSTNAME=order-1a2b");

    @Test
    void everyMeterCarriesServiceEnvAndInstance() {
        runner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.counter("any.meter").getId().getTags()).containsExactlyInAnyOrder(
                    Tag.of("service", "order-service"), Tag.of("env", "dev"), Tag.of("instance", "order-1a2b"));

            GroceryEventMetrics metrics = context.getBean(GroceryEventMetrics.class);
            assertThat(metrics.registry()).isSameAs(registry);
            metrics.published("order.events", "OrderPlaced");
            assertThat(registry.get("grocery.events.published").tags("topic", "order.events", "event_type", "OrderPlaced",
                    "service", "order-service").counter().count()).isOne();
        });
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("grocery.observability.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(GroceryEventMetrics.class)
                    .doesNotHaveBean("groceryCommonTagsCustomizer");
            assertThat(context.getBean(MeterRegistry.class).counter("any.meter").getId().getTags()).isEmpty();
        });
    }

    @Test
    void withoutMicrometersRegistryTheMetricsAreANoOp() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GroceryObservabilityAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(GroceryEventMetrics.class).registry()).isNull());
    }
}
