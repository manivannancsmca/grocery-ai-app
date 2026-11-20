package com.grocery.catalog.service;

import com.grocery.catalog.dto.ProductDtos.BrandView;
import com.grocery.catalog.entity.Brand;
import com.grocery.catalog.mapper.ProductMapper;
import com.grocery.catalog.repository.BrandRepository;
import com.grocery.common.id.Ids;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/** The shared brand dictionary: find-or-create for product requests, suggestions for the brand field. */
@Service
public class BrandService {

    private static final int SUGGESTIONS = 10;

    private final BrandRepository brands;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    public BrandService(BrandRepository brands, PlatformTransactionManager transactionManager, Clock clock) {
        this.brands = brands;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * The brand matching {@code name} (by {@link Brand#nameKey}), created if new; null for a blank name.
     * Runs in its own short transaction and is called BEFORE the product transaction, so the product
     * transaction always sees the committed brand. Race-safe: when two requests create the same brand at
     * once, the loser's insert hits the unique key and it re-reads the winner's row. A brand created for a
     * product request that then fails stays in the dictionary (harmless).
     */
    public Brand findOrCreate(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = Brand.nameKey(name);
        try {
            return newTransaction.execute(status -> brands.findByNameKey(key)
                    .orElseGet(() -> brands.saveAndFlush(new Brand(Ids.newId(), name, clock.instant()))));
        } catch (DataIntegrityViolationException e) {
            return newTransaction.execute(status -> brands.findByNameKey(key)).orElseThrow(() -> e);
        }
    }

    /** Up to 10 existing brands for the brand field (prefix matches first). */
    @Transactional(readOnly = true)
    public List<BrandView> suggest(String query) {
        PageRequest top = PageRequest.of(0, SUGGESTIONS);
        List<Brand> found = query == null || query.isBlank()
                ? brands.firstByName(top)
                : brands.suggest(Brand.nameKey(query), top);
        return found.stream().map(ProductMapper::toView).toList();
    }
}
