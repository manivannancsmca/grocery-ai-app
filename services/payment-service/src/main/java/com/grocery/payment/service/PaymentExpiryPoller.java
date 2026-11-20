package com.grocery.payment.service;

import com.grocery.payment.config.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Runs {@link PaymentExpiryService} every {@code grocery.payment.expiry-poll-interval} (30 s, fixed delay; Docker
 * Compose sets it with the env var {@code GROCERY_PAYMENT_EXPIRY_POLL_INTERVAL}), in batches of
 * {@code grocery.payment.expiry-batch-size}, until a batch comes back short. Safe with several replicas (claims use
 * {@code FOR UPDATE SKIP LOCKED} and a lease). A failing payment (e.g. provider unreachable) keeps its lease and is
 * retried when the lease runs out; the others go on.
 */
@Component
public class PaymentExpiryPoller {

    /** Upper bound of batches per run, so one run never monopolises the poller thread for long. */
    static final int MAX_BATCHES_PER_RUN = 20;
    private static final Logger log = LoggerFactory.getLogger(PaymentExpiryPoller.class);

    private final PaymentExpiryService expiry;
    private final int batchSize;

    public PaymentExpiryPoller(PaymentExpiryService expiry, PaymentProperties properties) {
        this.expiry = expiry;
        this.batchSize = properties.expiryBatchSize();
    }

    /** Scheduled only when {@code grocery.payment.pollers-enabled} (default true), see PaymentConfig. */
    @Scheduled(initialDelayString = "${grocery.payment.expiry-poll-interval:30s}",
            fixedDelayString = "${grocery.payment.expiry-poll-interval:30s}")
    public void scheduled() {
        run();
    }

    /** One run. Returns how many payments were claimed (expired, captured or left for later). */
    public int run() {
        int total = 0;
        int expired = 0;
        int captured = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<UUID> claimed;
            try {
                claimed = expiry.claim(batchSize);
            } catch (RuntimeException e) {
                log.warn("Claiming expired payments failed, retried in the next run: {}", e.toString());
                break;
            }
            for (UUID paymentId : claimed) {
                try {
                    switch (expiry.handle(paymentId)) {
                        case EXPIRED -> expired++;
                        case CAPTURED -> captured++;
                        default -> {
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("Expiry check of payment {} failed, retried after its lease: {}", paymentId,
                            e.toString());
                }
            }
            total += claimed.size();
            if (claimed.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("Payment window poller: {} claimed, {} expired, {} captured late-reported", total, expired,
                    captured);
        }
        return total;
    }
}
