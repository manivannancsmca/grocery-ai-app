package com.grocery.vendor.service;

import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.vendor.entity.Vendor;
import com.grocery.vendor.entity.VendorStatus;
import com.grocery.vendor.entity.VendorStatusChange;
import com.grocery.vendor.event.VendorEvents;
import com.grocery.vendor.event.VendorEvents.VendorLifecycleEvent;
import com.grocery.vendor.repository.VendorStatusChangeRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * After every status change: one history row + one outbox event, in the caller's transaction.
 * Keeps the "audit and publish" step identical for every transition.
 */
@Component
public class VendorLifecycleRecorder {

    private final VendorStatusChangeRepository history;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public VendorLifecycleRecorder(VendorStatusChangeRepository history, OutboxPublisher outbox, Clock clock) {
        this.history = history;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void record(Vendor vendor, VendorStatus from, String eventType, String reason, UUID actorUserId) {
        history.save(new VendorStatusChange(Ids.newId(), vendor.getId(), from, vendor.getStatus(), reason,
                actorUserId, clock.instant()));
        outbox.publish(OutboxEvent.of(VendorEvents.TOPIC, VendorEvents.AGGREGATE, vendor.getId().toString(), eventType,
                new VendorLifecycleEvent(vendor.getId(), vendor.getOwnerUserId(), vendor.getBusinessName(),
                        reason, actorUserId)));
    }
}
