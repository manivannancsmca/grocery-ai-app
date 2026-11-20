package com.grocery.common.observability;

import com.grocery.common.kafka.CorrelationRecordInterceptor;
import com.grocery.common.kafka.FailedEventCountingRecoverer;
import com.grocery.common.observability.GroceryEventMetrics.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The counters, the record interceptor's APPLIED / DUPLICATE and the recoverer's FAILED, without Kafka. */
class GroceryEventMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GroceryEventMetrics metrics = new GroceryEventMetrics(registry);

    @AfterEach
    void cleanThread() {
        KafkaUtils.clearConsumerGroupId();
        ConsumedEvents.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double consumed(String group, String eventType, Outcome outcome) {
        Counter counter = registry.find(GroceryEventMetrics.EVENTS_CONSUMED).tags("consumer_group", group,
                "event_type", eventType, "outcome", outcome.name()).counter();
        return counter == null ? 0 : counter.count();
    }

    private double published(String topic, String eventType) {
        Counter counter = registry.find(GroceryEventMetrics.EVENTS_PUBLISHED).tags("topic", topic, "event_type", eventType)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static ConsumerRecord<Object, Object> record(String eventType) {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("order.events", 0, 7L, "order-1", "{}");
        record.headers().add("eventId", "0190f3a2-0000-7000-8000-000000000001".getBytes(StandardCharsets.UTF_8));
        if (eventType != null) {
            record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void publishedCountsOnlyCommittedTransactions() {
        TransactionSynchronizationManager.initSynchronization();
        metrics.publishedOnCommit("order.events", "OrderPlaced");
        metrics.publishedOnCommit("order.events", "OrderPlaced");
        assertThat(published("order.events", "OrderPlaced")).isZero();           // not before the commit
        List<TransactionSynchronization> synchronizations = new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(published("order.events", "OrderPlaced")).isEqualTo(2);

        TransactionSynchronizationManager.initSynchronization();
        metrics.publishedOnCommit("order.events", "OrderCancelled");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(published("order.events", "OrderCancelled")).isZero();

        metrics.publishedOnCommit("order.events", "OrderConfirmed");             // no synchronization: at once
        assertThat(published("order.events", "OrderConfirmed")).isOne();
    }

    @Test
    void theInterceptorCountsAppliedAndDuplicateRecordsOnce() {
        KafkaUtils.setConsumerGroupId("inventory-service.order-events");
        CorrelationRecordInterceptor interceptor = new CorrelationRecordInterceptor(metrics);

        // 1. the guard said "first": APPLIED
        ConsumerRecord<Object, Object> first = record("OrderPlaced");
        interceptor.intercept(first, null);
        ConsumedEvents.report(true);
        interceptor.success(first, null);
        interceptor.afterRecord(first, null);
        // 2. the guard said "duplicate": DUPLICATE
        interceptor.intercept(first, null);
        ConsumedEvents.report(false);
        interceptor.success(first, null);
        interceptor.afterRecord(first, null);
        // 3. no guard at all (e.g. an ignored event type): APPLIED
        interceptor.intercept(first, null);
        interceptor.success(first, null);
        // 4. the guard twice, once first (one record touching two aggregates): APPLIED
        interceptor.intercept(first, null);
        ConsumedEvents.report(false);
        ConsumedEvents.report(true);
        interceptor.success(first, null);
        // 5. a listener's own duplicate detection
        interceptor.intercept(first, null);
        ConsumedEvents.markDuplicate();
        interceptor.success(first, null);
        // 6. a failed attempt is not counted (the error handler counts FAILED once, when it dead-letters it)
        interceptor.intercept(first, null);
        ConsumedEvents.report(true);
        interceptor.failure(first, new IllegalStateException("boom"), null);
        interceptor.afterRecord(first, null);
        // 7. a record without eventType header
        ConsumerRecord<Object, Object> anonymous = record(null);
        interceptor.intercept(anonymous, null);
        interceptor.success(anonymous, null);

        String group = "inventory-service.order-events";
        assertThat(consumed(group, "OrderPlaced", Outcome.APPLIED)).isEqualTo(3);
        assertThat(consumed(group, "OrderPlaced", Outcome.DUPLICATE)).isEqualTo(2);
        assertThat(consumed(group, "OrderPlaced", Outcome.FAILED)).isZero();
        assertThat(consumed(group, "unknown", Outcome.APPLIED)).isOne();
        assertThat(ConsumedEvents.finish()).isNull();                           // nothing left on the thread
    }

    @Test
    void outsideARecordReportsAreIgnored() {
        ConsumedEvents.report(true);
        ConsumedEvents.markDuplicate();
        assertThat(ConsumedEvents.finish()).isNull();

        ConsumedEvents.start("g", "T");
        ConsumedEvents.markDuplicate();
        assertThat(ConsumedEvents.finish()).isEqualTo(new ConsumedEvents.Finished("g", "T", Outcome.DUPLICATE));
    }

    @Test
    void theRecovererCountsFailedOnceTheDeadLetterIsWritten() {
        KafkaUtils.setConsumerGroupId("payment-service.order-events");
        List<ConsumerRecord<?, ?>> deadLettered = new ArrayList<>();
        FailedEventCountingRecoverer recoverer = new FailedEventCountingRecoverer(
                (record, consumer, exception) -> deadLettered.add(record), metrics);

        recoverer.accept(record("OrderPlaced"), null, new IllegalStateException("poison"));

        assertThat(deadLettered).hasSize(1);
        assertThat(consumed("payment-service.order-events", "OrderPlaced", Outcome.FAILED)).isOne();

        FailedEventCountingRecoverer failing = new FailedEventCountingRecoverer((record, consumer, exception) -> {
            throw new IllegalStateException("DLT unavailable");
        }, metrics);
        assertThatThrownBy(() -> failing.accept(record("OrderPlaced"), null, new IllegalStateException("poison")))
                .hasMessage("DLT unavailable");
        assertThat(consumed("payment-service.order-events", "OrderPlaced", Outcome.FAILED)).isOne();   // not counted
    }

    @Test
    void withoutARegistryEverythingIsANoOp() {
        GroceryEventMetrics noop = GroceryEventMetrics.noop();
        noop.published("t", "E");
        noop.publishedOnCommit("t", "E");
        noop.consumed("g", "E", Outcome.APPLIED);
        assertThat(noop.registry()).isNull();
    }
}
