package com.grocery.ai.approval;

import com.grocery.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every {@code AI_APPROVAL_EXPIRY_INTERVAL} (30 s, fixed delay):
 * <ul>
 *   <li><b>expiry</b>: PENDING_APPROVAL requests past {@code expires_at} → EXPIRED + ApprovalDecided(EXPIRED);</li>
 *   <li><b>recovery</b>: requests left EXECUTING for more than {@code executing-timeout} (2 min: the replica crashed
 *       during the call) → FAILED EXECUTION_INTERRUPTED + ApprovalFailed. Whether the target ran it is in its
 *       single-use record; the request is never retried.</li>
 * </ul>
 * Each batch is claimed and changed in one transaction with {@code SELECT … FOR UPDATE SKIP LOCKED}, so replicas take
 * disjoint rows and a decision in flight (holding the row) is skipped; no remote call happens here.
 */
@Component
public class ApprovalMaintenance {

    public static final String EXECUTION_INTERRUPTED = "EXECUTION_INTERRUPTED";
    static final int MAX_BATCHES_PER_RUN = 20;
    private static final Logger log = LoggerFactory.getLogger(ApprovalMaintenance.class);

    private final ApprovalRepository approvals;
    private final ApprovalEvents events;
    private final AiProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ApprovalMaintenance(ApprovalRepository approvals, ApprovalEvents events, AiProperties properties,
                               PlatformTransactionManager transactionManager, Clock clock) {
        this.approvals = approvals;
        this.events = events;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Scheduled only when {@code grocery.ai.approvals.pollers-enabled} (default true), see AiConfig. */
    @Scheduled(initialDelayString = "${grocery.ai.approvals.expiry-interval:30s}",
            fixedDelayString = "${grocery.ai.approvals.expiry-interval:30s}")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException e) {
            log.warn("Approval maintenance failed, retried in the next run: {}", e.toString());
        }
    }

    /** One run; returns {expired, interrupted}. */
    public int[] run() {
        return new int[]{expireOverdue(), recoverInterrupted()};
    }

    public int expireOverdue() {
        int total = 0;
        int batchSize = properties.approvals().batchSize();
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            Integer done = transaction.execute(status -> {
                Instant now = clock.instant();
                List<UUID> ids = approvals.claimOverdue(now, batchSize).stream().map(UUID::fromString).toList();
                for (ApprovalRequest approval : approvals.findAllById(ids)) {
                    approval.expire(now);
                    approvals.saveAndFlush(approval);
                    events.decided(approval, ApprovalStatus.EXPIRED.name());
                    log.info("Approval {} ({}) expired: nobody decided in time", approval.getId(),
                            approval.getAction());
                }
                return ids.size();
            });
            int count = done == null ? 0 : done;
            total += count;
            if (count < batchSize) {
                break;
            }
        }
        return total;
    }

    public int recoverInterrupted() {
        int total = 0;
        int batchSize = properties.approvals().batchSize();
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            Integer done = transaction.execute(status -> {
                Instant now = clock.instant();
                Instant before = now.minus(properties.approvals().executingTimeout());
                List<UUID> ids = approvals.claimInterrupted(before, batchSize).stream().map(UUID::fromString).toList();
                for (ApprovalRequest approval : approvals.findAllById(ids)) {
                    approval.fail(EXECUTION_INTERRUPTED, "The execution was interrupted. Whether the action ran is "
                            + "recorded by the target service; ask the assistant for a new proposal if needed.", null,
                            now);
                    approvals.saveAndFlush(approval);
                    events.failed(approval);
                    log.warn("Approval {} ({}) was left EXECUTING since {}: FAILED {}", approval.getId(),
                            approval.getAction(), approval.getExecutingSince(), EXECUTION_INTERRUPTED);
                }
                return ids.size();
            });
            int count = done == null ? 0 : done;
            total += count;
            if (count < batchSize) {
                break;
            }
        }
        return total;
    }
}
