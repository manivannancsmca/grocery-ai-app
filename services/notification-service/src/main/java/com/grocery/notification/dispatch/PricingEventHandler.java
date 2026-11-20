package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.security.Roles;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.entity.StoreType;
import com.grocery.notification.event.EventPayloads.PriceEvent;
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
 * pricing.events:
 * <ul>
 *   <li>PriceChangeRequested → {@code PRICE_APPROVAL_REQUESTED} to SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN (one row);</li>
 *   <li>PriceChangeApproved / PriceChangeRejected → vendor owner;</li>
 *   <li>PriceChanged → {@code PRICE_CHANGED} to {@code public}, throttled per product;</li>
 *   <li>PriceChanged and PriceChange* → {@code PRICING_UPDATED} to the vendor's owner; PriceChange* →
 *       {@code APPROVALS_CHANGED} ({@code PRICE_CHANGE}) to the same three admin roles.</li>
 * </ul>
 */
@Component
public class PricingEventHandler implements EventHandler {

    static final String TOPIC = "pricing.events";
    static final String PRICE_CHANGED = "PriceChanged";
    static final String REQUESTED = "PriceChangeRequested";
    static final String APPROVED = "PriceChangeApproved";
    static final String REJECTED = "PriceChangeRejected";
    static final String CANCELLED = "PriceChangeCancelled";
    private static final Set<String> TYPES = Set.of(PRICE_CHANGED, REQUESTED, APPROVED, REJECTED, CANCELLED);
    static final List<String> PRICE_APPROVERS = List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.FINANCE_ADMIN);
    private static final Logger log = LoggerFactory.getLogger(PricingEventHandler.class);

    private final StoreOwnerRepository owners;
    private final JsonMapper json;

    public PricingEventHandler(StoreOwnerRepository owners, JsonMapper json) {
        this.owners = owners;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && TYPES.contains(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        PriceEvent price = event.payloadAs(json, PriceEvent.class);
        UUID productId = Payloads.require(event, price.productId(), "productId");
        UUID vendorId = Payloads.require(event, price.vendorId(), "vendorId");
        if (!plan.fresh()) {
            return;   // pricing events update no projection
        }
        Optional<StoreOwner> owner = owners.find(StoreType.VENDOR, vendorId);
        if (owner.isEmpty()) {
            log.warn("No owner known for vendor {} ({} {}): the vendor is not notified", vendorId,
                    event.eventType(), event.eventId());
        }
        List<String> vendor = owner.map(o -> List.of(Audiences.user(o.ownerUserId()))).orElse(List.of());
        List<String> approvers = Audiences.roles(PRICE_APPROVERS);
        switch (event.eventType()) {
            case PRICE_CHANGED -> plan.publicSignal(SignalType.PRICE_CHANGED,
                    data("productId", productId, "vendorId", vendorId), SignalType.PRICE_CHANGED + ":" + productId);
            case REQUESTED -> plan.notify(NotificationTemplates.priceApprovalRequested(price.requestId(), productId,
                    vendorId, price.productName(), price.changePercent(), price.oldSellingPrice(),
                    price.newSellingPrice()), approvers);
            case APPROVED -> plan.notify(NotificationTemplates.priceChangeApproved(price.requestId(), productId),
                    vendor);
            case REJECTED -> plan.notify(NotificationTemplates.priceChangeRejected(price.requestId(), productId,
                    price.reason()), vendor);
            default -> {
                // PriceChangeCancelled: signals only
            }
        }
        if (!PRICE_CHANGED.equals(event.eventType())) {
            plan.signal(SignalType.APPROVALS_CHANGED, data("kind", SignalType.KIND_PRICE_CHANGE), approvers);
        }
        plan.signal(SignalType.PRICING_UPDATED, data("productId", productId, "requestId", price.requestId()), vendor);
    }
}
