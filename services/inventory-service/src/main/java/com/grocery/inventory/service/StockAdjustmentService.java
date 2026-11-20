package com.grocery.inventory.service;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.inventory.dto.InventoryDtos.Adjustment;
import com.grocery.inventory.dto.InventoryDtos.AdjustmentRequest;
import com.grocery.inventory.dto.InventoryDtos.BulkAdjustmentItem;
import com.grocery.inventory.dto.InventoryDtos.BulkAdjustmentRequest;
import com.grocery.inventory.dto.InventoryDtos.StockView;
import com.grocery.inventory.dto.InventoryDtos.ThresholdRequest;
import com.grocery.inventory.dto.StockMapper;
import com.grocery.inventory.entity.AdjustmentReason;
import com.grocery.inventory.entity.MovementType;
import com.grocery.inventory.entity.ProductRef;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.entity.StockMovement;
import com.grocery.inventory.repository.ProductRefRepository;
import com.grocery.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vendor stock adjustments and thresholds (contract §2). Ownership comes from the token's {@code vendor_id}
 * claim; another vendor's product is reported as not found. Every command follows the locking rule documented
 * on {@link StockLocks}: lock the stock row(s) first, then read the product copy, apply, and write the movement
 * and the events — in one transaction.
 */
@Service
public class StockAdjustmentService {

    static final int MAX_DELTA = 100_000;

    /** A validated adjustment. */
    record Command(MovementType type, int quantity, AdjustmentReason reason, String note) {

        /** Bean validation has checked the patterns and 0–1,000,000; INCREASE/DECREASE need 1–100,000. */
        static Command of(Adjustment adjustment, String fieldPrefix) {
            MovementType type = MovementType.valueOf(adjustment.type());
            int quantity = adjustment.quantity();
            if (type != MovementType.SET && (quantity < 1 || quantity > MAX_DELTA)) {
                throw InventoryException.validation(fieldPrefix + "quantity",
                        "must be between 1 and " + MAX_DELTA + " for " + type);
            }
            String note = adjustment.note() == null || adjustment.note().isBlank() ? null : adjustment.note().trim();
            return new Command(type, quantity, AdjustmentReason.valueOf(adjustment.reason()), note);
        }
    }

    private final StockLocks locks;
    private final StockItemRepository stockItems;
    private final ProductRefRepository productRefs;
    private final StockEventPublisher events;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StockAdjustmentService(StockLocks locks, StockItemRepository stockItems, ProductRefRepository productRefs,
                                  StockEventPublisher events, EntityManager entityManager,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.locks = locks;
        this.stockItems = stockItems;
        this.productRefs = productRefs;
        this.events = events;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public StockView adjust(CurrentUser user, UUID productId, AdjustmentRequest request) {
        UUID vendorId = user.requireVendorId();
        Command command = Command.of(request, "");
        return transaction.execute(status -> {
            StockItem stock = owned(locks.lock(productId), vendorId);          // 1. lock first
            ProductRef product = changeable(productRefs.findById(productId).orElse(null));
            apply(stock, product, command, user.userId());                     // 2.+3. change, ledger, events
            stockItems.flush();
            return StockMapper.toView(stock, product);
        });
    }

    /**
     * All or nothing: every product is locked (ascending id order) before the first change; one failing item
     * rolls the whole transaction back, and its error names the item ({@code productId} property).
     */
    public List<StockView> bulkAdjust(CurrentUser user, BulkAdjustmentRequest request) {
        UUID vendorId = user.requireVendorId();
        List<BulkAdjustmentItem> items = request.items();
        List<Command> commands = new ArrayList<>(items.size());
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            BulkAdjustmentItem item = items.get(i);
            Integer previous = firstIndex.putIfAbsent(item.productId(), i);
            if (previous != null) {
                throw InventoryException.validation("items[" + i + "].productId",
                        "the product is already adjusted by items[" + previous + "]");
            }
            commands.add(Command.of(item, "items[" + i + "]."));
        }
        return transaction.execute(status -> {
            Map<UUID, StockItem> locked = locks.lockAll(firstIndex.keySet());   // 1. lock all first
            Map<UUID, ProductRef> products = productRefs.findAllById(locked.keySet()).stream()
                    .collect(Collectors.toMap(ProductRef::getProductId, Function.identity()));
            List<StockView> views = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                UUID productId = items.get(i).productId();
                try {
                    StockItem stock = owned(locked.get(productId), vendorId);
                    ProductRef product = changeable(products.get(productId));
                    apply(stock, product, commands.get(i), user.userId());
                    views.add(StockMapper.toView(stock, product));
                } catch (InventoryException e) {
                    throw e.forProduct(productId);
                }
            }
            stockItems.flush();
            return views;
        });
    }

    /** Not a stock change: no movement, no event, the stock version stays. Still serialised by the row lock. */
    public StockView changeThreshold(CurrentUser user, UUID productId, ThresholdRequest request) {
        UUID vendorId = user.requireVendorId();
        return transaction.execute(status -> {
            StockItem stock = owned(locks.lock(productId), vendorId);
            ProductRef product = changeable(productRefs.findById(productId).orElse(null));
            stock.changeThreshold(request.lowStockThreshold(), clock.instant());
            stockItems.flush();
            return StockMapper.toView(stock, product);
        });
    }

    private void apply(StockItem stock, ProductRef product, Command command, UUID actorUserId) {
        Instant now = clock.instant();
        int availableBefore = stock.available();
        int delta = stock.adjust(command.type(), command.quantity(), now);
        StockMovement movement = StockMovement.record(Ids.newId(), stock, command.type(), delta, command.reason(),
                command.note(), null, actorUserId, now);
        entityManager.persist(movement);
        events.adjusted(stock, movement);
        events.transitions(stock, availableBefore, product::getName);
    }

    private static StockItem owned(StockItem stock, UUID vendorId) {
        if (stock == null || !stock.getVendorId().equals(vendorId)) {
            throw InventoryException.productNotFound();
        }
        return stock;
    }

    private static ProductRef changeable(ProductRef product) {
        if (product == null) {
            throw InventoryException.productNotFound();
        }
        if (product.isArchived()) {
            throw InventoryException.productArchived();
        }
        return product;
    }
}
