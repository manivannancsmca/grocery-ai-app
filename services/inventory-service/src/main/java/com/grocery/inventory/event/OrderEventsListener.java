package com.grocery.inventory.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.inventory.service.OrderStockService;
import com.grocery.inventory.service.ReservationService.ReservationLine;
import com.grocery.inventory.service.RestockService;
import com.grocery.inventory.service.RestockService.RestockLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * order.events → stock reservations (docs/api/phase-6-orders-and-payments.md §6 "Inventory",
 * phase-9-delivery.md §7). Grocery orders only: {@code OrderCreated} (reserve), {@code OrderConfirmed} (hold until
 * pickup), {@code OrderCancelled} (release), {@code OrderOutForDelivery} (commit at pickup), and (Phase 12 §3)
 * {@code ReturnApproved} with {@code restock} true (the returned units back into stock, {@link RestockService}).
 * Restaurant orders, returns without restock and every other event type are ignored.
 */
@Component
public class OrderEventsListener {

    static final String TOPIC = "order.events";
    static final String ORDER_CREATED = "OrderCreated";
    static final String ORDER_CONFIRMED = "OrderConfirmed";
    static final String ORDER_CANCELLED = "OrderCancelled";
    static final String ORDER_OUT_FOR_DELIVERY = "OrderOutForDelivery";
    static final String RETURN_APPROVED = "ReturnApproved";
    static final String GROCERY = "GROCERY";

    /** Our own view of the OrderCreated payload: only the fields this service needs (tolerant reader). */
    record OrderCreated(UUID orderId, String orderType, List<Item> items) {
    }

    /** {@code itemId} = the product id for grocery orders. */
    record Item(UUID itemId, Integer quantity) {
    }

    /** OrderConfirmed / OrderCancelled / OrderOutForDelivery: only which order. */
    record OrderRef(UUID orderId, String orderType) {
    }

    /** Phase 12: our own view of ReturnApproved ({@code itemId} = the product id for grocery orders). */
    record ReturnApproved(UUID returnId, String returnNumber, UUID orderId, String orderNumber, String orderType,
                          UUID storeId, List<Item> items, Boolean restock) {
    }

    private final OrderStockService orderStock;
    private final RestockService restock;
    private final JsonMapper jsonMapper;

    public OrderEventsListener(OrderStockService orderStock, RestockService restock, JsonMapper jsonMapper) {
        this.orderStock = orderStock;
        this.restock = restock;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "inventory-order-events", groupId = OrderStockService.CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        switch (event.eventType()) {
            case ORDER_CREATED -> created(event);
            case ORDER_CONFIRMED -> {
                OrderRef order = ref(event);
                if (isGrocery(order.orderType())) {
                    orderStock.orderConfirmed(event.eventId(), order.orderId());
                }
            }
            case ORDER_CANCELLED -> {
                OrderRef order = ref(event);
                if (isGrocery(order.orderType())) {
                    orderStock.orderCancelled(event.eventId(), order.orderId());
                }
            }
            case ORDER_OUT_FOR_DELIVERY -> {
                OrderRef order = ref(event);
                if (isGrocery(order.orderType())) {
                    orderStock.orderOutForDelivery(event.eventId(), order.orderId());
                }
            }
            case RETURN_APPROVED -> returnApproved(event);
            default -> {
                // OrderAwaitingPayment, OrderRejected, …: nothing to do here
            }
        }
    }

    private void created(EventEnvelope event) {
        OrderCreated order = event.payloadAs(jsonMapper, OrderCreated.class);
        if (order.orderId() == null || order.orderType() == null) {
            throw new InvalidEventException("OrderCreated " + event.eventId() + " lacks orderId or orderType");
        }
        if (!GROCERY.equals(order.orderType())) {
            return;                             // restaurant orders hold no stock
        }
        if (order.items() == null || order.items().isEmpty()) {
            throw new InvalidEventException("OrderCreated " + event.eventId() + " has no items");
        }
        List<ReservationLine> lines = order.items().stream()
                .map(item -> {
                    if (item == null || item.itemId() == null || item.quantity() == null || item.quantity() <= 0) {
                        throw new InvalidEventException("OrderCreated " + event.eventId()
                                + " has an item without itemId or a positive quantity");
                    }
                    return new ReservationLine(item.itemId(), item.quantity());
                })
                .toList();
        orderStock.orderCreated(event.eventId(), order.orderId(), lines);
    }

    /**
     * Only a GROCERY return with {@code restock} true changes stock; anything else is ignored (not marked processed),
     * as restaurant orders are. A return of a grocery order always names its store and lines.
     */
    private void returnApproved(EventEnvelope event) {
        ReturnApproved approved = event.payloadAs(jsonMapper, ReturnApproved.class);
        if (approved.orderId() == null || approved.orderType() == null || approved.restock() == null) {
            throw new InvalidEventException("ReturnApproved " + event.eventId()
                    + " lacks orderId, orderType or restock");
        }
        if (!GROCERY.equals(approved.orderType()) || !approved.restock()) {
            return;                             // nothing comes back into sellable stock
        }
        if (approved.storeId() == null || approved.items() == null || approved.items().isEmpty()) {
            throw new InvalidEventException("ReturnApproved " + event.eventId() + " lacks storeId or items");
        }
        List<RestockLine> lines = approved.items().stream()
                .map(item -> {
                    if (item == null || item.itemId() == null || item.quantity() == null || item.quantity() <= 0) {
                        throw new InvalidEventException("ReturnApproved " + event.eventId()
                                + " has an item without itemId or a positive quantity");
                    }
                    return new RestockLine(item.itemId(), item.quantity());
                })
                .toList();
        restock.returnApproved(event.eventId(), approved.storeId(), approved.orderId(), approved.returnNumber(),
                approved.orderNumber(), lines);
    }

    private OrderRef ref(EventEnvelope event) {
        OrderRef order = event.payloadAs(jsonMapper, OrderRef.class);
        if (order.orderId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks orderId");
        }
        return order;
    }

    /**
     * Missing orderType on a confirm/cancel/pickup: act on the order id (an order without a reservation is a
     * no-op).
     */
    private static boolean isGrocery(String orderType) {
        return orderType == null || GROCERY.equals(orderType);
    }
}
