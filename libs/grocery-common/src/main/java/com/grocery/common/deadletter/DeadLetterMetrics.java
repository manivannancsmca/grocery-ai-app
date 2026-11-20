package com.grocery.common.deadletter;

import com.grocery.common.observability.GroceryEventMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code grocery_dead_letters_pending{consumer_group}} (grocery-common 0.9.0, contract Phase 13 §1): per consumer group
 * of this service, the dead letters this instance's {@link DeadLetterIndex} holds that are still open — PENDING or
 * REPLAY_FAILED (an operator still has to replay or discard them). One gauge per group, registered once the listener
 * containers exist (on the context refresh), so a group without dead letters reports 0 and the
 * {@code DeadLettersPending} alert can resolve. The index fills while the background reader reads the DLTs, so right
 * after a start the value grows from 0 to the real count; every replica reports the same numbers.
 */
public class DeadLetterMetrics implements ApplicationListener<ContextRefreshedEvent> {

    private final DeadLetterIndex index;
    private final DeadLetterListeners listeners;
    private final MeterRegistry registry;
    private final AtomicBoolean registered = new AtomicBoolean();

    public DeadLetterMetrics(DeadLetterIndex index, DeadLetterListeners listeners, MeterRegistry registry) {
        this.index = index;
        this.listeners = listeners;
        this.registry = registry;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (registered.compareAndSet(false, true)) {
            for (String group : listeners.consumerGroups()) {
                Gauge.builder(GroceryEventMetrics.DEAD_LETTERS_PENDING, index, i -> i.openCount(group))
                        .description("Dead letters of this consumer group still to be replayed or discarded")
                        .tag(GroceryEventMetrics.CONSUMER_GROUP_TAG, group)
                        .register(registry);
            }
        }
    }
}
