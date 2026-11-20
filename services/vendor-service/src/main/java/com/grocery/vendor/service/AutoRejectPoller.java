package com.grocery.vendor.service;

import com.grocery.vendor.config.FulfillmentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Rejects NEW store orders nobody accepted before {@code acceptBy} (contract §2 "Auto-reject"): reason NO_RESPONSE,
 * {@code auto} true, no actor → {@code VendorOrderRejected}, which order-service turns into
 * {@code CANCELLED (STORE_NO_RESPONSE)} with a full refund.
 *
 * <p>Every {@code grocery.fulfillment.poll-interval} (15 s, fixed delay) it rejects batches of
 * {@code grocery.fulfillment.poll-batch-size} (20), each its own transaction, until a batch comes back short. Safe with
 * several replicas: each batch is claimed with {@code FOR UPDATE SKIP LOCKED}, so replicas take disjoint batches and an
 * order a store user is accepting right now (row locked) is skipped, then seen as ACCEPTED by the next run. A failed
 * batch is rolled back and retried in the next run. The {@link Clock} is injectable, so tests can run the poller "in the
 * future".
 */
@Component
public class AutoRejectPoller {

    /** Upper bound of batches per run, so one run never monopolises a connection for long. */
    static final int MAX_BATCHES_PER_RUN = 50;
    private static final Logger log = LoggerFactory.getLogger(AutoRejectPoller.class);

    private final StoreOrderService storeOrders;
    private final int batchSize;
    private final Clock clock;

    public AutoRejectPoller(StoreOrderService storeOrders, FulfillmentProperties properties, Clock clock) {
        this.storeOrders = storeOrders;
        this.batchSize = properties.pollBatchSize();
        this.clock = clock;
    }

    /** Scheduled only when {@code grocery.fulfillment.auto-reject-enabled} (default true), see VendorConfig. */
    @Scheduled(initialDelayString = "${grocery.fulfillment.poll-interval:15s}",
            fixedDelayString = "${grocery.fulfillment.poll-interval:15s}")
    public void scheduled() {
        rejectExpired();
    }

    /** One run: rejects everything past its deadline at the start of each batch. Returns how many were claimed. */
    public int rejectExpired() {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int claimed;
            try {
                claimed = storeOrders.autoRejectExpired(clock.instant(), batchSize);
            } catch (RuntimeException e) {
                log.warn("Auto-rejecting expired store orders failed, retried in the next run: {}", e.toString());
                break;
            }
            total += claimed;
            if (claimed < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Auto-rejected {} store order(s) past their acceptance deadline", total);
        }
        return total;
    }
}
