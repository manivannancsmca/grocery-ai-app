package com.grocery.common.autoconfigure;

import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.correlation.CorrelationIdFilter;
import com.grocery.common.idempotency.IdempotencyKeys;
import com.grocery.common.idempotency.JdbcProcessedEventGuard;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.observability.GroceryEventMetrics;
import com.grocery.common.observability.TraceParent;
import io.micrometer.tracing.Tracer;
import com.grocery.common.outbox.JdbcOutboxPublisher;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.common.web.DataAccessExceptionAdvice;
import com.grocery.common.web.GlobalExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Registers the grocery-common building blocks in any service that depends on the library.
 * Every bean backs off if the service declares its own, and each group can be switched off
 * with {@code grocery.common.<group>.enabled=false}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"
})
@EnableConfigurationProperties(GroceryCommonProperties.class)
public class GroceryCommonAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GroceryCommonAutoConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({JdbcClient.class, JsonMapper.class})
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnProperty(prefix = "grocery.common.outbox", name = "enabled", matchIfMissing = true)
    static class OutboxConfiguration {

        /**
         * Writes {@code payload} (JSON) and, while Avro is on ({@code grocery.events.avro.enabled}, default true),
         * {@code payload_avro}; with Avro off it is exactly the 0.5.0 publisher. Since 0.9.0 also the current span's
         * {@code traceparent} (where the table has the column) and {@code grocery_events_published_total} on commit.
         */
        @Bean
        @ConditionalOnMissingBean
        OutboxPublisher outboxPublisher(DataSource dataSource, ObjectProvider<JsonMapper> jsonMapper,
                                        ObjectProvider<AvroEventEncoder> avroEventEncoder, Environment environment,
                                        ObjectProvider<Tracer> tracer, ObjectProvider<GroceryEventMetrics> metrics) {
            AvroEventEncoder encoder = avroEventEncoder.getIfAvailable();
            if (encoder == null && environment.getProperty("grocery.events.avro.enabled", Boolean.class, true)) {
                log.warn("grocery.events.avro.enabled is true but no AvroEventEncoder is available (Confluent / Avro /"
                        + " Kafka client classes missing?): the outbox writes the JSON payload only");
            }
            return new JdbcOutboxPublisher(
                    JdbcClient.create(dataSource),
                    jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()),
                    Clock.systemUTC(),
                    encoder,
                    dataSource,
                    TraceParent.supplier(tracer.getIfUnique()),
                    metrics.getIfAvailable(GroceryEventMetrics::noop));
        }

        @Bean
        @ConditionalOnMissingBean
        ProcessedEventGuard processedEventGuard(DataSource dataSource) {
            return new JdbcProcessedEventGuard(JdbcClient.create(dataSource), Clock.systemUTC());
        }

        /** Needs the {@code idempotency_key} table only when a service actually calls it. */
        @Bean
        @ConditionalOnMissingBean
        IdempotencyKeys idempotencyKeys(DataSource dataSource, ObjectProvider<JsonMapper> jsonMapper) {
            return new IdempotencyKeys(JdbcClient.create(dataSource),
                    jsonMapper.getIfAvailable(() -> JsonMapper.builder().build()), Clock.systemUTC());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class ServletWebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "grocery.common.correlation", name = "enabled", matchIfMissing = true)
        CorrelationIdFilter correlationIdFilter() {
            return new CorrelationIdFilter();
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "grocery.common.error-handling", name = "enabled", matchIfMissing = true)
        GlobalExceptionHandler groceryGlobalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.dao.OptimisticLockingFailureException")
    @ConditionalOnProperty(prefix = "grocery.common.error-handling", name = "enabled", matchIfMissing = true)
    static class DataAccessWebConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DataAccessExceptionAdvice dataAccessExceptionAdvice() {
            return new DataAccessExceptionAdvice();
        }
    }
}
