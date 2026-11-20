package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;

/**
 * The rules for the events of one or more topics: update the projections, then — only when the plan is
 * {@link DispatchPlan#fresh() fresh} — add the notifications, signals and session closes of contract §4.
 * Runs inside the event's transaction; throwing rolls everything back (including the processed_event mark).
 */
public interface EventHandler {

    /** Whether this handler processes the event; others are ignored without touching the database. */
    boolean supports(String topic, String eventType);

    void handle(EventEnvelope event, DispatchPlan plan);
}
