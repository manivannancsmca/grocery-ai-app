package com.grocery.common.autoconfigure;

import com.grocery.common.kafka.CorrelationRecordInterceptor;
import com.grocery.common.kafka.FailedEventCountingRecoverer;
import com.grocery.common.observability.GroceryEventMetrics;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.kafka.KafkaRetryProperties;
import com.grocery.common.kafka.OriginalValueDeadLetterPublishingRecoverer;
import com.grocery.common.kafka.TransientFailures;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer defaults for every service that uses Kafka:
 * <ul>
 *   <li>correlation/causation IDs from event headers into the MDC;</li>
 *   <li><b>blocking</b> retries with exponential backoff (1s, 2s, 4s, 8s) — keeps per-key ordering,
 *       which the order saga relies on;</li>
 *   <li>after the last retry, or immediately for {@link InvalidEventException}, the record goes to
 *       {@code <topic>.DLT} so the partition is never blocked by a poison message;</li>
 *   <li>outages ({@link TransientFailures}: database/search/Schema Registry unreachable, lock timeouts, I/O errors)
 *       retry the same record for up to 30 minutes instead, so a restart never dead-letters valid events.</li>
 * </ul>
 * Since 0.7.0 the numbers come from {@link KafkaRetryProperties} ({@code grocery.kafka.retry.*}); the defaults are the
 * policy above.
 * With Avro (grocery-common 0.6.0, {@link GroceryAvroAutoConfiguration}) value deserialization failures follow the
 * same rules: an unavailable Schema Registry is an outage (the same record is retried with the outage backoff), a
 * corrupt value or unknown schema id goes to the DLT at once, and every dead letter carries the record's ORIGINAL
 * value bytes ({@link OriginalValueDeadLetterPublishingRecoverer}), so a replay is byte-identical. Spring Kafka
 * classifies {@link DeserializationException} as not retryable before looking at its cause; that classification is
 * removed so the cause decides. With {@code grocery.events.avro.enabled=false} the handler is exactly the 0.5.0 one.
 * Spring Boot wires these beans into its listener container factory automatically.
 *
 * <p>Since 0.9.0 the interceptor and the recoverer also count {@code grocery_events_consumed_total} (APPLIED /
 * DUPLICATE when the listener returns, FAILED once a record is dead-lettered), and grocery-common's defaults switch
 * Spring Kafka's listener and template observation on ({@code spring.kafka.listener.observation-enabled},
 * {@code spring.kafka.template.observation-enabled}, see {@code GroceryObservabilityEnvironmentPostProcessor}): a
 * record with a {@code traceparent} header is handled in a child span of that trace.
 */
@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
@EnableConfigurationProperties(KafkaRetryProperties.class)
public class GroceryKafkaAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GroceryKafkaAutoConfiguration.class);

    /** Correlation MDC and (0.9.0) {@code grocery_events_consumed_total} APPLIED / DUPLICATE. */
    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    RecordInterceptor<Object, Object> correlationRecordInterceptor(ObjectProvider<GroceryEventMetrics> metrics) {
        return new CorrelationRecordInterceptor(metrics.getIfAvailable(GroceryEventMetrics::noop));
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(KafkaTemplate.class)
    DefaultErrorHandler groceryKafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate,
                                                 ObjectProvider<OriginalValueDeadLetterPublishingRecoverer> avroDeadLetterRecoverer,
                                                 KafkaRetryProperties retry, ObjectProvider<GroceryEventMetrics> metrics) {
        GroceryEventMetrics eventMetrics = metrics.getIfAvailable(GroceryEventMetrics::noop);
        OriginalValueDeadLetterPublishingRecoverer avroRecoverer = avroDeadLetterRecoverer.getIfAvailable();
        if (avroRecoverer != null) {
            return errorHandler(avroRecoverer, true, retry, eventMetrics);
        }
        return errorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate, GroceryKafkaAutoConfiguration::deadLetterTopic),
                false, retry, eventMetrics);
    }

    static DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer recoverer, boolean avroDeserialization,
                                            KafkaRetryProperties retry) {
        return errorHandler(recoverer, avroDeserialization, retry, GroceryEventMetrics.noop());
    }

    /**
     * @param avroDeserialization values are read by the Avro deserializer (inside an ErrorHandlingDeserializer):
     *                            deserialization failures are classified by their cause (outage or poison)
     * @param retry               the retry policy ({@code grocery.kafka.retry.*})
     * @param metrics             a dead-lettered record counts as FAILED in {@code grocery_events_consumed_total}
     */
    static DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer recoverer, boolean avroDeserialization,
                                            KafkaRetryProperties retry, GroceryEventMetrics metrics) {
        DefaultErrorHandler handler = new DefaultErrorHandler(new FailedEventCountingRecoverer(recoverer, metrics),
                retry.processingBackOff());
        handler.addNotRetryableExceptions(InvalidEventException.class);
        if (avroDeserialization) {
            // DeserializationException is "not retryable" by default and the classifier stops at the first
            // classified exception of the cause chain: without this, an unreachable Schema Registry would
            // dead-letter valid events. Its cause now decides: InvalidEventException -> DLT at once,
            // TransientEventProcessingException -> outage backoff; any other deserialization failure -> DLT at once.
            handler.removeClassification(DeserializationException.class);
            handler.setBackOffFunction((record, exception) -> TransientFailures.isTransient(exception) ? retry.outageBackOff()
                    : isDeserializationFailure(exception) ? noRetries() : null);
        } else {
            // Outages (database / search cluster / network) keep retrying the same record, so they never
            // dead-letter valid events or reorder them; processing errors still use the short policy above.
            handler.setBackOffFunction((record, exception) ->
                    TransientFailures.isTransient(exception) ? retry.outageBackOff() : null);
        }
        return handler;
    }

    /** {@code <topic>.DLT}, partition chosen by the producer (key hash), logged with the failure's cause chain. */
    static TopicPartition deadLetterTopic(ConsumerRecord<?, ?> record, Exception exception) {
        log.error("Sending {}-{}@{} to {}.DLT after failure: {}", record.topic(), record.partition(),
                record.offset(), record.topic(), causes(exception));
        return new TopicPartition(record.topic() + ".DLT", -1);
    }

    /** "Listener failed &lt;- InvalidEventException: Corrupt Avro value on ..." (the wrapper alone says little). */
    private static String causes(Throwable failure) {
        StringBuilder text = new StringBuilder(String.valueOf(failure.getMessage()));
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && cause != failure && depth < 4; depth++, cause = cause.getCause()) {
            text.append(" <- ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
        }
        return text.toString();
    }

    private static BackOff noRetries() {
        return new FixedBackOff(0, 0);
    }

    private static boolean isDeserializationFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DeserializationException) {
                return true;
            }
        }
        return false;
    }
}
