package com.grocery.payment.service;

import com.grocery.payment.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Retries PENDING refunds whose provider call failed temporarily (no provider refund id yet: create it again, same
 * idempotency key) and re-checks PENDING refunds the provider accepted but has not reported back on (a lost
 * {@code refund.processed} webhook). Every {@code grocery.payment.refund-retry-interval} (60 s, fixed delay; env
 * {@code GROCERY_PAYMENT_REFUND_RETRY_INTERVAL}), batches of {@code grocery.payment.refund-batch-size}. Safe with
 * several replicas: refunds are claimed with {@code FOR UPDATE SKIP LOCKED} and leased before the provider is called
 * ({@link RefundService#claimDue}).
 */
@Component
public class RefundRetryPoller {

    static final int MAX_BATCHES_PER_RUN = 10;
    private static final Logger log = LoggerFactory.getLogger(RefundRetryPoller.class);

    private final RefundService refunds;
    private final int batchSize;

    public RefundRetryPoller(RefundService refunds, PaymentProperties properties) {
        this.refunds = refunds;
        this.batchSize = properties.refundBatchSize();
    }

    /** Scheduled only when {@code grocery.payment.pollers-enabled} (default true), see PaymentConfig. */
    @Scheduled(initialDelayString = "${grocery.payment.refund-retry-interval:60s}",
            fixedDelayString = "${grocery.payment.refund-retry-interval:60s}")
    public void scheduled() {
        retryDue();
    }

    /** One run; returns how many refunds were sent to / checked at the provider. */
    public int retryDue() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<UUID> claimed;
            try {
                claimed = refunds.claimDue(batchSize);
            } catch (RuntimeException e) {
                log.warn("Claiming due refunds failed, retried in the next run: {}", e.toString());
                break;
            }
            for (UUID refundId : claimed) {
                try {
                    refunds.process(refundId);
                } catch (RuntimeException e) {
                    log.warn("Retrying refund {} failed, retried after its lease: {}", refundId, e.toString());
                }
            }
            total += claimed.size();
            if (claimed.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Retried {} pending refund(s)", total);
        }
        return total;
    }
}
