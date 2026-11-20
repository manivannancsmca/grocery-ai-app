package com.grocery.common.autoconfigure;

import com.grocery.common.deadletter.DeadLetterController;
import com.grocery.common.deadletter.DeadLetterProperties;
import com.grocery.common.deadletter.DeadLetterReader;
import com.grocery.common.deadletter.DeadLetterService;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GroceryDeadLetterAutoConfigurationTest {

    private static final AutoConfigurations CONFIGURATIONS = AutoConfigurations.of(KafkaAutoConfiguration.class,
            GroceryAvroAutoConfiguration.class, GroceryKafkaAutoConfiguration.class, GroceryDeadLetterAutoConfiguration.class);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(CONFIGURATIONS)
            .withPropertyValues("grocery.events.avro.schema-registry-url=mock://dead-letter-autoconfig",
                    "spring.kafka.bootstrap-servers=127.0.0.1:1");

    @AfterEach
    void dropScope() {
        MockSchemaRegistry.dropScope("dead-letter-autoconfig");
    }

    @Test
    void servletServicesWithKafkaGetTheApi() {
        runner.withPropertyValues("spring.kafka.listener.auto-startup=false").run(context -> {
            assertThat(context).hasSingleBean(DeadLetterService.class)
                    .hasSingleBean(DeadLetterController.class)
                    .hasSingleBean(DeadLetterReader.class);
            DeadLetterProperties properties = context.getBean(DeadLetterProperties.class);
            assertThat(properties.getActionsTopic()).isEqualTo("_grocery.dead-letter-actions");
            assertThat(properties.getExcludedTopics()).containsExactly("delivery.location");
            assertThat(properties.getMaxRecords()).isEqualTo(2000);
            assertThat(properties.getCatchUpTimeout()).isEqualTo(Duration.ofSeconds(3));
            // the reader follows the listener containers: service tests that do not start them do not start it
            assertThat(context.getBean(DeadLetterReader.class).isAutoStartup()).isFalse();
            assertThat(context.getBean(DeadLetterReader.class).isRunning()).isFalse();
        });
    }

    @Test
    void theReaderStartsWithTheApplicationByDefaultAndCanBeForced() {
        runner.withPropertyValues("grocery.dead-letters.auto-startup=false").run(context ->
                assertThat(context.getBean(DeadLetterReader.class).isAutoStartup()).isFalse());
        runner.withPropertyValues("spring.kafka.listener.auto-startup=false", "grocery.dead-letters.auto-startup=true")
                .run(context -> assertThat(context.getBean(DeadLetterReader.class).isRunning()).isTrue());
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("grocery.dead-letters.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(DeadLetterService.class).doesNotHaveBean(DeadLetterController.class)
                        .doesNotHaveBean(DeadLetterReader.class));
    }

    @Test
    void notInNonWebApplications() {
        new ApplicationContextRunner().withConfiguration(CONFIGURATIONS)
                .withPropertyValues("grocery.events.avro.schema-registry-url=mock://dead-letter-autoconfig")
                .run(context -> assertThat(context).doesNotHaveBean(DeadLetterService.class)
                        .doesNotHaveBean(DeadLetterReader.class));
    }
}
