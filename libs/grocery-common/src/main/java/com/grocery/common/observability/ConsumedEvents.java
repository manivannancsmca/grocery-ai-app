package com.grocery.common.observability;

import com.grocery.common.observability.GroceryEventMetrics.Outcome;

/**
 * The outcome of the record the current thread is handling, for {@code grocery_events_consumed_total}
 * (grocery-common 0.9.0).
 *
 * <p>grocery-common's record interceptor opens a record on the listener thread before the listener runs and, when
 * the listener returns normally, counts it: {@code DUPLICATE} if the {@code ProcessedEventGuard} said so for every
 * call, otherwise {@code APPLIED} (a listener that never calls the guard, or ignores an event type, counts as
 * {@code APPLIED}). A listener that throws is not counted per attempt: the error handler counts {@code FAILED} once,
 * when it dead-letters the record. The in-process dead-letter replay does the same on the request thread.
 *
 * <p><b>Services</b> only need this class when a listener detects duplicates <i>without</i> the guard (its own
 * version check, an upsert that changed nothing …): call {@link #markDuplicate()} on the listener thread. Calls
 * outside a record (another thread, a scheduled job) are ignored.
 */
public final class ConsumedEvents {

    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

    /** The record being handled on this thread. */
    private static final class Current {
        final String consumerGroup;
        final String eventType;
        boolean applied;
        boolean duplicate;

        Current(String consumerGroup, String eventType) {
            this.consumerGroup = consumerGroup;
            this.eventType = eventType;
        }
    }

    /** What {@link #finish()} returns: the record's group, event type and outcome. */
    public record Finished(String consumerGroup, String eventType, Outcome outcome) {
    }

    private ConsumedEvents() {
    }

    /** The current record was an event this consumer had already handled. */
    public static void markDuplicate() {
        report(false);
    }

    /**
     * Reported by {@code ProcessedEventGuard.markProcessed}: {@code first} = the event is applied now, otherwise it is a
     * duplicate. When a listener calls the guard several times for one record, one {@code first} makes it APPLIED.
     */
    public static void report(boolean first) {
        Current current = CURRENT.get();
        if (current == null) {
            return;
        }
        if (first) {
            current.applied = true;
        } else {
            current.duplicate = true;
        }
    }

    /** Library-internal: a record of {@code consumerGroup} starts on this thread (replaces any previous one). */
    public static void start(String consumerGroup, String eventType) {
        CURRENT.set(new Current(consumerGroup, eventType));
    }

    /** Library-internal: the record was handled; its outcome ({@code null} if none was started). Clears the thread. */
    public static Finished finish() {
        Current current = CURRENT.get();
        CURRENT.remove();
        if (current == null) {
            return null;
        }
        Outcome outcome = current.duplicate && !current.applied ? Outcome.DUPLICATE : Outcome.APPLIED;
        return new Finished(current.consumerGroup, current.eventType, outcome);
    }

    /** Library-internal: forget the current record (it failed, or the thread is cleaned up). */
    public static void clear() {
        CURRENT.remove();
    }
}
