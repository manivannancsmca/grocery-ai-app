package com.grocery.notification.service;

import com.grocery.notification.config.NotificationProperties;
import com.grocery.notification.repository.AiApprovalRefRepository;
import com.grocery.notification.repository.NotificationRepository;
import com.grocery.notification.repository.PendingEventRepository;
import com.grocery.notification.repository.PendingEventRepository.PendingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The nightly cleanup ({@code retention-cron}, India time). Safe on several replicas at once: the deletes are
 * idempotent.
 * <ul>
 *   <li>notifications older than {@code retention} (90 days), in batches of {@value #BATCH} (each its own short
 *       transaction; audience and read rows cascade);</li>
 *   <li>(Phase 11) rows of the assistants' approval projection ({@code ai_approval_ref}) older than {@code retention}: a
 *       request is decided or expired within a day;</li>
 *   <li>parked payment events older than {@code pending-event-max-age} (24 h), each logged with a WARN: their order
 *       never arrived, so they can only belong to orders this service never heard of.</li>
 * </ul>
 */
@Component
public class NotificationRetention {

    static final int BATCH = 1_000;
    private static final Logger log = LoggerFactory.getLogger(NotificationRetention.class);

    private final NotificationRepository notifications;
    private final PendingEventRepository pendingEvents;
    private final AiApprovalRefRepository approvals;
    private final Clock clock;
    private final Duration retention;
    private final Duration pendingEventMaxAge;

    public NotificationRetention(NotificationRepository notifications, PendingEventRepository pendingEvents,
                                 AiApprovalRefRepository approvals, Clock clock, NotificationProperties properties) {
        this.notifications = notifications;
        this.pendingEvents = pendingEvents;
        this.approvals = approvals;
        this.clock = clock;
        this.retention = properties.getRetention();
        this.pendingEventMaxAge = properties.getPendingEventMaxAge();
    }

    @Scheduled(cron = "${grocery.notification.retention-cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        Instant now = clock.instant();
        int deleted = deleteOlderThan(now.minus(retention));
        log.info("Retention: {} notifications older than {} deleted", deleted, retention);
        int forgotten = forgetApprovalsOlderThan(now.minus(retention));
        if (forgotten > 0) {
            log.info("Retention: {} assistant approval projection rows older than {} deleted", forgotten, retention);
        }
        int dropped = dropPendingEventsParkedBefore(now.minus(pendingEventMaxAge));
        if (dropped > 0) {
            log.warn("Retention: {} parked payment events older than {} dropped (their orders never arrived)",
                    dropped, pendingEventMaxAge);
        }
    }

    /** @return how many notifications were deleted */
    public int deleteOlderThan(Instant cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = notifications.deleteOlderThan(cutoff, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }

    /** Phase 11: @return how many {@code ai_approval_ref} rows were deleted */
    public int forgetApprovalsOlderThan(Instant cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = approvals.deleteOlderThan(cutoff, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        return total;
    }

    /** @return how many parked events were dropped (each logged with a WARN) */
    public int dropPendingEventsParkedBefore(Instant cutoff) {
        int total = 0;
        List<PendingEvent> expired;
        do {
            expired = pendingEvents.receivedBefore(cutoff, BATCH);
            for (PendingEvent event : expired) {
                log.warn("Dropping parked {} {} of order {} (parked at {}): the order was never seen",
                        event.eventType(), event.eventId(), event.orderId(), event.receivedAt());
            }
            total += pendingEvents.delete(expired.stream().map(PendingEvent::seq).toList());
        } while (expired.size() == BATCH);
        return total;
    }
}
