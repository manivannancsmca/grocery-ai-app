package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.entity.ReviewTarget;
import com.grocery.notification.entity.StoreOwner;
import com.grocery.notification.event.EventPayloads.ReviewEvent;
import com.grocery.notification.repository.ItemNameRepository;
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
 * review.events (Phase 12 §1, schemas 1.5.0, key reviewId); RatingSummaryUpdated is not used (catalog-query's):
 * <ul>
 *   <li>ReviewSubmitted PUBLISHED → {@code NEW_REVIEW} · INFO to the owner of the review's store ({@code storeId},
 *       from the {@code store_owner} projection — never another store's owner) — not for DELIVERY_PARTNER reviews,
 *       which are not the store's business and do not appear on its reviews page;</li>
 *   <li>ReviewSubmitted FLAGGED → {@code REVIEW_FLAGGED} · WARNING to SUPER_ADMIN, OPS_ADMIN, SUPPORT (one row);</li>
 *   <li>ReviewModerated REJECTED / REMOVED → {@code REVIEW_NOT_PUBLISHED} · WARNING to the customer (PUBLISHED notifies
 *       nobody);</li>
 *   <li>ReviewReplied → {@code REVIEW_REPLY} · INFO to the customer;</li>
 *   <li>signals: {@code REVIEW_UPDATED {reviewId, status, event}} → the customer, every event;
 *       {@code ADMIN_REVIEWS_CHANGED {reviewId, status, event}} → all admin roles, every event;
 *       {@code STORE_REVIEWS_CHANGED {reviewId, targetType, targetId, event}} → the store owner when the review appears
 *       on, leaves or changes on the store's page (Submitted PUBLISHED, Moderated PUBLISHED / REMOVED, Replied).</li>
 * </ul>
 * Names come from the projections: a product / dish from {@code item_name} (the order lines), a store from
 * {@code store_owner}; otherwise neutral words ("your product", "a dish", "the store"). An unknown store owner skips
 * only the store's part (logged). No projection is written, so an old event (older than max-event-age) does nothing.
 * Missing {@code reviewId}, {@code customerId}, {@code targetType}, Submitted's {@code status} or Moderated's
 * {@code decision} → {@code InvalidEventException} (DLT).
 */
@Component
public class ReviewEventHandler implements EventHandler {

    static final String TOPIC = "review.events";
    static final String SUBMITTED = "ReviewSubmitted";
    static final String MODERATED = "ReviewModerated";
    static final String REPLIED = "ReviewReplied";
    private static final Set<String> TYPES = Set.of(SUBMITTED, MODERATED, REPLIED);
    private static final Logger log = LoggerFactory.getLogger(ReviewEventHandler.class);

    private final StoreOwnerRepository owners;
    private final ItemNameRepository itemNames;
    private final JsonMapper json;

    public ReviewEventHandler(StoreOwnerRepository owners, ItemNameRepository itemNames, JsonMapper json) {
        this.owners = owners;
        this.itemNames = itemNames;
        this.json = json;
    }

    @Override
    public boolean supports(String topic, String eventType) {
        return TOPIC.equals(topic) && TYPES.contains(eventType);
    }

    @Override
    public void handle(EventEnvelope event, DispatchPlan plan) {
        ReviewEvent review = event.payloadAs(json, ReviewEvent.class);
        UUID reviewId = Payloads.require(event, review.reviewId(), "reviewId");
        UUID customerId = Payloads.require(event, review.customerId(), "customerId");
        String targetType = Payloads.require(event, review.targetType(), "targetType");
        String status = switch (event.eventType()) {
            case SUBMITTED -> Payloads.require(event, review.status(), "status");
            case MODERATED -> Payloads.require(event, review.decision(), "decision");
            default -> null;
        };
        if (!plan.fresh()) {
            return;                                     // no projection to update
        }

        ReviewTarget target = ReviewTarget.of(targetType);
        UUID targetId = review.targetId();
        Optional<StoreOwner> store = store(event, target, review.storeId());
        String targetName = targetName(target, targetId, store);
        List<String> customer = List.of(Audiences.user(customerId));
        boolean storeSees = false;
        switch (event.eventType()) {
            case SUBMITTED -> {
                if ("PUBLISHED".equals(status)) {
                    storeSees = true;
                    store.ifPresent(owner -> plan.notify(NotificationTemplates.newReview(reviewId, target, targetType,
                                    targetId, review.rating(), targetName, Boolean.TRUE.equals(review.edited()),
                                    owner.storeType()),
                            List.of(Audiences.user(owner.ownerUserId()))));
                } else if ("FLAGGED".equals(status)) {
                    plan.notify(NotificationTemplates.reviewFlagged(reviewId, target, targetType, targetId,
                            review.rating(), targetName, review.flagReason()), Audiences.moderators());
                }
            }
            case MODERATED -> {
                if ("REJECTED".equals(status) || "REMOVED".equals(status)) {
                    plan.notify(NotificationTemplates.reviewNotPublished(reviewId, target, targetType, targetId,
                            status, targetName, review.reason()), customer);
                }
                storeSees = "PUBLISHED".equals(status) || "REMOVED".equals(status);
            }
            default -> {
                plan.notify(NotificationTemplates.reviewReply(reviewId, target, targetType, targetId,
                        store.map(StoreOwner::storeName).orElse(null), targetName), customer);
                storeSees = true;
            }
        }

        if (storeSees && store.isPresent()) {
            plan.signal(SignalType.STORE_REVIEWS_CHANGED, data("reviewId", reviewId, "targetType", targetType,
                    "targetId", targetId, "event", event.eventType()), List.of(Audiences.user(store.get().ownerUserId())));
        }
        plan.signal(SignalType.REVIEW_UPDATED, data("reviewId", reviewId, "status", status, "event",
                event.eventType()), customer);
        plan.signal(SignalType.ADMIN_REVIEWS_CHANGED, data("reviewId", reviewId, "status", status, "event",
                event.eventType()), Audiences.allAdmins());
    }

    /** The owner of the review's store, for the store's target types only (never for DELIVERY_PARTNER reviews). */
    private Optional<StoreOwner> store(EventEnvelope event, ReviewTarget target, UUID storeId) {
        if (target == null || target.storeType() == null || storeId == null) {
            return Optional.empty();
        }
        Optional<StoreOwner> owner = owners.find(target.storeType(), storeId);
        if (owner.isEmpty()) {
            log.warn("No owner known for {} {} ({} {}): the store is not notified", target.storeType(), storeId,
                    event.eventType(), event.eventId());
        }
        return owner;
    }

    /** The product / dish name (item_name) or the store name (store_owner); null when unknown. */
    private String targetName(ReviewTarget target, UUID targetId, Optional<StoreOwner> store) {
        if (target == null) {
            return null;
        }
        if (target.item()) {
            return itemNames.find(targetId).orElse(null);
        }
        if (target == ReviewTarget.VENDOR || target == ReviewTarget.RESTAURANT) {
            return store.map(StoreOwner::storeName).orElse(null);
        }
        return null;
    }
}
