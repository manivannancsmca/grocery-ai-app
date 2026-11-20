package com.grocery.common.deadletter;

import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.event.EventHeaders;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.observability.ConsumedEvents;
import com.grocery.common.observability.GroceryEventMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;
import org.springframework.kafka.support.micrometer.KafkaListenerObservation;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.kafka.listener.BatchMessageListener;
import org.springframework.kafka.listener.GenericMessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.Optional;

/**
 * Replays a dead letter <b>in process</b>: its original bytes are decoded with the service's own value deserializer and
 * handed, as a {@code ConsumerRecord} with the original topic, partition, offset, timestamp, key and headers (minus
 * {@code kafka_dlt-*}), to the message listener of the container of its consumer group for its topic, synchronously,
 * on the calling thread. Nothing is re-published, so other consumer groups of the topic are not affected; the
 * listener's own idempotency ({@code processed_event}) makes a replay of an event that was in fact applied a no-op.
 * The correlation / causation ids of the event are put in the logging MDC for the duration of the call, like
 * {@code CorrelationRecordInterceptor} does for consumed records (and restored afterwards).
 *
 * <p>Since 0.9.0 the call runs inside a Kafka listener observation of the record, like the container's: with tracing,
 * a child span of the record's {@code traceparent} header (so a replayed order event joins the order's trace), with
 * {@code traceId} / {@code spanId} in the MDC. A successful replay counts APPLIED or DUPLICATE in
 * {@code grocery_events_consumed_total}; a failed one counts nothing (the record already counted FAILED when it was
 * dead-lettered).
 */
public class DeadLetterReplayer {

