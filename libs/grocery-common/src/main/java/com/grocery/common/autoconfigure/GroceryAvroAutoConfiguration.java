package com.grocery.common.autoconfigure;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.AvroEventSchemas;
import com.grocery.common.avro.AvroSchemaWarmUp;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.avro.SchemaRegistryAvroEventEncoder;
import com.grocery.common.avro.SchemaRegistrySupport;
import com.grocery.common.kafka.OriginalValueDeadLetterPublishingRecoverer;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

/**
 * Avro on the wire with centrally managed schemas ({@code grocery.events.avro.*}, on by default). Listeners, payload
 * classes and business code do not change: producers keep handing JSON-able payloads to the outbox, listeners keep
 * receiving {@code ConsumerRecord<String, String>} with the JSON payload.
 * <ul>
 *   <li>producer side: {@link AvroEventEncoder} (used by the outbox for {@code payload_avro}) + the start-up
 *       {@link AvroSchemaWarmUp};</li>
 *   <li>consumer side: every consumer created by Spring Boot's consumer factory reads values with
 *       {@code ErrorHandlingDeserializer(AvroEventDeserializer)} (Avro or legacy JSON -&gt; JSON string); keys stay
 *       {@code StringDeserializer};</li>
 *   <li>errors: {@link GroceryKafkaAutoConfiguration}'s error handler retries a registry outage, dead-letters poison
 *       at once, and dead-letters with the original value bytes through {@link OriginalValueDeadLetterPublishingRecoverer}.</li>
 * </ul>
 * {@code grocery.events.avro.enabled=false} removes all of it: the beans are exactly those of grocery-common 0.5.0.
 * Every bean backs off if the service declares its own.
 */
@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        before = {GroceryKafkaAutoConfiguration.class, GroceryCommonAutoConfiguration.class})
@ConditionalOnClass(name = {
        "io.confluent.kafka.serializers.KafkaAvroSerializer",
        "org.apache.avro.Schema",
        "org.apache.kafka.common.serialization.Serializer",
        "tools.jackson.databind.json.JsonMapper"})
@ConditionalOnProperty(prefix = "grocery.events.avro", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(GroceryAvroProperties.class)
public class GroceryAvroAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GroceryAvroAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    JsonAvroConverter groceryJsonAvroConverter() {
        return new JsonAvroConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    AvroEventSchemas groceryAvroEventSchemas() {
        return new AvroEventSchemas();
    }

    /** One client per service (shared caches); closed on shutdown. */
    @Bean
    @ConditionalOnMissingBean
    SchemaRegistryClient grocerySchemaRegistryClient(GroceryAvroProperties properties) {
        return SchemaRegistrySupport.newClient(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    AvroEventEncoder groceryAvroEventEncoder(SchemaRegistryClient grocerySchemaRegistryClient,
                                             GroceryAvroProperties properties, AvroEventSchemas schemas,
                                             JsonAvroConverter converter) {
        return new SchemaRegistryAvroEventEncoder(grocerySchemaRegistryClient, properties, schemas, converter);
    }

    @Bean
    @ConditionalOnMissingBean
    AvroSchemaWarmUp groceryAvroSchemaWarmUp(SchemaRegistryClient grocerySchemaRegistryClient,
                                             GroceryAvroProperties properties, AvroEventSchemas schemas) {
        return new AvroSchemaWarmUp(grocerySchemaRegistryClient, schemas, properties.isAutoRegisterSchemas());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.springframework.kafka.core.KafkaTemplate",
            "org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer"})
    static class AvroConsumerConfiguration {

        /**
         * Installs {@code ErrorHandlingDeserializer(AvroEventDeserializer)} as the value deserializer of Spring
         * Boot's consumer factory, unless the service configured another value deserializer than Boot's default
         * StringDeserializer.
         */
        @Bean
        @ConditionalOnMissingBean(name = "groceryAvroConsumerFactoryCustomizer")
        DefaultKafkaConsumerFactoryCustomizer groceryAvroConsumerFactoryCustomizer(
                SchemaRegistryClient grocerySchemaRegistryClient, GroceryAvroProperties properties,
                JsonAvroConverter converter) {
            return factory -> {
                Object configured = factory.getConfigurationProperties().get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
                boolean stringDefault = configured == null || configured == StringDeserializer.class
                        || StringDeserializer.class.getName().equals(configured);
                if (!stringDefault || factory.getValueDeserializer() != null) {
                    log.info("Avro value deserializer not installed: the consumer factory already uses {}",
                            factory.getValueDeserializer() != null
                                    ? factory.getValueDeserializer().getClass().getName() : configured);
                    return;
                }
                @SuppressWarnings("unchecked")
                DefaultKafkaConsumerFactory<Object, String> consumerFactory = (DefaultKafkaConsumerFactory<Object, String>) factory;
                consumerFactory.setValueDeserializerSupplier(() -> new ErrorHandlingDeserializer<String>(
                        new AvroEventDeserializer(grocerySchemaRegistryClient, properties, converter)));
            };
        }

        /** Used by {@link GroceryKafkaAutoConfiguration#groceryKafkaErrorHandler} for every dead letter. */
        @Bean
        @ConditionalOnMissingBean({CommonErrorHandler.class, OriginalValueDeadLetterPublishingRecoverer.class})
        @ConditionalOnSingleCandidate(ProducerFactory.class)
        OriginalValueDeadLetterPublishingRecoverer groceryDeadLetterRecoverer(ProducerFactory<?, ?> producerFactory) {
            return OriginalValueDeadLetterPublishingRecoverer.withOwnProducer(
                    producerFactory.getConfigurationProperties(), GroceryKafkaAutoConfiguration::deadLetterTopic);
        }
    }
}
