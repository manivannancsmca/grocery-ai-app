package com.grocery.common.autoconfigure;

import com.grocery.common.deadletter.DeadLetterActionLog;
import com.grocery.common.deadletter.DeadLetterController;
import com.grocery.common.deadletter.DeadLetterIndex;
import com.grocery.common.deadletter.DeadLetterListeners;
import com.grocery.common.deadletter.DeadLetterMetrics;
import com.grocery.common.observability.GroceryEventMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import com.grocery.common.deadletter.DeadLetterPayloadDecoder;
import com.grocery.common.deadletter.DeadLetterProperties;
import com.grocery.common.deadletter.DeadLetterReader;
import com.grocery.common.deadletter.DeadLetterReplayer;
import com.grocery.common.deadletter.DeadLetterService;
import com.grocery.common.deadletter.KafkaDeadLetterActionLog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Dead letters — inspect, replay, discard (grocery-common 0.7.0, contract Phase 10 §1): {@code /api/admin/dead-letters}
 * for this service's own consumer groups, a background {@link DeadLetterReader} per instance, states in the compacted
 * topic {@code _grocery.dead-letter-actions} (so services without a database work the same way).
 *
 * <p>Active in servlet services with Spring Kafka listeners and Spring Security on the classpath;
 * {@code grocery.dead-letters.enabled=false} switches it off. Every bean backs off if the service declares its own.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration"
}, after = {GroceryAvroAutoConfiguration.class, GroceryKafkaAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.kafka.config.KafkaListenerEndpointRegistry",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.security.core.context.SecurityContextHolder",
        "tools.jackson.databind.json.JsonMapper"})
@ConditionalOnProperty(prefix = "grocery.dead-letters", name = "enabled", matchIfMissing = true)
@ConditionalOnBean(KafkaListenerEndpointRegistry.class)
@ConditionalOnSingleCandidate(ConsumerFactory.class)
@EnableConfigurationProperties(DeadLetterProperties.class)
public class GroceryDeadLetterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DeadLetterIndex groceryDeadLetterIndex(DeadLetterProperties properties) {
        return new DeadLetterIndex(properties.getMaxRecords());
    }

    @Bean
    @ConditionalOnMissingBean
    DeadLetterListeners groceryDeadLetterListeners(KafkaListenerEndpointRegistry registry, DeadLetterProperties properties) {
        return new DeadLetterListeners(registry, properties.getExcludedTopics());
    }

    @Bean
    @ConditionalOnMissingBean
    DeadLetterPayloadDecoder groceryDeadLetterPayloadDecoder(ConsumerFactory<?, ?> consumerFactory) {
        return new DeadLetterPayloadDecoder(consumerFactory);
    }

    /** Starts with the application unless {@code grocery.dead-letters.auto-startup} / listener auto-startup is false. */
    @Bean
    @ConditionalOnMissingBean
    DeadLetterReader groceryDeadLetterReader(ConsumerFactory<?, ?> consumerFactory, DeadLetterListeners listeners,
                                             DeadLetterIndex index, DeadLetterProperties properties,
                                             Environment environment) {
        boolean autoStartup = properties.getAutoStartup() != null ? properties.getAutoStartup()
                : environment.getProperty("spring.kafka.listener.auto-startup", Boolean.class, true);
        return new DeadLetterReader(consumerFactory.getConfigurationProperties(), listeners, index, properties, autoStartup);
    }

    /** Since 0.9.0: replays run inside the record's trace and count in {@code grocery_events_consumed_total}. */
    @Bean
    @ConditionalOnMissingBean
    DeadLetterReplayer groceryDeadLetterReplayer(DeadLetterListeners listeners, DeadLetterPayloadDecoder decoder,
                                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                                 ObjectProvider<GroceryEventMetrics> metrics) {
        return new DeadLetterReplayer(listeners, decoder, observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP),
                metrics.getIfAvailable(GroceryEventMetrics::noop));
    }

    /** {@code grocery_dead_letters_pending{consumer_group}} (0.9.0), where Micrometer has a registry. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "grocery.observability", name = "enabled", matchIfMissing = true)
    DeadLetterMetrics groceryDeadLetterMetrics(DeadLetterIndex index, DeadLetterListeners listeners,
                                               MeterRegistry meterRegistry) {
        return new DeadLetterMetrics(index, listeners, meterRegistry);
    }

    /** The API: needs a producer for the states (configured like the application's). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnSingleCandidate(ProducerFactory.class)
    static class DeadLetterApiConfiguration {

        @Bean
        @ConditionalOnMissingBean(DeadLetterActionLog.class)
        KafkaDeadLetterActionLog groceryDeadLetterActionLog(ProducerFactory<?, ?> producerFactory,
                                                            DeadLetterProperties properties) {
            return new KafkaDeadLetterActionLog(producerFactory.getConfigurationProperties(), properties.getActionsTopic());
        }

        @Bean
        @ConditionalOnMissingBean
        DeadLetterService groceryDeadLetterService(DeadLetterIndex index, DeadLetterReader reader,
                                                   DeadLetterActionLog actionLog, DeadLetterReplayer replayer,
                                                   DeadLetterPayloadDecoder decoder, ObjectProvider<JsonMapper> jsonMapper) {
            return new DeadLetterService(index, reader, actionLog, replayer, decoder,
                    jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()), Clock.systemUTC());
        }

        @Bean
        @ConditionalOnMissingBean
        DeadLetterController groceryDeadLetterController(DeadLetterService service) {
            return new DeadLetterController(service);
        }
    }
}
