package com.grocery.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The generic business metrics of grocery-common 0.9.0 (contract Phase 13 §1):
 * <table>
 *   <tr><th>Meter (Prometheus name)</th><th>Type</th><th>Tags</th><th>Recorded by</th></tr>
 *   <tr><td>{@value #EVENTS_PUBLISHED} ({@code grocery_events_published_total})</td><td>counter</td>
 *       <td>{@code topic}, {@code event_type}</td><td>the outbox publisher, once the business transaction commits</td></tr>
 *   <tr><td>{@value #EVENTS_CONSUMED} ({@code grocery_events_consumed_total})</td><td>counter</td>
 *       <td>{@code consumer_group}, {@code event_type}, {@code outcome} ({@link Outcome})</td>
 *       <td>the record interceptor + {@code ProcessedEventGuard} ({@code APPLIED} / {@code DUPLICATE}), the error
 *       handler ({@code FAILED}: the record went to {@code <topic>.DLT}), the dead-letter replay</td></tr>
 *   <tr><td>{@value #DEAD_LETTERS_PENDING} ({@code grocery_dead_letters_pending})</td><td>gauge</td>
 *       <td>{@code consumer_group}</td><td>the dead-letter index (PENDING + REPLAY_FAILED)</td></tr>
 * </table>
 * Without a {@link MeterRegistry} every method is a no-op ({@link #noop()}).
 */
public class GroceryEventMetrics {

    public static final String EVENTS_PUBLISHED = "grocery.events.published";
    public static final String EVENTS_CONSUMED = "grocery.events.consumed";
    public static final String DEAD_LETTERS_PENDING = "grocery.dead.letters.pending";

    public static final String TOPIC_TAG = "topic";
    public static final String EVENT_TYPE_TAG = "event_type";
    public static final String CONSUMER_GROUP_TAG = "consumer_group";
    public static final String OUTCOME_TAG = "outcome";
    /** Tag value when a record carries no {@code eventType} header or has no consumer group. */
    public static final String UNKNOWN = "unknown";

    /** How one consumer group's handling of one event ended. */
    public enum Outcome {
        /** The listener handled the event (and, with the guard, it was the first time). */
        APPLIED,
        /** The listener recognised an event it had already handled ({@code ProcessedEventGuard} returned false). */
        DUPLICATE,
        /** The error handler gave up: the record was published to {@code <topic>.DLT} (once per record, not per retry). */
        FAILED
    }

    private static final GroceryEventMetrics NOOP = new GroceryEventMetrics(null);

    private final MeterRegistry registry;

    /** @param registry {@code null} = no-op */
    public GroceryEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static GroceryEventMetrics noop() {
        return NOOP;
    }

    /** {@code null} for the no-op instance. */
    public MeterRegistry registry() {
        return registry;
    }

    /** Counts one published event now. */
    public void published(String topic, String eventType) {
        if (registry == null) {
            return;
        }
        Counter.builder(EVENTS_PUBLISHED)
                .description("Domain events written to the outbox in committed transactions")
                .tag(TOPIC_TAG, value(topic))
                .tag(EVENT_TYPE_TAG, value(eventType))
                .register(registry)
                .increment();
    }

    /**
     * Counts one published event when the current transaction commits (nothing on rollback); without transaction
     * synchronization, now.
     */
    public void publishedOnCommit(String topic, String eventType) {
        if (registry == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            published(topic, eventType);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                published(topic, eventType);
            }
        });
    }

    /** Counts one consumed event of {@code consumerGroup} with its outcome. */
    public void consumed(String consumerGroup, String eventType, Outcome outcome) {
        if (registry == null || outcome == null) {
            return;
        }
        Counter.builder(EVENTS_CONSUMED)
                .description("Domain events handled by this service's consumer groups, by outcome")
                .tag(CONSUMER_GROUP_TAG, value(consumerGroup))
                .tag(EVENT_TYPE_TAG, value(eventType))
                .tag(OUTCOME_TAG, outcome.name())
                .register(registry)
                .increment();
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
