package com.grocery.reporting.facts;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.reporting.event.EventPayloads.DeliveryEvent;
import com.grocery.reporting.event.EventPayloads.GoodsReceived;
import com.grocery.reporting.event.EventPayloads.OrderEvent;
import com.grocery.reporting.event.EventPayloads.OrderItem;
import com.grocery.reporting.event.EventPayloads.PaymentCaptured;
import com.grocery.reporting.event.EventPayloads.RatingSummaryUpdated;
import com.grocery.reporting.event.EventPayloads.ReceiptLine;
import com.grocery.reporting.event.EventPayloads.RefundCompleted;
import com.grocery.reporting.event.EventPayloads.ReturnApproved;
import com.grocery.reporting.event.EventPayloads.ReturnLine;
import com.grocery.reporting.event.EventPayloads.ReviewSubmitted;
import com.grocery.reporting.facts.FactsRepository.Line;
import com.grocery.reporting.facts.FactsRepository.RatingRow;
import com.grocery.reporting.facts.FactsRepository.ReceiptLineRow;
import com.grocery.reporting.facts.FactsRepository.ReceiptRow;
import com.grocery.reporting.facts.FactsRepository.ReturnLineRow;
import com.grocery.reporting.facts.FactsRepository.ReturnRow;
import com.grocery.reporting.facts.FactsRepository.ReviewRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns one event into facts, in ONE transaction: {@code processed_event} (consumer {@value #CONSUMER}; a duplicate
 * stops here) → the event's upserts. Events this service does not use (every other type on the five topics) are
 * ignored without touching the database. The payload is read before the transaction: an unreadable payload or one
 * without its ids is an {@link InvalidEventException} (grocery-common sends it to {@code <topic>.DLT} at once).
 *
 * <p>Times: the event's own timestamp field; RefundCompleted and OrderRejected carry none, so their time is the
 * {@code occurredAt} header, or the processing time when the header is missing.
 */
@Service
public class FactsService {

    public static final String CONSUMER = "reporting-service.facts";
    private static final Logger log = LoggerFactory.getLogger(FactsService.class);

    /** Every event type this service turns into facts (contract §7). */
    public static final Set<String> CONSUMED = Set.of(
            "OrderCreated", "OrderConfirmed", "OrderCancelled", "OrderRejected", "OrderDelivered",
            "OrderDeliveryFailed", "ReturnApproved",
            "PaymentCaptured", "RefundCompleted",
            "DeliveryPartnerAssigned", "DeliveryPickedUp", "DeliveryCompleted",
            "ReviewSubmitted", "RatingSummaryUpdated",
            "GoodsReceived");

    /** What one event did (for logs and tests). */
    public enum Outcome {
        /** Facts written. */
        APPLIED,
        /** Already processed (redelivery). */
        DUPLICATE,
        /** Not an event this service uses. */
        IGNORED
    }

    private final FactsRepository facts;
    private final ProcessedEventGuard guard;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;

    public FactsService(FactsRepository facts, ProcessedEventGuard guard, PlatformTransactionManager transactionManager,
                        JsonMapper json, Clock clock) {
        this.facts = facts;
        this.guard = guard;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param occurredAt the event's {@code occurredAt} header ({@code null} if missing or unreadable)
     */
    public Outcome apply(EventEnvelope event, Instant occurredAt) {
        if (!CONSUMED.contains(event.eventType())) {
            return Outcome.IGNORED;
        }
        Runnable write = plan(event, occurredAt == null ? clock.instant() : occurredAt);
        Outcome outcome = transactions.execute(status -> {
            if (!guard.markProcessed(event.eventId(), CONSUMER)) {
                return Outcome.DUPLICATE;
            }
            write.run();
            return Outcome.APPLIED;
        });
        log.debug("{} {} ({}) on {}: {}", event.eventType(), event.eventId(), event.key(), event.topic(), outcome);
        return outcome;
    }

    /** Reads and checks the payload (outside the transaction) and returns the writes to run inside it. */
    private Runnable plan(EventEnvelope event, Instant eventTime) {
        Instant now = clock.instant();
        return switch (event.eventType()) {
            case "OrderCreated" -> {
                OrderEvent e = order(event);
                Instant createdAt = e.createdAt() == null ? eventTime : e.createdAt();
                yield () -> {
                    facts.upsertOrder(header(e)
                            .storeName(e.storeName())
                            .amounts(e.currency(), e.subtotal(), e.deliveryFee(), e.total())
                            .coupon(e.couponCode(), e.discount(), e.deliveryFeeDiscount(), e.couponName(),
                                    e.couponFundedBy())
                            .created(createdAt), now);
                    lines(e, createdAt);
                    store(e, e.storeName(), createdAt);
                };
            }
            case "OrderConfirmed" -> {
                OrderEvent e = order(event);
                Instant confirmedAt = e.confirmedAt() == null ? eventTime : e.confirmedAt();
                String storeName = e.storeLocation() == null ? null : e.storeLocation().name();
                yield () -> {
                    facts.upsertOrder(header(e)
                            .storeName(storeName)
                            .amounts(null, e.subtotal(), null, e.total())
                            .coupon(e.couponCode(), e.discount(), e.deliveryFeeDiscount(), e.couponName(),
                                    e.couponFundedBy())
                            .confirmed(confirmedAt), now);
                    lines(e, confirmedAt);
                    store(e, storeName, confirmedAt);
                };
            }
            case "OrderCancelled" -> {
                OrderEvent e = order(event);
                Instant cancelledAt = e.cancelledAt() == null ? eventTime : e.cancelledAt();
                yield () -> facts.upsertOrder(header(e)
                        .cancelled(cancelledAt, e.reason(), e.cancelledBy(), e.previousStatus()), now);
            }
            case "OrderRejected" -> {
                OrderEvent e = order(event);
                yield () -> facts.upsertOrder(header(e).rejected(eventTime, e.reason()), now);
            }
            case "OrderDelivered" -> {
                OrderEvent e = order(event);
                Instant deliveredAt = e.deliveredAt() == null ? eventTime : e.deliveredAt();
                yield () -> facts.upsertOrder(header(e).delivered(deliveredAt, e.partnerId()), now);
            }
            case "OrderDeliveryFailed" -> {
                OrderEvent e = order(event);
                Instant failedAt = e.failedAt() == null ? eventTime : e.failedAt();
                yield () -> facts.upsertOrder(header(e).deliveryFailed(failedAt, e.reason(), e.partnerId()), now);
            }
            case "ReturnApproved" -> returnApproved(event, eventTime);
            case "PaymentCaptured" -> {
                PaymentCaptured e = event.payloadAs(json, PaymentCaptured.class);
                requireIds(event, e.orderId());
                Instant capturedAt = e.capturedAt() == null ? eventTime : e.capturedAt();
                yield () -> facts.upsertOrder(new OrderUpdate(e.orderId())
                        .captured(e.amount(), capturedAt, e.late()), now);
            }
            case "RefundCompleted" -> {
                RefundCompleted e = event.payloadAs(json, RefundCompleted.class);
                requireIds(event, e.orderId(), e.refundId());
                require(event, e.amount(), "amount");
                yield () -> facts.insertRefund(e.refundId(), e.orderId(), e.paymentId(), e.returnId(), e.amount(),
                        e.reason(), eventTime);
            }
            case "DeliveryPartnerAssigned", "DeliveryPickedUp", "DeliveryCompleted" -> delivery(event, eventTime, now);
            case "ReviewSubmitted" -> {
                ReviewSubmitted e = event.payloadAs(json, ReviewSubmitted.class);
                requireIds(event, e.reviewId(), e.targetId());
                require(event, e.targetType(), "targetType");
                require(event, e.rating(), "rating");
                ReviewRow row = new ReviewRow(e.reviewId(), e.targetType(), e.targetId(), e.storeId(), e.customerId(),
                        e.orderId(), e.rating(), e.status() == null ? "PUBLISHED" : e.status(), e.flagReason(),
                        e.submittedAt() == null ? eventTime : e.submittedAt());
                yield () -> facts.upsertReview(row);
            }
            case "RatingSummaryUpdated" -> {
                RatingSummaryUpdated e = event.payloadAs(json, RatingSummaryUpdated.class);
                requireIds(event, e.targetId());
                require(event, e.targetType(), "targetType");
                require(event, e.summaryVersion(), "summaryVersion");
                List<Integer> distribution = new ArrayList<>(List.of(0, 0, 0, 0, 0));
                if (e.ratingDistribution() != null) {
                    for (int i = 0; i < Math.min(5, e.ratingDistribution().size()); i++) {
                        Integer value = e.ratingDistribution().get(i);
                        distribution.set(i, value == null ? 0 : value);
                    }
                }
                RatingRow row = new RatingRow(e.targetType(), e.targetId(),
                        e.averageRating() == null ? BigDecimal.ZERO : e.averageRating().setScale(2, RoundingMode.HALF_UP),
                        e.ratingCount() == null ? 0 : e.ratingCount(), distribution, e.summaryVersion(),
                        e.updatedAt() == null ? eventTime : e.updatedAt());
                yield () -> facts.upsertRating(row);
            }
            case "GoodsReceived" -> goodsReceived(event, eventTime);
            default -> throw new IllegalStateException("No fact writer for " + event.eventType());
        };
    }

    // ---------------------------------------------------------------- orders

    private OrderEvent order(EventEnvelope event) {
        OrderEvent e = event.payloadAs(json, OrderEvent.class);
        requireIds(event, e.orderId());
        return e;
    }

    private static OrderUpdate header(OrderEvent e) {
        return new OrderUpdate(e.orderId()).header(e.orderNumber(), e.orderType(), e.customerId(), e.storeId());
    }

    /** The lines (and the items' names); an event without lines leaves the stored ones alone. */
    private void lines(OrderEvent e, Instant at) {
        if (e.items() == null || e.items().isEmpty()) {
            return;
        }
        List<Line> lines = new ArrayList<>(e.items().size());
        for (OrderItem item : e.items()) {
            if (item == null || item.itemId() == null) {
                continue;
            }
            int quantity = item.quantity() == null ? 0 : item.quantity();
            BigDecimal lineTotal = item.lineTotal() != null ? item.lineTotal()
                    : item.unitPrice() == null ? BigDecimal.ZERO : item.unitPrice().multiply(BigDecimal.valueOf(quantity));
            lines.add(new Line(item.itemId(), item.type(), item.name(), quantity, item.unitPrice(),
                    lineTotal.setScale(2, RoundingMode.HALF_UP)));
            if (item.name() != null && at != null) {
                facts.upsertItem(item.itemId(), item.type(), e.storeId(), item.name(), at);
            }
        }
        facts.replaceLines(e.orderId(), lines);
    }

    private void store(OrderEvent e, String name, Instant at) {
        if (e.storeId() != null && name != null && !name.isBlank() && at != null) {
            facts.upsertStore(e.storeId(), e.orderType(), name.strip(), at);
        }
    }

    private Runnable returnApproved(EventEnvelope event, Instant eventTime) {
        ReturnApproved e = event.payloadAs(json, ReturnApproved.class);
        requireIds(event, e.returnId(), e.orderId());
        require(event, e.refundAmount(), "refundAmount");
        List<ReturnLineRow> lines = new ArrayList<>();
        if (e.items() != null) {
            for (ReturnLine line : e.items()) {
                if (line != null && line.itemId() != null) {
                    lines.add(new ReturnLineRow(line.itemId(), line.name(),
                            line.quantity() == null ? 0 : line.quantity(),
                            line.refundAmount() == null ? BigDecimal.ZERO : line.refundAmount()));
                }
            }
        }
        ReturnRow row = new ReturnRow(e.returnId(), e.returnNumber(), e.orderId(), e.orderType(), e.storeId(),
                e.customerId(), e.refundAmount(), e.restock(), e.decidedByRole(),
                e.decidedAt() == null ? eventTime : e.decidedAt());
        return () -> facts.insertReturn(row, lines);
    }

    // ---------------------------------------------------------------- deliveries

    private Runnable delivery(EventEnvelope event, Instant eventTime, Instant now) {
        DeliveryEvent e = event.payloadAs(json, DeliveryEvent.class);
        requireIds(event, e.deliveryId(), e.orderId());
        return switch (event.eventType()) {
            case "DeliveryPartnerAssigned" -> {
                Instant at = e.assignedAt() == null ? eventTime : e.assignedAt();
                yield () -> {
                    facts.upsertDelivery(e.deliveryId(), e.orderId(), e.orderNumber(), e.partnerId(), at, null, null,
                            now);
                    if (e.partnerId() != null && e.partnerName() != null && !e.partnerName().isBlank()) {
                        facts.upsertPartner(e.partnerId(), e.partnerName().strip(), at);
                    }
                };
            }
            case "DeliveryPickedUp" -> {
                Instant at = e.pickedUpAt() == null ? eventTime : e.pickedUpAt();
                yield () -> facts.upsertDelivery(e.deliveryId(), e.orderId(), e.orderNumber(), e.partnerId(), null,
                        at, null, now);
            }
            default -> {
                Instant at = e.deliveredAt() == null ? eventTime : e.deliveredAt();
                yield () -> facts.upsertDelivery(e.deliveryId(), e.orderId(), e.orderNumber(), e.partnerId(), null,
                        null, at, now);
            }
        };
    }

    // ---------------------------------------------------------------- purchasing

    private Runnable goodsReceived(EventEnvelope event, Instant eventTime) {
        GoodsReceived e = event.payloadAs(json, GoodsReceived.class);
        requireIds(event, e.receiptId(), e.vendorId(), e.purchaseOrderId());
        List<ReceiptLineRow> lines = new ArrayList<>();
        int units = 0;
        BigDecimal cost = BigDecimal.ZERO;
        if (e.lines() != null) {
            for (ReceiptLine line : e.lines()) {
                if (line == null || line.productId() == null) {
                    continue;
                }
                int quantity = line.quantity() == null ? 0 : line.quantity();
                BigDecimal unitCost = line.unitCost() == null ? BigDecimal.ZERO : line.unitCost();
                lines.add(new ReceiptLineRow(line.productId(), quantity, unitCost));
                units += quantity;
                cost = cost.add(unitCost.multiply(BigDecimal.valueOf(quantity)));
            }
        }
        ReceiptRow row = new ReceiptRow(e.receiptId(), e.vendorId(), e.purchaseOrderId(), e.purchaseOrderNumber(),
                e.supplierId(), units, cost.setScale(2, RoundingMode.HALF_UP),
                e.receivedAt() == null ? eventTime : e.receivedAt());
        return () -> facts.insertReceipt(row, lines);
    }

    // ---------------------------------------------------------------- checks

    private static void requireIds(EventEnvelope event, Object... ids) {
        for (Object id : ids) {
            if (id == null) {
                throw new InvalidEventException(event.eventType() + " " + event.eventId() + " on " + event.topic()
                        + " lacks a required id");
            }
        }
    }

    private static <T> T require(EventEnvelope event, T value, String field) {
        if (value == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " on " + event.topic()
                    + " has no " + field);
        }
        return value;
    }
}
