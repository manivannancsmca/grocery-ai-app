package com.grocery.common.kafka;

import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.event.EventHeaders;
import com.grocery.common.observability.ConsumedEvents;
import com.grocery.common.observability.GroceryEventMetrics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.KafkaUtils;

/**
 * Before each record is handled: puts the event's correlation ID and its event ID (as the
 * causation ID) into the MDC. Logs then carry them, and any event published while handling this
 * one (through the outbox) inherits the correlation ID and points back to its cause.
 *
 * <p>Since 0.9.0 it also counts {@code grocery_events_consumed_total}: a record whose listener returns normally is
 * {@code APPLIED}, or {@code DUPLICATE} when the {@code ProcessedEventGuard} (or {@link ConsumedEvents#markDuplicate()})
 * said so. Failed attempts are not counted here; the error handler counts {@code FAILED} once, when it dead-letters
 * the record. With listener observation on (grocery-common's default), the interceptor runs inside the record's
 * span, so the MDC also carries {@code traceId} / {@code spanId}.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    private final GroceryEventMetrics metrics;

    public CorrelationRecordInterceptor() {
        this(GroceryEventMetrics.noop());
    }

    public CorrelationRecordInterceptor(GroceryEventMetrics metrics) {
        this.metrics = metrics == null ? GroceryEventMetrics.noop() : metrics;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        String eventId = EventEnvelope.header(record, EventHeaders.EVENT_ID);
        String correlationId = CorrelationId.sanitize(EventEnvelope.header(record, EventHeaders.CORRELATION_ID));
        if (correlationId == null) {
            correlationId = eventId != null ? eventId : CorrelationId.newValue();
        }
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        if (eventId != null) {
            MDC.put(CorrelationId.CAUSATION_MDC_KEY, eventId);
        }
        ConsumedEvents.start(consumerGroup(consumer), EventEnvelope.header(record, EventHeaders.EVENT_TYPE));
        return record;
    }

    @Override
    public void success(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        ConsumedEvents.Finished finished = ConsumedEvents.finish();
        if (finished != null) {
            metrics.consumed(finished.consumerGroup(), finished.eventType(), finished.outcome());
        }
    }

    @Override
    public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
        ConsumedEvents.clear();
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        clear();
    }

    @Override
    public void clearThreadState(Consumer<?, ?> consumer) {
        clear();
    }

    /** The listener container's consumer group (Spring Kafka keeps it in a thread-local on the consumer thread). */
    static String consumerGroup(Consumer<?, ?> consumer) {
        String group = KafkaUtils.getConsumerGroupId();
        if (group == null && consumer != null) {
            try {
                group = consumer.groupMetadata().groupId();
            } catch (RuntimeException e) {
                group = null;                               // assigned partitions without a group
            }
        }
        return group;
    }

    private static void clear() {
        MDC.remove(CorrelationId.MDC_KEY);
        MDC.remove(CorrelationId.CAUSATION_MDC_KEY);
        ConsumedEvents.clear();
    }
}
