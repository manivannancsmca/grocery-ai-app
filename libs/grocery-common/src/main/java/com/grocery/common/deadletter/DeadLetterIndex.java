package com.grocery.common.deadletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

/**
 * This instance's in-memory view of its dead letters: the records of every {@code <topic>.DLT} whose original consumer
 * group is one of this service's groups, plus the latest state of each from {@code _grocery.dead-letter-actions}
 * (missing = {@link DeadLetterAction#PENDING}). Filled by {@link DeadLetterReader}; an action this instance writes is
 * applied at once too. Thread-safe.
 *
 * <p>Bounded: above {@code maxRecords} the closed records (REPLAYED, DISCARDED) are dropped first, then the oldest.
 * A dropped record is only gone from this instance's memory (the DLT keeps it for its retention; a restart reads it
 * again).
 */
public class DeadLetterIndex {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterIndex.class);

    /** A state together with its offset in the action topic: an older record never overwrites a newer state. */
    private record Entry(DeadLetterAction action, long offset) {
    }

    private final int maxRecords;
    private final ConcurrentMap<String, DeadLetter> records = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Entry> actions = new ConcurrentHashMap<>();
    private final Object evictionLock = new Object();

    public DeadLetterIndex(int maxRecords) {
        this.maxRecords = Math.max(1, maxRecords);
    }

    void add(DeadLetter deadLetter) {
        records.put(deadLetter.id(), deadLetter);
        if (records.size() > maxRecords + Math.max(10, maxRecords / 10)) {
            evictToLimit();
        }
    }

    /**
     * Applies the state read from (or just written to) the action topic at {@code offset}; {@code null} = tombstone.
     */
    void applyAction(String key, DeadLetterAction action, long offset) {
        actions.compute(key, (k, current) -> {
            if (current != null && current.offset() > offset) {
                return current;                                       // a newer state is already known
            }
            return action == null ? null : new Entry(action, offset);
        });
    }

    /**
     * Applies a state that could not be written to the action topic: it replaces the current one, and any later
     * record of the topic replaces it in turn.
     */
    void applyLocally(String key, DeadLetterAction action) {
        actions.compute(key, (k, current) -> new Entry(action, current == null ? -1 : current.offset()));
    }

    Optional<DeadLetter> find(String id) {
        return Optional.ofNullable(id == null ? null : records.get(id));
    }

    DeadLetterAction state(DeadLetter deadLetter) {
        Entry entry = actions.get(deadLetter.actionKey());
        return entry == null ? DeadLetterAction.PENDING : entry.action();
    }

    /** Matching dead letters, newest first (by the time they were dead-lettered, then by id). */
    List<DeadLetter> newestFirst(Predicate<DeadLetter> filter) {
        List<DeadLetter> matching = new ArrayList<>();
        for (DeadLetter deadLetter : records.values()) {
            if (filter.test(deadLetter)) {
                matching.add(deadLetter);
            }
        }
        matching.sort(OLDEST_FIRST.reversed());
        return matching;
    }

    /** Matching dead letters, oldest first. */
    List<DeadLetter> oldestFirst(Predicate<DeadLetter> filter) {
        List<DeadLetter> matching = newestFirst(filter);
        return matching.reversed();
    }

    int size() {
        return records.size();
    }

    /** Dead letters of {@code consumerGroup} still open (PENDING or REPLAY_FAILED): {@code grocery_dead_letters_pending}. */
    long openCount(String consumerGroup) {
        long open = 0;
        for (DeadLetter deadLetter : records.values()) {
            if (deadLetter.consumerGroup().equals(consumerGroup) && !state(deadLetter).status().isClosed()) {
                open++;
            }
        }
        return open;
    }

    /** Forgets records (and their states) that the DLT's retention has removed: offsets below the log start offset. */
    void evictBelow(String dltTopic, int partition, long logStartOffset) {
        records.values().removeIf(d -> d.dltTopic().equals(dltTopic) && d.dltPartition() == partition
                && d.dltOffset() < logStartOffset);
        String prefix = "|" + dltTopic + "|" + partition + "|";
        actions.keySet().removeIf(key -> {
            int at = key.indexOf(prefix);
            if (at < 0) {
                return false;
            }
            try {
                return Long.parseLong(key.substring(at + prefix.length())) < logStartOffset;
            } catch (NumberFormatException e) {
                return false;
            }
        });
    }

    private void evictToLimit() {
        synchronized (evictionLock) {
            int excess = records.size() - maxRecords;
            if (excess <= 0) {
                return;
            }
            List<DeadLetter> candidates = new ArrayList<>(records.values());
            // closed ones first, then the oldest
            candidates.sort(Comparator.comparing((DeadLetter d) -> !state(d).status().isClosed()).thenComparing(OLDEST_FIRST));
            for (int i = 0; i < excess && i < candidates.size(); i++) {
                records.remove(candidates.get(i).id());
            }
            log.warn("Dead-letter view is full ({} records, grocery.dead-letters.max-records={}): dropped the {} "
                    + "oldest (closed ones first) from memory; they stay in their DLT", records.size(), maxRecords, excess);
        }
    }

    private static final Comparator<DeadLetter> OLDEST_FIRST = Comparator.comparingLong(DeadLetter::sortTime)
            .thenComparing(DeadLetter::dltTopic)
            .thenComparingInt(DeadLetter::dltPartition)
            .thenComparingLong(DeadLetter::dltOffset);
}
