package com.grocery.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The work someone is waiting for that uses the model right now (chat turns, insight reports), so that the warm-up
 * ({@link ModelWarmUp}) gives way to it. Ollama answers one request at a time: a warm-up read in front of a customer's
 * model call makes the customer wait for it, about a minute on CPU, close to the model timeout. So the warm-up stops its
 * read as soon as such work begins ({@link #onBegin}) and starts no read while any runs or ran just before.
 */
@Component
public class ModelActivity {

    private static final Logger log = LoggerFactory.getLogger(ModelActivity.class);

    private final Clock clock;
    private final AtomicInteger running = new AtomicInteger();
    private final List<Runnable> beginListeners = new CopyOnWriteArrayList<>();
    private volatile Instant lastEnded = Instant.EPOCH;

    public ModelActivity(Clock clock) {
        this.clock = clock;
    }

    /** Runs {@code listener} on the thread of each piece of work that begins (after it counts as running): be quick. */
    public void onBegin(Runnable listener) {
        beginListeners.add(listener);
    }

    /** Work that uses the model starts; close what this returns when it is done (closing twice counts once). */
    public Use begin() {
        running.incrementAndGet();                          // first: a listener's warm-up then sees it running
        for (Runnable listener : beginListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.warn("A model activity listener failed: {}", e.toString());
            }
        }
        return new Use();
    }

    /** Nothing uses the model now, and nothing did for at least {@code quiet} (a follow-up question may be coming). */
    public boolean idleFor(Duration quiet) {
        return running.get() == 0 && !clock.instant().isBefore(lastEnded.plus(quiet));
    }

    /** One piece of work that uses the model. */
    public final class Use implements AutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean();

        private Use() {
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lastEnded = clock.instant();                 // before the count drops: idleFor never sees 0 too early
                running.decrementAndGet();
            }
        }
    }
}
