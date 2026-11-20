package com.grocery.notification.realtime;

import com.grocery.notification.config.NotificationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Phase 9: at most one {@code DELIVERY_LOCATION} per order per {@code grocery.notification.location-throttle} (2 s).
 * The first position of a window is sent, the others inside it are dropped (positions are best effort; the next one
 * follows within seconds, so there is nothing to catch up on — unlike {@link PublicSignalThrottle}, no trailing send).
 *
 * <p>In memory, per replica: delivery.location is keyed by orderId, so all positions of one order are consumed by one
 * replica. Idle orders are forgotten every minute.
 */
@Component
public class LocationThrottle {

    private final long windowNanos;
    private final LongSupplier nanoTime;
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    @Autowired
    public LocationThrottle(NotificationProperties properties) {
        this(properties.getLocationThrottle().toNanos(), System::nanoTime);
    }

    /** With an own time source (tests). */
    LocationThrottle(long windowNanos, LongSupplier nanoTime) {
        this.windowNanos = Math.max(0, windowNanos);
        this.nanoTime = nanoTime;
    }

    /** True (and the window starts) when no position of this order was sent in the current window. */
    public boolean tryAcquire(UUID orderId) {
        long now = nanoTime.getAsLong();
        boolean[] allowed = {false};
        lastSent.compute(orderId, (id, previous) -> {
            if (previous == null || now - previous >= windowNanos) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        return allowed[0];
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void forgetIdle() {
        long now = nanoTime.getAsLong();
        lastSent.entrySet().removeIf(entry -> now - entry.getValue() >= windowNanos);
    }

    /** Orders currently remembered (tests). */
    int size() {
        return lastSent.size();
    }
}
