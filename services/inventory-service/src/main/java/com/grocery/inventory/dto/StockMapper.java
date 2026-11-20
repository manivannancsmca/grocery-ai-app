package com.grocery.inventory.dto;

import com.grocery.inventory.dto.InventoryDtos.StockMovementView;
import com.grocery.inventory.dto.InventoryDtos.StockView;
import com.grocery.inventory.entity.ProductRef;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.entity.StockMovement;

/** Entity → API view mapping (plain Java). */
public final class StockMapper {

    private StockMapper() {
    }

    public static StockView toView(StockItem stock, ProductRef product) {
        return new StockView(stock.getProductId(), product.getName(), product.getUnitLabel(), product.getSku(),
                product.getStatus().name(), stock.getOnHand(), stock.getReserved(), stock.available(),
                stock.getLowStockThreshold(), stock.lowStock(), stock.inStock(), stock.getUpdatedAt());
    }

    public static StockMovementView toView(StockMovement movement) {
        return new StockMovementView(movement.getId(), movement.getType().name(), movement.getQuantityDelta(),
                movement.getOnHandAfter(), movement.getReservedAfter(),
                movement.getReason() == null ? null : movement.getReason().name(), movement.getNote(),
                movement.getReference(), movement.getActorUserId(), movement.getCreatedAt());
    }
}
