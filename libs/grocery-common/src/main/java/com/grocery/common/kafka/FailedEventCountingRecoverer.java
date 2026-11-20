package com.grocery.common.kafka;

import com.grocery.common.event.EventHeaders;
import com.grocery.common.observability.GroceryEventMetrics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

/**
 * The error handler's recoverer (grocery-common 0.9.0): delegates to the dead-letter recoverer and, once the record is
 * on {@code <topic>.DLT}, counts it as {@code FAILED} in {@code grocery_events_consumed_total} — once per record,
 * not per retry. A recoverer that throws (the DLT could not be written) counts nothing; the error handler retries it.
 */
public class FailedEventCountingRecoverer implements ConsumerAwareRecordRecoverer {

    private final ConsumerAwareRecordRecoverer delegate;
    private final GroceryEventMetrics metrics;

    public FailedEventCountingRecoverer(ConsumerAwareRecordRecoverer delegate, GroceryEventMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics == null ? GroceryEventMetrics.noop() : metrics;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
        delegate.accept(record, consumer, exception);
        metrics.consumed(CorrelationRecordInterceptor.consumerGroup(consumer),
                EventEnvelope.header(record, EventHeaders.EVENT_TYPE), GroceryEventMetrics.Outcome.FAILED);
    }

    /** The recoverer that writes the dead letter. */
    public ConsumerAwareRecordRecoverer delegate() {
        return delegate;
    }
}
