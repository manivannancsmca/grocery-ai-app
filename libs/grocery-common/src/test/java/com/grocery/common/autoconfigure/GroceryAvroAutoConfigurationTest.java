package com.grocery.common.autoconfigure;

import com.grocery.common.avro.AvroEventDeserializer;
import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.avro.AvroSchemaWarmUp;
import com.grocery.common.avro.GroceryAvroProperties;
import com.grocery.common.avro.JsonAvroConverter;
import com.grocery.common.kafka.OriginalValueDeadLetterPublishingRecoverer;
import com.grocery.common.outbox.OutboxPublisher;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class GroceryAvroAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, GroceryCommonAutoConfiguration.class,
                    GroceryAvroAutoConfiguration.class, GroceryKafkaAutoConfiguration.class))
            .withBean(DataSource.class, () -> new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/never-connected"))
            .withPropertyValues("grocery.events.avro.schema-registry-url=mock://autoconfig");

    @AfterEach
    void dropScope() {
        MockSchemaRegistry.dropScope("autoconfig");
    }

    @Test
    void avroIsOnByDefaultForProducersAndConsumers() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SchemaRegistryClient.class)
                    .hasSingleBean(AvroEventEncoder.class)
                    .hasSingleBean(JsonAvroConverter.class)
                    .hasSingleBean(AvroSchemaWarmUp.class)
                    .hasSingleBean(OriginalValueDeadLetterPublishingRecoverer.class)
                    .hasSingleBean(DefaultErrorHandler.class)
                    .hasBean("groceryAvroConsumerFactoryCustomizer");
            GroceryAvroProperties properties = context.getBean(GroceryAvroProperties.class);
            assertThat(properties.isAutoRegisterSchemas()).isFalse();

            DefaultKafkaConsumerFactory<?, ?> consumerFactory = context.getBean(DefaultKafkaConsumerFactory.class);
            assertThat(consumerFactory.getValueDeserializer()).isInstanceOf(ErrorHandlingDeserializer.class);
            assertThat(ReflectionTestUtils.getField(consumerFactory.getValueDeserializer(), "delegate"))
                    .isInstanceOf(AvroEventDeserializer.class);
            assertThat(consumerFactory.getKeyDeserializer()).isNull();             // keys: Boot's StringDeserializer
            assertThat(consumerFactory.getConfigurationProperties().get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG))
                    .isEqualTo(StringDeserializer.class);

            assertThat(ReflectionTestUtils.getField(context.getBean(OutboxPublisher.class), "avroEncoder"))
                    .isSameAs(context.getBean(AvroEventEncoder.class));
        });
    }

    @Test
    void theRollbackSwitchRestoresTheJsonOnlyBeans() {
        runner.withPropertyValues("grocery.events.avro.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SchemaRegistryClient.class)
                    .doesNotHaveBean(AvroEventEncoder.class)
                    .doesNotHaveBean(AvroSchemaWarmUp.class)
                    .doesNotHaveBean(OriginalValueDeadLetterPublishingRecoverer.class)
                    .doesNotHaveBean(DefaultKafkaConsumerFactoryCustomizer.class)
                    .hasSingleBean(DefaultErrorHandler.class)
                    .hasSingleBean(OutboxPublisher.class);
            DefaultKafkaConsumerFactory<?, ?> consumerFactory = context.getBean(DefaultKafkaConsumerFactory.class);
            assertThat(consumerFactory.getValueDeserializer()).isNull();
            assertThat(consumerFactory.getConfigurationProperties().get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                    .isEqualTo(StringDeserializer.class);
            assertThat(ReflectionTestUtils.getField(context.getBean(OutboxPublisher.class), "avroEncoder")).isNull();
        });
    }

    @Test
    void aServiceErrorHandlerWinsButValuesAreStillDecoded() {
        CommonErrorHandler own = new DefaultErrorHandler();
        runner.withBean("ownErrorHandler", CommonErrorHandler.class, () -> own).run(context -> {
            assertThat(context).doesNotHaveBean(OriginalValueDeadLetterPublishingRecoverer.class);
            assertThat(context.getBean(CommonErrorHandler.class)).isSameAs(own);
            assertThat(context.getBean(DefaultKafkaConsumerFactory.class).getValueDeserializer())
                    .isInstanceOf(ErrorHandlingDeserializer.class);
        });
    }

    @Test
    void aServiceSpecificValueDeserializerIsKept() {
        runner.withPropertyValues("spring.kafka.consumer.value-deserializer=" + ByteArrayDeserializer.class.getName())
                .run(context -> assertThat(context.getBean(DefaultKafkaConsumerFactory.class).getValueDeserializer()).isNull());
    }

    @Test
    void registryClientSettingsAreBound() {
        runner.withPropertyValues("grocery.events.avro.auto-register-schemas=true",
                        "grocery.events.avro.client.basic.auth.credentials.source=USER_INFO",
                        "grocery.events.avro.client.basic.auth.user.info=svc:secret")
                .run(context -> {
                    GroceryAvroProperties properties = context.getBean(GroceryAvroProperties.class);
                    assertThat(properties.isAutoRegisterSchemas()).isTrue();
                    assertThat(properties.getClient()).containsEntry("basic.auth.credentials.source", "USER_INFO")
                            .containsEntry("basic.auth.user.info", "svc:secret");
                });
    }
}
