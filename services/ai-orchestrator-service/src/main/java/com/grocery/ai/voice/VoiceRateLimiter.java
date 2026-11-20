package com.grocery.ai.voice;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A cost guard for the paid or CPU-heavy speech calls: at most N voice calls per user per clock hour, counted in this
 * replica's memory (with several replicas a user gets up to N per replica, which is fine for a cost guard; the
 * gateway's request rate limit still applies to every call). Chat messages have their own, database-backed limit.
 */
@Component
class VoiceRateLimiter {

    /** Above this many tracked users, windows of past hours are dropped. */
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(long hour, int count) {
    }

    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    VoiceRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts one call of {@code userId}.
     *
     * @return 0 when it is allowed, else the seconds until the next hour starts (the call is not counted)
     */
    long acquire(UUID userId, int perHour) {
        Instant now = clock.instant();
        long hour = now.getEpochSecond() / 3600;
        boolean[] allowed = {false};
        windows.compute(userId, (id, window) -> {
            Window current = window == null || window.hour() != hour ? new Window(hour, 0) : window;
            if (current.count() >= perHour) {
                return current;
            }
            allowed[0] = true;
            return new Window(hour, current.count() + 1);
        });
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(window -> window.hour() != hour);
        }
        if (allowed[0]) {
            return 0;
        }
        Instant nextHour = Instant.ofEpochSecond((hour + 1) * 3600);
        return Math.max(1, Duration.between(now, nextHour).toSeconds());
    }
}
