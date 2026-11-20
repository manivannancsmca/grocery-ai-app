package com.grocery.common.deadletter;

import com.grocery.common.web.PageResponse;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The dead-letter operations of this service (contract Phase 10 §1): list, detail, replay, discard, replay-all.
 *
 * <p>Consistency across replicas: before a state-changing action and before answering a detail or a list, the
 * instance catches up with the end of {@code _grocery.dead-letter-actions} (bounded wait, see
 * {@link DeadLetterReader#catchUp}), so a dead letter replayed or discarded through one replica is closed on the next
 * request to any other (replay twice → the second is 409 DEAD_LETTER_CLOSED). Actions on one instance are serialised
 * per dead letter. Two replays of the same dead letter started at the very same moment on two replicas can both run;
 * that is harmless because the listener's business idempotency ({@code processed_event}) applies the event once.
 */
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_REASON_LENGTH = 300;
    public static final int REPLAY_ALL_LIMIT = 100;
    static final int PREVIEW_LIMIT = 500;
    private static final long PREVIEW_RETRY_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final DeadLetterIndex index;
    private final DeadLetterReader reader;
    private final DeadLetterActionLog actionLog;
    private final DeadLetterReplayer replayer;
    private final DeadLetterPayloadDecoder decoder;
    private final JsonMapper jsonMapper;
    private final ObjectReader exactJsonReader;
    private final Clock clock;
    private final Object[] locks = new Object[64];

    public DeadLetterService(DeadLetterIndex index, DeadLetterReader reader, DeadLetterActionLog actionLog,
                             DeadLetterReplayer replayer, DeadLetterPayloadDecoder decoder, JsonMapper jsonMapper,
                             Clock clock) {
        this.index = index;
        this.reader = reader;
        this.actionLog = actionLog;
        this.replayer = replayer;
        this.decoder = decoder;
        this.jsonMapper = jsonMapper;
        this.exactJsonReader = jsonMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
        this.clock = clock;
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * Newest first; {@code page} ≥ 0, 1 ≤ {@code size} ≤ 100 (default 20). Any filter may be null; {@code statuses}
     * null or empty = every status, otherwise any of them.
     */
    public PageResponse<DeadLetterView> list(Collection<DeadLetterStatus> statuses, String consumerGroup,
                                             String eventType, Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        reader.catchUp(reader.actionPartitions());
        Set<DeadLetterStatus> wanted = statuses == null || statuses.isEmpty() ? null : EnumSet.copyOf(statuses);
        List<DeadLetter> matching = index.newestFirst(matches(wanted, consumerGroup, eventType));
        long from = (long) safePage * safeSize;
        List<DeadLetterView> content = from >= matching.size() ? List.of()
                : matching.subList((int) from, (int) Math.min(matching.size(), from + safeSize)).stream().map(this::view).toList();
        int totalPages = (int) ((matching.size() + safeSize - 1) / safeSize);
        return new PageResponse<>(content, safePage, safeSize, matching.size(), totalPages);
    }

    /** @throws DeadLetterException 404 DEAD_LETTER_NOT_FOUND */
    public DeadLetterDetail get(String id) {
        catchUp(id);
        DeadLetter deadLetter = require(id);
        return new DeadLetterDetail(view(deadLetter), payload(deadLetter), deadLetter.stackTrace());
    }

    /**
     * Hands the dead letter to its listener again: REPLAYED, or REPLAY_FAILED with {@code lastError} (attempts + 1).
     *
     * @throws DeadLetterException 404 DEAD_LETTER_NOT_FOUND, 409 DEAD_LETTER_CLOSED (already REPLAYED / DISCARDED),
     *                             503 DEAD_LETTER_STATUS_NOT_SAVED (the replay ran but Kafka did not take its state)
     */
    public DeadLetterView replay(String id, String actor) {
        catchUp(id);
        DeadLetter deadLetter = require(id);
        synchronized (lockFor(deadLetter)) {
            DeadLetterAction current = index.state(deadLetter);
            if (current.status().isClosed()) {
                throw DeadLetterException.closed(id, current.status());
            }
            replayLocked(deadLetter, current, actor);
            return view(deadLetter);
        }
    }

    /**
     * @throws DeadLetterException 400 (reason missing or longer than 300), 404, 409 DEAD_LETTER_CLOSED, 503
     */
    public DeadLetterView discard(String id, String reason, String actor) {
        String trimmed = reason == null ? null : reason.strip();
        if (trimmed == null || trimmed.isEmpty()) {
            throw DeadLetterException.invalid("reason", "must not be blank");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw DeadLetterException.invalid("reason", "size must be between 1 and " + MAX_REASON_LENGTH);
        }
        catchUp(id);
        DeadLetter deadLetter = require(id);
        synchronized (lockFor(deadLetter)) {
            DeadLetterAction current = index.state(deadLetter);
            if (current.status().isClosed()) {
                throw DeadLetterException.closed(id, current.status());
            }
            DeadLetterAction discarded = new DeadLetterAction(DeadLetterStatus.DISCARDED, current.attempts(),
                    current.lastError(), actor, clock.instant(), trimmed);
            try {
                save(deadLetter, discarded);
            } catch (RuntimeException e) {
                log.error("Discarding dead letter {} ({}) by {} failed: {}", id, deadLetter.consumerGroup(), actor, e.toString());
                throw DeadLetterException.statusNotSaved("The dead letter could not be discarded: its status cannot be "
                        + "saved (" + e.getMessage() + "). Nothing was changed; try again.", current.status());
            }
            log.info("Dead letter {} ({} {} of {}) DISCARDED by {}: {}", id, deadLetter.eventType(),
                    deadLetter.eventId(), deadLetter.consumerGroup(), actor, trimmed);
            return view(deadLetter);
        }
    }

    /**
     * Replays the PENDING and REPLAY_FAILED dead letters matching the filters, oldest first, at most 100 per call.
     */
    public DeadLetterRequests.ReplayAllResult replayAll(String consumerGroup, String eventType, String actor) {
        Set<TopicPartition> partitions = new HashSet<>(reader.actionPartitions());
        partitions.addAll(reader.deadLetterPartitions());
        reader.catchUp(partitions);
        List<DeadLetter> candidates = index.oldestFirst(matches(null, blankToNull(consumerGroup), blankToNull(eventType))
                .and(d -> !index.state(d).status().isClosed()));
        int attempted = 0;
        int replayed = 0;
        int failed = 0;
        for (DeadLetter deadLetter : candidates.subList(0, Math.min(REPLAY_ALL_LIMIT, candidates.size()))) {
            synchronized (lockFor(deadLetter)) {
                DeadLetterAction current = index.state(deadLetter);
                if (current.status().isClosed()) {
                    continue;                                   // closed meanwhile (another request / replica)
                }
                attempted++;
                if (replayLocked(deadLetter, current, actor).status() == DeadLetterStatus.REPLAYED) {
                    replayed++;
                } else {
                    failed++;
                }
            }
        }
        log.info("Replay-all by {} (consumerGroup={}, eventType={}): attempted {}, replayed {}, failed {}", actor,
                consumerGroup, eventType, attempted, replayed, failed);
        return new DeadLetterRequests.ReplayAllResult(attempted, replayed, failed);
    }

    private DeadLetterAction replayLocked(DeadLetter deadLetter, DeadLetterAction current, String actor) {
        long started = System.nanoTime();
        DeadLetterReplayer.Outcome outcome = replayer.replay(deadLetter);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        DeadLetterAction next = outcome.succeeded()
                ? new DeadLetterAction(DeadLetterStatus.REPLAYED, current.attempts() + 1, null, actor, clock.instant(), null)
                : new DeadLetterAction(DeadLetterStatus.REPLAY_FAILED, current.attempts() + 1, outcome.error(), actor,
                        clock.instant(), null);
        if (outcome.succeeded()) {
            log.info("Dead letter {} ({} {} of {}) REPLAYED by {} in {} ms (attempt {})", deadLetter.id(),
                    deadLetter.eventType(), deadLetter.eventId(), deadLetter.consumerGroup(), actor, millis, next.attempts());
        } else {
            log.warn("Dead letter {} ({} {} of {}) REPLAY_FAILED by {} in {} ms (attempt {}): {}", deadLetter.id(),
                    deadLetter.eventType(), deadLetter.eventId(), deadLetter.consumerGroup(), actor, millis,
                    next.attempts(), outcome.error());
        }
        try {
            save(deadLetter, next);
        } catch (RuntimeException e) {
            // The listener did run: this instance keeps the outcome, other replicas still see the previous state
            // (replaying there again is harmless thanks to the listener's idempotency).
            index.applyLocally(deadLetter.actionKey(), next);
            log.error("The state {} of dead letter {} could not be saved: {}", next.status(), deadLetter.id(), e.toString());
            throw DeadLetterException.statusNotSaved("The replay ran (outcome " + next.status() + ") but its status "
                    + "cannot be saved (" + e.getMessage() + ")", next.status());
        }
        return next;
    }

    private void save(DeadLetter deadLetter, DeadLetterAction action) {
        long offset = actionLog.append(deadLetter.actionKey(), action);
        index.applyAction(deadLetter.actionKey(), action, offset);
    }

    private void catchUp(String id) {
        Set<TopicPartition> partitions = new HashSet<>(reader.actionPartitions());
        TopicPartition partition = DeadLetter.partitionOf(id);
        if (partition != null) {
            partitions.add(partition);
        }
        reader.catchUp(partitions);
    }

    private DeadLetter require(String id) {
        return index.find(id).orElseThrow(() -> DeadLetterException.notFound(id));
    }

    private Object lockFor(DeadLetter deadLetter) {
        return locks[Math.floorMod(deadLetter.actionKey().hashCode(), locks.length)];
    }

    private Predicate<DeadLetter> matches(Set<DeadLetterStatus> statuses, String consumerGroup, String eventType) {
        return d -> (statuses == null || statuses.contains(index.state(d).status()))
                && (consumerGroup == null || consumerGroup.equals(d.consumerGroup()))
                && (eventType == null || eventType.equals(d.eventType()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    DeadLetterView view(DeadLetter d) {
        DeadLetterAction state = index.state(d);
        return new DeadLetterView(d.id(), d.dltTopic(), d.originalTopic(), d.originalPartition(), d.originalOffset(),
                d.consumerGroup(), d.keyText(), d.eventId(), d.eventType(), d.eventVersion(), d.occurredAt(),
                d.deadLetteredAt(), d.exceptionClass(), d.exceptionMessage(), preview(d), state.status(),
                state.attempts(), state.lastError(), state.actor(), state.at());
    }

    /** Decoded once and cached; a failure (e.g. Schema Registry down) is retried after a minute. */
    private String preview(DeadLetter d) {
        if (d.previewDecoded()) {
            return d.payloadPreview();
        }
        if (d.previewFailedRecently(PREVIEW_RETRY_NANOS)) {
            return null;
        }
        try {
            String text = text(decoder.decode(d.originalTopic(), d.headersCopy(), d.value()));
            String preview = text == null || text.length() <= PREVIEW_LIMIT ? text
                    : text.substring(0, PREVIEW_LIMIT - 1) + "…";
            d.previewDecoded(preview);
            return preview;
        } catch (RuntimeException | LinkageError e) {
            // an unreadable record is still listed, without preview: one bad record never fails a listing
            log.debug("Cannot decode the payload of dead letter {}: {}", d.id(), e.toString());
            d.previewFailed();
            return null;
        }
    }

    private JsonNode payload(DeadLetter d) {
        String text;
        try {
            text = text(decoder.decode(d.originalTopic(), d.headersCopy(), d.value()));
        } catch (RuntimeException | LinkageError e) {
            log.debug("Cannot decode the payload of dead letter {}: {}", d.id(), e.toString());
            return null;
        }
        if (text == null) {
            return null;
        }
        try {
            return exactJsonReader.readTree(text);
        } catch (JacksonException e) {
            return jsonMapper.valueToTree(text);                  // not JSON: shown as a string
        }
    }

    private String text(Object decoded) {
        if (decoded == null) {
            return null;
        }
        if (decoded instanceof String string) {
            return string;
        }
        if (decoded instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return jsonMapper.writeValueAsString(decoded);
    }
}
