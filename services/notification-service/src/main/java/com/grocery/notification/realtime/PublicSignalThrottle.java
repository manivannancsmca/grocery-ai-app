package com.grocery.notification.realtime;

import com.grocery.notification.config.NotificationProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * At most one public signal per key ({@code STOCK_CHANGED:<productId>}, {@code PRICE_CHANGED:<productId>},
 * {@code RESTAURANT_UPDATED:<restaurantId>}) per {@code grocery.notification.public-signal-throttle} (2 s).
 * The first one is published at once; later ones inside the window are collapsed into ONE trailing signal (the
 * latest) published when the window ends, so the last in-stock / price state is never lost.
 *
 * <p>In memory, per replica: all events of one product / restaurant are keyed to one partition, so they are consumed
 * by one replica. Idle keys are forgotten every minute.
 */
@Component
public class PublicSignalThrottle {

    private static final class State {
        long lastSentNanos;
        RealtimeMessage pending;
        boolean scheduled;
    }

    private final RealtimePublisher publisher;
    private final long windowNanos;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("public-signal-throttle").daemon().factory());

    public PublicSignalThrottle(RealtimePublisher publisher, NotificationProperties properties) {
        this.publisher = publisher;
        this.windowNanos = Math.max(0, properties.getPublicSignalThrottle().toNanos());
        scheduler.scheduleWithFixedDelay(this::forgetIdle, 1, 1, TimeUnit.MINUTES);
    }

    public void submit(String key, RealtimeMessage message) {
        long now = System.nanoTime();
        boolean[] sendNow = {false};
        states.compute(key, (k, state) -> {
            State current = state;
            if (current == null) {
                current = new State();
                current.lastSentNanos = now - windowNanos;
            }
            if (!current.scheduled && now - current.lastSentNanos >= windowNanos) {
                current.lastSentNanos = now;
                sendNow[0] = true;
            } else {
                current.pending = message;
                if (!current.scheduled) {
                    current.scheduled = true;
                    long delay = Math.max(0, current.lastSentNanos + windowNanos - now);
                    scheduler.schedule(() -> flush(k), delay, TimeUnit.NANOSECONDS);
                }
            }
            return current;
        });
        if (sendNow[0]) {
            publisher.publish(message);
        }
    }

    private void flush(String key) {
        RealtimeMessage[] latest = {null};
        states.computeIfPresent(key, (k, state) -> {
            latest[0] = state.pending;
            state.pending = null;
            state.scheduled = false;
            state.lastSentNanos = System.nanoTime();
            return state;
        });
        if (latest[0] != null) {
            publisher.publish(latest[0]);
        }
    }

    private void forgetIdle() {
        long now = System.nanoTime();
        for (String key : states.keySet()) {
            states.computeIfPresent(key, (k, state) ->
                    !state.scheduled && now - state.lastSentNanos >= windowNanos ? null : state);
        }
    }

    /** Number of keys currently remembered (tests). */
    int size() {
        return states.size();
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
