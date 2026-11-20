package com.grocery.pricing.service;

import com.grocery.pricing.dto.PriceDtos.RepublishResult;
import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.repository.ProductPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Re-sends {@code PriceChanged} for every live price with its CURRENT {@code priceVersion} (no increments), e.g.
 * after the search index was lost. Consumers skip versions they already have and rebuild what they lost.
 * Batches of 500 in id order, each in its own transaction, so a large catalog never holds one long transaction.
 * No locks are needed: each event is built from one consistent read of the row, so its version always matches
 * its data, and a concurrent change carries a higher version anyway.
 */
@Service
public class RepublishService {

    static final int BATCH_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(RepublishService.class);

    private final ProductPriceRepository prices;
    private final PriceWriter writer;
    private final TransactionTemplate transaction;

    public RepublishService(ProductPriceRepository prices, PriceWriter writer,
                            PlatformTransactionManager transactionManager) {
        this.prices = prices;
        this.writer = writer;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public RepublishResult republishAll() {
        int count = 0;
        UUID after = null;
        while (true) {
            UUID batchStart = after;
            List<UUID> batch = transaction.execute(status -> {
                List<ProductPrice> page = batchStart == null
                        ? prices.findFirstBatch(PageRequest.of(0, BATCH_SIZE))
                        : prices.findBatchAfter(batchStart, PageRequest.of(0, BATCH_SIZE));
                page.forEach(writer::republish);
                return page.stream().map(ProductPrice::getProductId).toList();
            });
            count += batch.size();
            if (batch.size() < BATCH_SIZE) {
                break;
            }
            after = batch.getLast();
        }
        log.info("Republished {} price(s)", count);
        return new RepublishResult(count);
    }
}
