package com.grocery.inventory.service;

import com.grocery.inventory.dto.InventoryDtos.RepublishResult;
import com.grocery.inventory.entity.ProductStatus;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.repository.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Re-sends {@code ProductAvailabilityChanged} for every non-archived product with its CURRENT {@code stockVersion}
 * (no increments, no locks), e.g. after the search index was lost. The read side ignores versions it already has
 * and recreates what it lost; a change committed meanwhile carries a higher version and wins. Products go in
 * batches of 500 in product id order, each batch in its own transaction.
 */
@Service
public class RepublishService {

    static final int BATCH_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(RepublishService.class);

    private final StockItemRepository stockItems;
    private final StockEventPublisher events;
    private final TransactionTemplate transaction;

    public RepublishService(StockItemRepository stockItems, StockEventPublisher events,
                            PlatformTransactionManager transactionManager) {
        this.stockItems = stockItems;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public RepublishResult republishAll() {
        long count = 0;
        UUID after = null;
        while (true) {
            UUID batchStart = after;
            List<UUID> sent = transaction.execute(status -> {
                PageRequest batch = PageRequest.of(0, BATCH_SIZE);
                List<StockItem> rows = batchStart == null
                        ? stockItems.findFirstLive(ProductStatus.ARCHIVED, batch)
                        : stockItems.findLiveAfter(ProductStatus.ARCHIVED, batchStart, batch);
                rows.forEach(events::availability);
                return rows.stream().map(StockItem::getProductId).toList();
            });
            count += sent.size();
            if (sent.size() < BATCH_SIZE) {
                break;
            }
            after = sent.getLast();
        }
        log.info("Republished the availability of {} product(s)", count);
        return new RepublishResult(count);
    }
}
