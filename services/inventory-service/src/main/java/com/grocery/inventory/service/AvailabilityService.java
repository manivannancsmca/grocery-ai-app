package com.grocery.inventory.service;

import com.grocery.inventory.dto.InventoryDtos.AvailabilityItem;
import com.grocery.inventory.dto.InventoryDtos.AvailabilityResponse;
import com.grocery.inventory.entity.ProductStatus;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.repository.StockItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Live availability for a batch of products (internal, Phase 5 contract §4): cart-service checks stock every time
 * a cart is shown. One query for the whole batch in a read-only transaction and <b>no locks</b> — a plain InnoDB
 * consistent read never waits for a stock change in flight; it sees the last committed stock. Nothing is reserved
 * here (that happens when an order is created).
 */
@Service
@Transactional(readOnly = true)
public class AvailabilityService {

    private final StockItemRepository stockItems;

    public AvailabilityService(StockItemRepository stockItems) {
        this.stockItems = stockItems;
    }

    /** Unknown and archived products are {@code missing}; duplicates are answered once, in request order. */
    public AvailabilityResponse availability(List<UUID> productIds) {
        List<UUID> ids = productIds.stream().distinct().toList();
        Map<UUID, StockItem> found = stockItems.findAvailable(ids, ProductStatus.ARCHIVED).stream()
                .collect(Collectors.toMap(StockItem::getProductId, Function.identity()));

        List<AvailabilityItem> items = new ArrayList<>(found.size());
        List<UUID> missing = new ArrayList<>();
        for (UUID id : ids) {
            StockItem stock = found.get(id);
            if (stock == null) {
                missing.add(id);
            } else {
                items.add(new AvailabilityItem(id, stock.getVendorId(), stock.available(), stock.inStock()));
            }
        }
        return new AvailabilityResponse(items, missing);
    }
}
