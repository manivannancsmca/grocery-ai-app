package com.grocery.pricing.service;

import com.grocery.pricing.cache.CachedPrice;
import com.grocery.pricing.cache.PriceQuoteCache;
import com.grocery.pricing.dto.PriceDtos.QuoteItem;
import com.grocery.pricing.dto.PriceDtos.QuoteResponse;
import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.entity.ProductStatus;
import com.grocery.pricing.repository.ProductPriceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The price quote (contract §5): read through the Redis cache (one MGET), the misses from MySQL in one query,
 * which then fill the cache (compare-and-set by version, see {@link PriceQuoteCache}). Not transactional on
 * purpose: no database connection is held while Redis is called.
 */
@Service
public class PriceQuoteService {

    private final PriceQuoteCache cache;
    private final ProductPriceRepository prices;

    public PriceQuoteService(PriceQuoteCache cache, ProductPriceRepository prices) {
        this.cache = cache;
        this.prices = prices;
    }

    public QuoteResponse quote(List<UUID> productIds) {
        List<UUID> ids = productIds.stream().distinct().toList();
        Map<UUID, CachedPrice> found = new HashMap<>(cache.getAll(ids));

        List<UUID> misses = ids.stream().filter(id -> !found.containsKey(id)).toList();
        if (!misses.isEmpty()) {
            List<CachedPrice> loaded = prices.findQuoteRows(misses).stream()
                    .map(row -> CachedPrice.of((ProductPrice) row[0], (Long) row[1], (ProductStatus) row[2]))
                    .toList();
            loaded.forEach(entry -> found.put(entry.productId(), entry));
            cache.putAllIfNewer(loaded);
        }

        List<QuoteItem> items = new ArrayList<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            CachedPrice entry = found.get(id);
            if (entry == null || entry.archived()) {
                missing.add(id);
            } else {
                items.add(new QuoteItem(entry.productId(), entry.vendorId(), entry.mrp(), entry.sellingPrice(),
                        entry.currency(), entry.priceVersion()));
            }
        }
        return new QuoteResponse(items, missing);
    }
}
