package com.grocery.catalog.service;

import com.grocery.catalog.dto.ProductDtos.RepublishResult;
import com.grocery.catalog.entity.Category;
import com.grocery.catalog.repository.CategoryRepository;
import com.grocery.catalog.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Re-sends every category and product snapshot with its CURRENT version (no increments), e.g. after the
 * search index was lost or recreated. The read side skips equal versions it already has and recreates the
 * documents it lost. Categories go first (one transaction), then products in batches of 500, each batch in
 * its own transaction so a large catalog never holds one long transaction.
 */
@Service
public class RepublishService {

    static final int BATCH_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(RepublishService.class);

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final SnapshotPublisher snapshots;
    private final TransactionTemplate transaction;

    public RepublishService(CategoryRepository categories, ProductRepository products, SnapshotPublisher snapshots,
                            PlatformTransactionManager transactionManager) {
        this.categories = categories;
        this.products = products;
        this.snapshots = snapshots;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public RepublishResult republishAll() {
        int categoryCount = transaction.execute(status -> {
            CategoryTree tree = new CategoryTree(categories.findAll());
            List<Category> parentsFirst = new ArrayList<>();
            tree.roots().forEach(root -> parentsFirst.addAll(tree.subtree(root.getId())));
            parentsFirst.forEach(c -> snapshots.republishCategory(c, tree));
            return parentsFirst.size();
        });

        int productCount = 0;
        UUID after = null;
        while (true) {
            UUID batchStart = after;
            Batch batch = transaction.execute(status -> {
                List<UUID> ids = batchStart == null
                        ? products.findFirstIds(PageRequest.of(0, BATCH_SIZE))
                        : products.findIdsAfter(batchStart, PageRequest.of(0, BATCH_SIZE));
                if (ids.isEmpty()) {
                    return new Batch(ids, 0);
                }
                int sent = snapshots.republishProducts(products.findAllById(ids), new CategoryTree(categories.findAll()));
                return new Batch(ids, sent);
            });
            productCount += batch.sent();
            if (batch.ids().size() < BATCH_SIZE) {
                break;
            }
            after = batch.ids().getLast();
        }
        log.info("Republished {} category and {} product snapshot(s)", categoryCount, productCount);
        return new RepublishResult(categoryCount, productCount);
    }

    private record Batch(List<UUID> ids, int sent) {
    }
}
