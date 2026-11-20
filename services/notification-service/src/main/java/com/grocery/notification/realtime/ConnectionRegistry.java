package com.grocery.notification.realtime;

import com.grocery.notification.config.NotificationProperties;
import com.grocery.notification.dispatch.Audiences;
import com.grocery.notification.dispatch.SessionReason;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The SSE connections open on THIS replica, indexed by audience key. There is no shared connection registry: every
 * replica receives every Redis message and delivers it to its own matching connections ({@link #deliver}).
 * <ul>
 *   <li>at most {@code max-streams-per-user} per user on this replica: registering one more closes the oldest with
 *       {@code session REPLACED};</li>
 *   <li>a {@code : hb} heartbeat on every connection each {@code heartbeat-interval} — it also detects dead clients
 *       (the write fails and the connection is removed);</li>
 *   <li>{@code session TOKEN_EXPIRED} at the token's {@code exp};</li>
 *   <li>on shutdown every stream is completed first (before the web server's graceful shutdown), and the clients
 *       reconnect to another replica.</li>
 * </ul>
 */
@Component
public class ConnectionRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Clock clock;
    private final int maxStreamsPerUser;
    private final Duration heartbeatInterval;
    private final String instanceId;
    private final Map<String, Set<StreamConnection>> byAudience = new ConcurrentHashMap<>();
    private final Set<StreamConnection> all = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("sse-scheduler").daemon().factory());
    private final ExecutorService sender = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("sse-send-", 0).factory());
    private volatile boolean running;
    private ScheduledFuture<?> heartbeat;

    public ConnectionRegistry(Clock clock, NotificationProperties properties) {
        this.clock = clock;
        this.maxStreamsPerUser = Math.max(1, properties.getMaxStreamsPerUser());
        this.heartbeatInterval = properties.getHeartbeatInterval();
        this.instanceId = properties.resolvedInstanceId();
    }

    /** A connection for this emitter, not registered yet (it already accepts frames: the retry line goes first). */
    public StreamConnection newConnection(UUID userId, Set<String> audiences, Instant expiresAt, SseEmitter emitter) {
        return new StreamConnection(sequence.incrementAndGet(), userId, audiences, clock.instant(), expiresAt, emitter,
                sender, this::unregister);
    }

    /**
     * Starts live delivery to the connection, schedules its TOKEN_EXPIRED close and enforces the per-user limit
     * (the oldest connections beyond it get {@code session REPLACED}).
     */
    public void register(StreamConnection connection) {
        List<StreamConnection> replaced = new ArrayList<>();
        synchronized (this) {
            all.add(connection);
            for (String audience : connection.audiences()) {
                byAudience.computeIfAbsent(audience, key -> ConcurrentHashMap.newKeySet()).add(connection);
            }
            Set<StreamConnection> mine = byAudience.get(Audiences.user(connection.userId()));
            int excess = mine.size() - maxStreamsPerUser;
            if (excess > 0) {
                mine.stream().sorted(Comparator.comparingLong(StreamConnection::sequence)).limit(excess)
                        .forEach(replaced::add);
                replaced.forEach(this::unregisterLocked);
            }
        }
        if (connection.expiresAt() != null) {
            long delay = Math.max(0, Duration.between(clock.instant(), connection.expiresAt()).toMillis());
            connection.expireWith(scheduler.schedule(() -> connection.close(SessionReason.TOKEN_EXPIRED), delay,
                    TimeUnit.MILLISECONDS));
        }
        for (StreamConnection old : replaced) {
            log.info("Stream {} of user {} replaced by stream {} on {} (more than {} streams)", old.sequence(),
                    old.userId(), connection.sequence(), instanceId, maxStreamsPerUser);
            old.close(SessionReason.REPLACED);
        }
        log.debug("Stream {} of user {} opened on {} ({} open)", connection.sequence(), connection.userId(),
                instanceId, all.size());
    }

    /** Called by {@link StreamConnection#terminate()}; idempotent. */
    void unregister(StreamConnection connection) {
        synchronized (this) {
            unregisterLocked(connection);
        }
        log.debug("Stream {} of user {} closed on {} ({} open)", connection.sequence(), connection.userId(),
                instanceId, all.size());
    }

    private void unregisterLocked(StreamConnection connection) {
        all.remove(connection);
        for (String audience : connection.audiences()) {
            Set<StreamConnection> connections = byAudience.get(audience);
            if (connections != null) {
                connections.remove(connection);
                if (connections.isEmpty()) {
                    byAudience.remove(audience);
                }
            }
        }
    }

    /**
     * Writes the frame to every local connection with at least one of these audiences — once per connection, even
     * when several audiences match. A last frame ({@code session}) closes those connections after it.
     *
     * @return the number of connections it went to
     */
    public int deliver(Collection<String> audiences, SseFrame frame) {
        Map<StreamConnection, Boolean> targets = new IdentityHashMap<>();
        for (String audience : audiences) {
            Set<StreamConnection> connections = byAudience.get(audience);
            if (connections != null) {
                connections.forEach(connection -> targets.put(connection, Boolean.TRUE));
            }
        }
        targets.keySet().forEach(connection -> connection.deliver(frame));
        return targets.size();
    }

    /** Open connections on this replica. */
    public int size() {
        return all.size();
    }

    /** Open connections of one user on this replica. */
    public int size(UUID userId) {
        Set<StreamConnection> connections = byAudience.get(Audiences.user(userId));
        return connections == null ? 0 : connections.size();
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        long interval = Math.max(100, heartbeatInterval.toMillis());
        heartbeat = scheduler.scheduleAtFixedRate(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
        running = true;
        log.info("SSE streams on {}: heartbeat every {}, at most {} streams per user", instanceId, heartbeatInterval,
                maxStreamsPerUser);
    }

    private void heartbeat() {
        for (StreamConnection connection : all) {
            connection.send(SseFrame.HEARTBEAT);
        }
    }

    /** Completes every stream; clients reconnect (to another replica) with their Last-Event-ID. */
    @Override
    public void stop() {
        running = false;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        List<StreamConnection> open = new ArrayList<>(all);
        if (!open.isEmpty()) {
            log.info("Closing {} SSE streams on {}", open.size(), instanceId);
        }
        open.forEach(StreamConnection::complete);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops first, before the web server's graceful shutdown, which would otherwise wait for the open streams. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @PreDestroy
    void shutdownExecutors() {
        scheduler.shutdownNow();
        sender.shutdown();
    }
}
