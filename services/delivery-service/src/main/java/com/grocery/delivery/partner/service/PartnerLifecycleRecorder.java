package com.grocery.delivery.partner.service;

import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.entity.PartnerStatus;
import com.grocery.delivery.partner.entity.PartnerStatusChange;
import com.grocery.delivery.partner.event.PartnerEvents;
import com.grocery.delivery.partner.event.PartnerEvents.PartnerLifecycleEvent;
import com.grocery.delivery.partner.repository.PartnerStatusChangeRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * After every status change: one history row + one outbox event, in the caller's transaction.
 * Keeps the "audit and publish" step identical for every transition.
 */
@Component
public class PartnerLifecycleRecorder {

    private final PartnerStatusChangeRepository history;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public PartnerLifecycleRecorder(PartnerStatusChangeRepository history, OutboxPublisher outbox, Clock clock) {
        this.history = history;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void record(DeliveryPartner partner, PartnerStatus from, String eventType, String reason, UUID actorUserId) {
        history.save(new PartnerStatusChange(Ids.newId(), partner.getId(), from, partner.getStatus(), reason,
                actorUserId, clock.instant()));
        outbox.publish(OutboxEvent.of(PartnerEvents.TOPIC, PartnerEvents.AGGREGATE, partner.getId().toString(),
                eventType, new PartnerLifecycleEvent(partner.getId(), partner.getFullName(), reason, actorUserId)));
    }
}
