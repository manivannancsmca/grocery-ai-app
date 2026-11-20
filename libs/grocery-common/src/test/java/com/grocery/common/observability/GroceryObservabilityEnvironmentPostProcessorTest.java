package com.grocery.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroceryObservabilityEnvironmentPostProcessorTest {

    private static MockEnvironment process(MockEnvironment environment) {
        new GroceryObservabilityEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }

    @Test
    void addsThePlatformDefaults() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("HOSTNAME", "order-7f9c"));

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,prometheus");
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://localhost:4318/v1/traces");
        assertThat(environment.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.kafka.template.observation-enabled")).isEqualTo("true");
        assertThat(environment.getProperty("logging.structured.ecs.service.environment")).isEqualTo("local");
        assertThat(environment.getProperty("logging.structured.ecs.service.node-name")).isEqualTo("order-7f9c");
        assertThat(environment.getProperty("management.opentelemetry.resource-attributes[deployment.environment.name]"))
                .isEqualTo("local");
        assertThat(environment.getProperty("management.opentelemetry.resource-attributes[service.instance.id]"))
                .isEqualTo("order-7f9c");
        assertThat(environment.getProperty("management.tracing.export.enabled")).isNull();
        for (String timer : List.of("http.server.requests", "grocery.orders.placement", "grocery.ai.model.latency")) {
            assertThat(environment.getProperty("management.metrics.distribution.slo[" + timer + "]"))
                    .isEqualTo("50ms,100ms,250ms,500ms,1s,2s,5s");
        }
        // the lowest precedence
        assertThat(environment.getPropertySources().stream().reduce((first, second) -> second).orElseThrow().getName())
                .isEqualTo(GroceryObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    void environmentVariablesFeedTheDefaults() {
        MockEnvironment environment = process(new MockEnvironment()
                .withProperty("GROCERY_ENV", "Prod")
                .withProperty("TRACING_SAMPLING_PROBABILITY", "0.1")
                .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318/"));

        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
        assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("http://otel-collector:4318/v1/traces");
        assertThat(environment.getProperty("logging.structured.ecs.service.environment")).isEqualTo("prod");
    }

    @Test
    void theLatencyBucketsCanBeChangedForEveryTimerAtOnce() {
        MockEnvironment environment = process(new MockEnvironment()
                .withProperty("grocery.observability.latency-slo", "100ms,1s"));
        assertThat(environment.getProperty("management.metrics.distribution.slo[grocery.orders.placement]"))
                .isEqualTo("100ms,1s");
    }

    @Test
    void theServicesOwnSettingsWin() {
        MockEnvironment environment = process(new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", "health,info")
                .withProperty("spring.kafka.listener.observation-enabled", "false")
                .withProperty("management.tracing.sampling.probability", "0.5"));

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info");
        assertThat(environment.getProperty("spring.kafka.listener.observation-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.5");
    }

    @Test
    void theSpringBoot3SwitchStillTurnsTracingExportOff() {
        assertThat(process(new MockEnvironment().withProperty("management.tracing.enabled", "false"))
                .getProperty("management.tracing.export.enabled")).isEqualTo("false");
        // the Boot 4 property, when set, decides
        assertThat(process(new MockEnvironment().withProperty("management.tracing.enabled", "false")
                .withProperty("management.tracing.export.enabled", "true"))
                .getProperty("management.tracing.export.enabled")).isEqualTo("true");
        assertThat(process(new MockEnvironment().withProperty("management.tracing.enabled", "true"))
                .getProperty("management.tracing.export.enabled")).isNull();
    }

    @Test
    void switchedOffAddsNothing() {
        MockEnvironment environment = process(new MockEnvironment().withProperty("grocery.observability.enabled", "false"));

        assertThat(environment.getPropertySources().contains(GroceryObservabilityEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isNull();
    }
}
