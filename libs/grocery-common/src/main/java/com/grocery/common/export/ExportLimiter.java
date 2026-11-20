package com.grocery.common.export;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * How many exports run at once: per instance (each holds a request thread and, batch by batch, a database
 * connection) and per user. A request over a limit is refused at once (429 EXPORT_BUSY), never queued.
 */
final class ExportLimiter {

    private final Semaphore instance;
    private final int perUser;
    private final Map<UUID, Integer> running = new ConcurrentHashMap<>();

    ExportLimiter(int maxConcurrent, int maxPerUser) {
        this.instance = new Semaphore(Math.max(1, maxConcurrent));
        this.perUser = Math.max(1, maxPerUser);
    }

    /** A slot for {@code user}'s export; release it when the export ends. */
    Permit acquire(UUID user) {
        boolean[] granted = {false};
        running.compute(user, (id, count) -> {
            int current = count == null ? 0 : count;
            if (current >= perUser) {
                return count;
            }
            granted[0] = true;
            return current + 1;
        });
        if (!granted[0]) {
            throw ExportException.busy(true);
        }
        if (!instance.tryAcquire()) {
            release(user);
            throw ExportException.busy(false);
        }
        return () -> {
            instance.release();
            release(user);
        };
    }

    private void release(UUID user) {
        running.computeIfPresent(user, (id, count) -> count <= 1 ? null : count - 1);
    }

    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
