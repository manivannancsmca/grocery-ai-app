package com.grocery.review.service;

import com.grocery.review.config.ReviewProperties;
import com.grocery.review.entity.TargetType;
import com.grocery.review.repository.RatingSummaries;
import com.grocery.review.repository.RatingSummaries.Summary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The rating summary of a target (contract §1): average of the published ratings (2 decimals, half up), their count
 * and the 1–5 distribution. Every change of a target's published reviews recomputes it from the review table inside
 * the same transaction, under the summary row's lock; when the result differs, {@code summaryVersion} goes up by one
 * and {@code RatingSummaryUpdated} is published.
 */
@Service
public class RatingSummaryService {

    private static final Logger log = LoggerFactory.getLogger(RatingSummaryService.class);
    static final BigDecimal ZERO = new BigDecimal("0.00");

    private final RatingSummaries summaries;
    private final ReviewEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final ReviewProperties properties;

    public RatingSummaryService(RatingSummaries summaries, ReviewEventPublisher events, Clock clock,
                                TransactionTemplate transactions, ReviewProperties properties) {
        this.summaries = summaries;
        this.events = events;
        this.clock = clock;
        this.transactions = transactions;
        this.properties = properties;
    }

    /**
     * Recomputes the target's summary; publishes it when it changed.
     *
     * @return the summary as stored now
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Summary recompute(TargetType targetType, UUID targetId) {
        Instant now = clock.instant();
        Summary current = summaries.lock(targetType, targetId, now);
        int[] counts = summaries.publishedDistribution(targetType, targetId);
        Summary computed = summarise(targetType, targetId, counts, current.version() + 1, now);
        if (current.version() > 0 && current.ratingCount() == computed.ratingCount()
                && current.distribution().equals(computed.distribution())) {
            return current;
        }
        if (current.version() == 0 && computed.ratingCount() == 0) {
            return current;         // nothing was ever published for this target, and still nothing is
        }
        summaries.update(computed);
        events.ratingSummaryUpdated(computed);
        return computed;
    }

    /** The public summary; a target without published reviews has count 0, average 0.00 and [0, 0, 0, 0, 0]. */
    @Transactional(readOnly = true)
    public Summary summary(TargetType targetType, UUID targetId) {
        return summaries.find(targetType, targetId)
                .orElseGet(() -> new Summary(targetType, targetId, ZERO, 0, List.of(0, 0, 0, 0, 0), 0, null));
    }

    /**
     * Publishes every published summary again with its <b>current</b> version (nothing changes here): a consumer that
     * lost its copy (e.g. a rebuilt search index) gets it back, one that has it ignores the same version.
     *
     * @return how many summaries were published
     */
    public int republishAll() {
        int batchSize = Math.max(1, properties.republishBatchSize());
        int total = 0;
        TargetType afterType = null;
        UUID afterId = null;
        while (true) {
            TargetType fromType = afterType;
            UUID fromId = afterId;
            List<Summary> batch = transactions.execute(status -> {
                List<Summary> page = summaries.publishedAfter(fromType, fromId, batchSize);
                page.forEach(events::ratingSummaryUpdated);
                return page;
            });
            if (batch == null || batch.isEmpty()) {
                break;
            }
            total += batch.size();
            Summary last = batch.getLast();
            afterType = last.targetType();
            afterId = last.targetId();
            if (batch.size() < batchSize) {
                break;
            }
        }
        log.info("Republished {} rating summaries", total);
        return total;
    }

    /** Count, distribution and the average with 2 decimals (half up). */
    static Summary summarise(TargetType targetType, UUID targetId, int[] counts, long version, Instant now) {
        int count = Arrays.stream(counts).sum();
        long sum = 0;
        for (int stars = 1; stars <= 5; stars++) {
            sum += (long) stars * counts[stars - 1];
        }
        BigDecimal average = count == 0 ? ZERO
                : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
        return new Summary(targetType, targetId, average, count, Arrays.stream(counts).boxed().toList(), version, now);
    }
}
