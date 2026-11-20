package com.grocery.notification.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * One open SSE stream on this replica.
 *
 * <p><b>Serialised sends.</b> Every frame goes through one FIFO queue, drained by at most one (virtual) thread at a
 * time, so writes to the emitter never overlap and keep their order; the fan-out thread and the schedulers only
 * enqueue and never block on a slow client. A client that falls more than {@value #MAX_QUEUED_FRAMES} frames behind
 * is disconnected (it reconnects with its Last-Event-ID).
 *
 * <p><b>Replay without gaps or duplicates.</b> A new connection is registered (so it starts receiving live frames)
 * <i>before</i> the replay query runs. While replaying, live frames are held; the replayed notifications are sent
 * first, then {@link #finishReplay()} sends the held frames. Live copies of replayed notifications are skipped, held
 * or arriving later.
 *
 * <p><b>No leaks.</b> The first failed write (client gone), the emitter's completion / timeout / error callbacks,
 * a session close, an overflow and the registry's shutdown all end in {@link #terminate()}: it is idempotent, drops
 * queued frames, cancels the expiry task and removes the connection from the registry.
 */
public final class StreamConnection {

    static final int MAX_QUEUED_FRAMES = 1_000;
    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final Logger log = LoggerFactory.getLogger(StreamConnection.class);

    private final long sequence;
    private final UUID userId;
    private final Set<String> audiences;
    private final Instant openedAt;
    private final Instant expiresAt;
    private final SseEmitter emitter;
    private final Executor sender;
    private final Consumer<StreamConnection> onTerminated;

    private final ReentrantLock lock = new ReentrantLock();
    // ---- guarded by lock
    private final ArrayDeque<SseFrame> queue = new ArrayDeque<>();
    private final List<SseFrame> held = new ArrayList<>();
    private final Set<UUID> replayed = new HashSet<>();
    private boolean replaying = true;
    /** A drain task is running (or about to). */
    private boolean draining;
    /** The last frame (a session) is queued: nothing more is accepted. */
    private boolean closing;
    /** Too many frames waiting: the client is disconnected. */
    private boolean overflowed;
    /** Cleanup ran: nothing is accepted or sent any more. */
    private boolean terminated;
    private ScheduledFuture<?> expiryTask;

    StreamConnection(long sequence, UUID userId, Set<String> audiences, Instant openedAt, Instant expiresAt,
                     SseEmitter emitter, Executor sender, Consumer<StreamConnection> onTerminated) {
        this.sequence = sequence;
        this.userId = userId;
        this.audiences = Set.copyOf(audiences);
        this.openedAt = openedAt;
        this.expiresAt = expiresAt;
        this.emitter = emitter;
        this.sender = sender;
        this.onTerminated = onTerminated;
    }

    public long sequence() {
        return sequence;
    }

    public UUID userId() {
        return userId;
    }

    public Set<String> audiences() {
        return audiences;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** Sends a frame in order with everything queued before it (retry line, replay, RESYNC, heartbeats, sessions). */
    public void send(SseFrame frame) {
        boolean start;
        lock.lock();
        try {
            start = enqueueLocked(frame);
        } finally {
            lock.unlock();
        }
        afterEnqueue(start);
    }

    /** A replayed notification: sent now and remembered, so its live copy (if one is held) is skipped. */
    public void sendReplayed(SseFrame frame) {
        boolean start;
        lock.lock();
        try {
            if (frame.notificationId() != null) {
                replayed.add(frame.notificationId());
            }
            start = enqueueLocked(frame);
        } finally {
            lock.unlock();
        }
        afterEnqueue(start);
    }

    /**
     * A live frame from the fan-out: held while the replay runs, sent after it. A notification the replay already
     * sent is skipped — also when its Redis copy arrives only after the replay (committed before the replay query,
     * published after it).
     */
    public void deliver(SseFrame frame) {
        boolean start = false;
        lock.lock();
        try {
            if (frame.notificationId() != null && replayed.contains(frame.notificationId())) {
                return;
            }
            if (replaying && !frame.last()) {
                if (acceptsLocked()) {
                    if (held.size() >= MAX_QUEUED_FRAMES) {
                        overflowed = true;
                    } else {
                        held.add(frame);
                    }
                }
            } else {
                start = enqueueLocked(frame);   // a session close is never held back
            }
        } finally {
            lock.unlock();
        }
        afterEnqueue(start);
    }

    /** Ends the replay: the held live frames follow, without the notifications the replay already sent. */
    public void finishReplay() {
        boolean start = false;
        lock.lock();
        try {
            if (!replaying) {
                return;
            }
            replaying = false;
            for (SseFrame frame : held) {
                if (frame.notificationId() == null || !replayed.contains(frame.notificationId())) {
                    start |= enqueueLocked(frame);
                }
            }
            held.clear();
            // replayed ids (at most replay-limit) stay: their live copies may still be on the way
        } finally {
            lock.unlock();
        }
        afterEnqueue(start);
    }

    /** Sends {@code session {reason}} after everything already queued, then closes the stream. */
    public void close(String reason) {
        send(SseFrame.sessionReason(reason));
    }

    /** The task that closes the stream when the token expires; cancelled by {@link #terminate()}. */
    void expireWith(ScheduledFuture<?> task) {
        boolean cancel;
        lock.lock();
        try {
            cancel = terminated;
            if (!cancel) {
                expiryTask = task;
            }
        } finally {
            lock.unlock();
        }
        if (cancel) {
            task.cancel(false);
        }
    }

    /** Forgets the connection (idempotent). Does not touch the emitter: for callbacks of a stream already ending. */
    public void terminate() {
        ScheduledFuture<?> task;
        lock.lock();
        try {
            if (terminated) {
                return;
            }
            terminated = true;
            queue.clear();
            held.clear();
            replayed.clear();
            task = expiryTask;
            expiryTask = null;
        } finally {
            lock.unlock();
        }
        if (task != null) {
            task.cancel(false);
        }
        onTerminated.accept(this);
    }

    /** Terminates and completes the HTTP response (shutdown, timeout, after the last frame). */
    public void complete() {
        terminate();
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("Completing stream {} of user {}: {}", sequence, userId, e.toString());
        }
    }

    public boolean isTerminated() {
        lock.lock();
        try {
            return terminated;
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------ internals

    private boolean acceptsLocked() {
        return !terminated && !closing && !overflowed;
    }

    /** @return true when the caller must start a drain task */
    private boolean enqueueLocked(SseFrame frame) {
        if (!acceptsLocked()) {
            return false;
        }
        if (queue.size() >= MAX_QUEUED_FRAMES) {
            overflowed = true;
            return false;
        }
        queue.add(frame);
        if (frame.last()) {
            closing = true;
        }
        if (!draining) {
            draining = true;
            return true;
        }
        return false;
    }

    private void afterEnqueue(boolean startDrain) {
        boolean abandon;
        lock.lock();
        try {
            abandon = overflowed && !terminated;
        } finally {
            lock.unlock();
        }
        if (abandon) {
            log.info("Stream {} of user {} fell {} frames behind: disconnected", sequence, userId, MAX_QUEUED_FRAMES);
            terminate();
            runOnSender(this::completeEmitter);
        } else if (startDrain) {
            runOnSender(this::drain);
        }
    }

    private void drain() {
        while (true) {
            SseFrame frame;
            lock.lock();
            try {
                frame = terminated ? null : queue.poll();
                if (frame == null) {
                    draining = false;
                    return;
                }
            } finally {
                lock.unlock();
            }
            try {
                emitter.send(Set.of(new DataWithMediaType(frame.text(), UTF8_TEXT)));
            } catch (Exception e) {
                // the client went away (or the response already ended): forget the connection
                log.debug("Stream {} of user {}: write failed ({})", sequence, userId, e.toString());
                stopDraining();
                terminate();
                return;
            }
            if (frame.last()) {
                stopDraining();
                complete();
                return;
            }
        }
    }

    private void stopDraining() {
        lock.lock();
        try {
            draining = false;
        } finally {
            lock.unlock();
        }
    }

    private void completeEmitter() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("Completing stream {}: {}", sequence, e.toString());
        }
    }

    private void runOnSender(Runnable task) {
        try {
            sender.execute(task);
        } catch (RejectedExecutionException e) {
            stopDraining();   // shutting down: nothing more is sent
        }
    }

    @Override
    public String toString() {
        return "StreamConnection#" + sequence + "(user " + userId + ")";
    }
}
