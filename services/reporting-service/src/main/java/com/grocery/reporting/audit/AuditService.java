package com.grocery.reporting.audit;

import com.grocery.common.export.RowSource;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.common.web.PageResponse;
import com.grocery.reporting.audit.AuditMapper.Mapped;
import com.grocery.reporting.audit.AuditRepository.Filter;
import com.grocery.reporting.audit.AuditRepository.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The central audit trail (README "Audit trail"): events in ({@value #CONSUMER}, one transaction per event:
 * {@code processed_event}, then the entry and what it tells about users), the admin search out, and the purge of
 * entries past their retention.
 */
@Service
public class AuditService {

    public static final String CONSUMER = "reporting-service.audit";
    /** The sign-in events: kept {@code sign-in-retention}, everything else {@code retention}. */
    static final List<String> SIGN_IN_CATEGORIES = List.of("SECURITY");
    private static final int PURGE_BATCH = 5_000;
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** What one event did. */
    public enum Outcome { WRITTEN, DUPLICATE, IGNORED }

    private final AuditRepository audit;
    private final AuditMapper mapper;
    private final ProcessedEventGuard guard;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration retention;
    private final Duration signInRetention;

    public AuditService(AuditRepository audit, ProcessedEventGuard guard, PlatformTransactionManager transactionManager,
                        JsonMapper json, Clock clock,
                        @Value("${grocery.reporting.audit.retention:730d}") Duration retention,
                        @Value("${grocery.reporting.audit.sign-in-retention:180d}") Duration signInRetention) {
        this.audit = audit;
        this.mapper = new AuditMapper(json, audit);
        this.guard = guard;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
        this.retention = retention;
        this.signInRetention = signInRetention;
    }

    /**
     * @param occurredAt the event's {@code occurredAt} header ({@code null} if missing or unreadable)
     */
    public Outcome apply(EventEnvelope event, Instant occurredAt) {
        if (!AuditMapper.AUDITED.contains(event.eventType()) || !AuditMapper.SOURCES.containsKey(event.topic())) {
            return Outcome.IGNORED;
        }
        UUID eventId;
        JsonNode payload;
        try {
            eventId = UUID.fromString(event.eventId());
            payload = event.payloadAs(json, JsonNode.class);
        } catch (RuntimeException e) {
            throw new InvalidEventException("Unreadable " + event.eventType() + " " + event.eventId() + ": "
                    + e.getMessage(), e);
        }
        Instant now = clock.instant();
        Optional<Mapped> mapped = transactions.execute(status -> {
            if (!guard.markProcessed(event.eventId(), CONSUMER)) {
                return null;
            }
            Optional<Mapped> entry = mapper.map(event.topic(), event.eventType(), eventId, payload,
                    occurredAt == null ? now : occurredAt);
            entry.ifPresent(m -> {
                audit.insert(m.entry(), now);
                m.users().forEach(audit::remember);
            });
            return entry;
        });
        Outcome outcome = mapped == null ? Outcome.DUPLICATE : mapped.isPresent() ? Outcome.WRITTEN : Outcome.IGNORED;
        log.debug("{} {} on {}: {}", event.eventType(), event.eventId(), event.topic(), outcome);
        return outcome;
    }

    /** One page of the search, newest first. */
    public PageResponse<Row> search(Filter filter, int page, int size) {
        long total = audit.count(filter);
        List<Row> rows = audit.page(filter, size, (long) page * size);
        return new PageResponse<>(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    /** The whole search for an export, read in keyset batches (no connection held while a batch is written). */
    public RowSource<Row> export(Filter filter) {
        return new RowSource<>() {
            @Override
            public long count() {
                return audit.count(filter);
            }

            @Override
            public void forEachBatch(int batchSize, BatchHandler<? super Row> handler) throws IOException {
                Instant beforeTime = null;
                UUID beforeId = null;
                while (true) {
                    List<Row> batch = audit.batch(filter, beforeTime, beforeId, batchSize);
                    if (batch.isEmpty() || !handler.handle(batch) || batch.size() < batchSize) {
                        return;
                    }
                    beforeTime = batch.getLast().occurredAt();
                    beforeId = batch.getLast().id();
                }
            }
        };
    }

    /**
     * Daily: deletes sign-in events older than {@code grocery.reporting.audit.sign-in-retention} (180 days) and every
     * other entry older than {@code grocery.reporting.audit.retention} (730 days), in batches. {@code -} as the cron
     * turns it off (tests).
     */
    @Scheduled(cron = "${grocery.reporting.audit.purge-cron:0 40 3 * * *}", zone = "Asia/Kolkata")
    public void purge() {
        Instant now = clock.instant();
        int signIns = purge(now.minus(signInRetention), SIGN_IN_CATEGORIES, false);
        int others = purge(now.minus(retention), SIGN_IN_CATEGORIES, true);
        if (signIns + others > 0) {
            log.info("Audit trail purge: {} sign-in entries older than {}, {} other entries older than {}", signIns,
                    signInRetention, others, retention);
        }
    }

    private int purge(Instant before, List<String> categories, boolean except) {
        int total = 0;
        int deleted;
        do {
            deleted = audit.purge(before, categories, except, PURGE_BATCH);
            total += deleted;
        } while (deleted == PURGE_BATCH);
        return total;
    }
}