    /** The outcome: {@code error} is null on success. */
    public record Outcome(String error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    static final int ERROR_LIMIT = 1000;
    private static final Acknowledgment NO_ACKNOWLEDGMENT = () -> {
    };

    private final DeadLetterListeners listeners;
    private final DeadLetterPayloadDecoder decoder;
    private final ObservationRegistry observationRegistry;
    private final GroceryEventMetrics metrics;

    public DeadLetterReplayer(DeadLetterListeners listeners, DeadLetterPayloadDecoder decoder) {
        this(listeners, decoder, ObservationRegistry.NOOP, GroceryEventMetrics.noop());
    }

    /**
     * @param observationRegistry the replay runs in a Kafka listener observation of the record (a child span of its
     *                            {@code traceparent}) when the container has observation enabled
     * @param metrics             a successful replay counts as APPLIED / DUPLICATE in {@code grocery_events_consumed_total}
     */
    public DeadLetterReplayer(DeadLetterListeners listeners, DeadLetterPayloadDecoder decoder,
                              ObservationRegistry observationRegistry, GroceryEventMetrics metrics) {
        this.listeners = listeners;
        this.decoder = decoder;
        this.observationRegistry = observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
        this.metrics = metrics == null ? GroceryEventMetrics.noop() : metrics;
    }

    Outcome replay(DeadLetter deadLetter) {
        MessageListenerContainer container = listeners.container(deadLetter.consumerGroup(), deadLetter.originalTopic());
        if (container == null) {
            return new Outcome("No listener of consumer group " + deadLetter.consumerGroup() + " consumes "
                    + deadLetter.originalTopic() + " in this service");
        }
        Object listener = container.getContainerProperties().getMessageListener();
        if (!(listener instanceof GenericMessageListener<?>)) {
            return new Outcome("The listener container " + container.getListenerId() + " has no message listener");
        }
        Headers headers = deadLetter.headersCopy();
        Object value;
        try {
            value = decoder.decode(deadLetter.originalTopic(), headers, deadLetter.value());
        } catch (RuntimeException e) {
            return new Outcome(describe(e instanceof DeadLetterPayloadDecoder.DecodingException && e.getCause() != null
                    ? e.getCause() : e));
        }
        byte[] key = deadLetter.key();
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(deadLetter.originalTopic(),
                deadLetter.originalPartition() == null ? 0 : deadLetter.originalPartition(),
                deadLetter.originalOffset() == null ? -1 : deadLetter.originalOffset(),
                deadLetter.originalTimestamp(), deadLetter.originalTimestampType(),
                key == null ? -1 : key.length, deadLetter.value() == null ? -1 : deadLetter.value().length,
                deadLetter.keyText(), value, headers, Optional.empty());

        String previousCorrelation = MDC.get(CorrelationId.MDC_KEY);
        String previousCausation = MDC.get(CorrelationId.CAUSATION_MDC_KEY);
        String eventType = EventEnvelope.header(record, EventHeaders.EVENT_TYPE);
        // the same observation as the listener container's: a child span of the record's traceparent (kept in the
        // dead letter's headers), so the replay shows in the original trace
        Observation observation = KafkaListenerObservation.LISTENER_OBSERVATION.observation(
                container.getContainerProperties().getObservationConvention(),
                KafkaListenerObservation.DefaultKafkaListenerObservationConvention.INSTANCE,
                () -> new KafkaRecordReceiverContext(record, String.valueOf(container.getListenerId()), null,
                        deadLetter.consumerGroup(), () -> null),
                container.getContainerProperties().isObservationEnabled() ? observationRegistry : ObservationRegistry.NOOP);
        observation.start();
        Observation.Scope scope = observation.openScope();
        try {
            putCorrelation(record);
            ConsumedEvents.start(deadLetter.consumerGroup(), eventType);
            invoke(listener, record);
            ConsumedEvents.Finished finished = ConsumedEvents.finish();
            if (finished != null) {
                metrics.consumed(finished.consumerGroup(), finished.eventType(), finished.outcome());
            }
            return new Outcome(null);
        } catch (Exception e) {
            observation.error(unwrap(e));
            return new Outcome(describe(unwrap(e)));
        } finally {
            ConsumedEvents.clear();
            observation.stop();
            scope.close();
            restore(CorrelationId.MDC_KEY, previousCorrelation);
            restore(CorrelationId.CAUSATION_MDC_KEY, previousCausation);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void invoke(Object listener, ConsumerRecord<Object, Object> record) {
        GenericMessageListener generic = (GenericMessageListener) listener;
        if (listener instanceof BatchMessageListener<?, ?>) {
            generic.onMessage(List.of(record), NO_ACKNOWLEDGMENT, null);
        } else {
            generic.onMessage(record, NO_ACKNOWLEDGMENT, null);
        }
    }

    /** Same rule as CorrelationRecordInterceptor: the event's correlation id (or its id), the event id as causation. */
    private static void putCorrelation(ConsumerRecord<?, ?> record) {
        String eventId = EventEnvelope.header(record, EventHeaders.EVENT_ID);
        String correlationId = CorrelationId.sanitize(EventEnvelope.header(record, EventHeaders.CORRELATION_ID));
        if (correlationId == null) {
            correlationId = eventId != null ? eventId : CorrelationId.newValue();
        }
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        if (eventId != null) {
            MDC.put(CorrelationId.CAUSATION_MDC_KEY, eventId);
        } else {
            MDC.remove(CorrelationId.CAUSATION_MDC_KEY);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    /** Skips the listener adapter's wrapper (ListenerExecutionFailedException) and reflection wrappers. */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 10 && current.getCause() != null && current.getCause() != current; depth++) {
            String name = current.getClass().getName();
            if (name.equals("org.springframework.kafka.listener.ListenerExecutionFailedException")
                    || current instanceof InvocationTargetException || current instanceof UndeclaredThrowableException) {
                current = current.getCause();
            } else {
                break;
            }
        }
        return current;
    }

    static String describe(Throwable failure) {
        String text = failure.getClass().getName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return DeadLetter.truncate(text, ERROR_LIMIT);
    }
}
