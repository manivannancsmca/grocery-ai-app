package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import com.grocery.notification.event.EventPayloads.StockEvent;
import com.grocery.notification.repository.StoreOwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.grocery.notification.dispatch.NotificationTemplates.data;

/**
 * inventory.events (the Phase 4 stock events; the order saga's reservation events are ignored):
 * <ul>
 *   <li>LowStockDetected → vendor owner {@code LOW_STOCK};</li>
 *   <li>ProductAvailabilityChanged → {@code STOCK_CHANGED} to {@code public} (in ↔ out of stock transitions only:
 *       that is when inventory-service publishes it), throttled per product;</li>
 *   <li>StockAdjusted / LowStockDetected / ProductAvailabilityChanged → {@code INVENTORY_UPDATED} to the vendor's
 *       owner.</li>
 * </ul>
 */
@Component
public class StockEventHandler implements EventHandler {

    static final String TOPIC = "inventory.events";
    static final String LOW_STOCK_DETECTED = "LowStockDetected";
    static final String PRODUCT_AVAILABILITY_CHANGED = "ProductAvailabilityChanged";
    private static final Set<String> TYPES = Set.of("StockAdjusted", LOW_STOCK_DETECTED, PRODUCT_AVAILABILITY_CHANGED);
    private static final Logger log = LoggerFactory.getLogger(StockEventHandler.class);

    private final StoreOwnerRepository owners;
    private final JsonMapper json;

    public StockEventHandler(StoreOwnerRepository owners, JsonMapper json) {
        this.owners = owners;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && TYPES.contains(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        StockEvent stock = event.payloadAs(json, StockEvent.class);
        UUID productId = Payloads.require(event, stock.productId(), "productId");
        UUID vendorId = Payloads.require(event, stock.vendorId(), "vendorId");
        if (!plan.fresh()) {
            return;   // stock events update no projection
        }
        Optional<StoreOwner> owner = owners.find(StoreType.VENDOR, vendorId);
        if (owner.isEmpty()) {
            log.warn("No owner known for vendor {} ({} {}): the vendor is not notified", vendorId,
                    event.eventType(), event.eventId());
        }
        List<String> vendor = owner.map(o -> List.of(Audiences.user(o.ownerUserId()))).orElse(List.of());
        switch (event.eventType()) {
            case LOW_STOCK_DETECTED -> plan.notify(NotificationTemplates.lowStock(productId, stock.productName(),
                    stock.available(), stock.threshold()), vendor);
            case PRODUCT_AVAILABILITY_CHANGED -> plan.publicSignal(SignalType.STOCK_CHANGED,
                    data("productId", productId, "vendorId", vendorId,
                            "inStock", Payloads.require(event, stock.inStock(), "inStock")),
                    SignalType.STOCK_CHANGED + ":" + productId);
            default -> {
                // StockAdjusted: the vendor's inventory signal only
            }
        }
        plan.signal(SignalType.INVENTORY_UPDATED, data("productId", productId), vendor);
    }
}
