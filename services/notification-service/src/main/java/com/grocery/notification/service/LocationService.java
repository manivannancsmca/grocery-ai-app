package com.grocery.notification.service;

import com.grocery.common.event.EventHeaders;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.config.NotificationProperties;
import com.grocery.notification.dispatch.Audiences;
import com.grocery.notification.dispatch.SignalType;
import com.grocery.notification.dto.NotificationDtos.Signal;
import com.grocery.notification.entity.OrderRef;
import com.grocery.notification.event.EventPayloads.DeliveryLocation;
import com.grocery.notification.event.OccurredAt;
import com.grocery.notification.realtime.LocationThrottle;
import com.grocery.notification.realtime.RealtimeMessage;
import com.grocery.notification.realtime.RealtimePublisher;
import com.grocery.notification.repository.OrderRefRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 9 live location (docs/api/phase-9-delivery.md §7): one {@code DeliveryLocationUpdated} from delivery.location
 * (consumer group {@value #CONSUMER}) → signal {@code DELIVERY_LOCATION {orderId, latitude, longitude, heading, at}}
 * to <b>the order's customer only</b>, resolved through the {@code order_ref} projection (never from the event).
 * <ul>
 *   <li>recorded longer ago than {@code grocery.notification.location-max-age} (30 s) → dropped (by
 *       {@code recordedAt}; without it the {@code occurredAt} header, else the record's timestamp);</li>
 *   <li>an order this service does not know, or one that has ended (DELIVERED, DELIVERY_FAILED, CANCELLED, REJECTED)
 *       → dropped;</li>
 *   <li>at most one per order per {@code grocery.notification.location-throttle} (2 s), the others dropped
 *       ({@link LocationThrottle}).</li>
 * </ul>
 * Best effort and stateless: nothing is stored, no {@code processed_event} row (a repeated position is harmless and
 * throttled), the signal is published at once. The event may come from a direct producer, so the outbox headers are
 * optional: a record whose {@code eventType} header names another type is ignored.
 */
@Service
public class LocationService {

    public static final String CONSUMER = "notification-service.location";
    static final String EVENT_TYPE = "DeliveryLocationUpdated";
    /** Order statuses after which the customer's map no longer moves. */
    private static final Set<String> ENDED = Set.of("DELIVERED", "DELIVERY_FAILED", "CANCELLED", "REJECTED");
    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    /** What happened to one position (for logs and tests). */
    public enum Outcome {
        SENT,
        /** Recorded longer ago than location-max-age. */
        TOO_OLD,
        /** No such order in the projection, or the order has ended. */
        UNKNOWN_ORDER,
        /** Another position of the order was sent in the current throttle window. */
        THROTTLED,
        /** Not a DeliveryLocationUpdated, or unreadable / incomplete. */
        IGNORED
    }

    private final OrderRefRepository orders;
    private final LocationThrottle throttle;
    private final RealtimePublisher publisher;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration maxAge;

    public LocationService(OrderRefRepository orders, LocationThrottle throttle, RealtimePublisher publisher,
                           JsonMapper json, Clock clock, NotificationProperties properties) {
        this.orders = orders;
        this.throttle = throttle;
        this.publisher = publisher;
        this.json = json;
        this.clock = clock;
        this.maxAge = properties.getLocationMaxAge();
    }

    public Outcome handle(ConsumerRecord<String, String> record) {
        String eventType = EventEnvelope.header(record, EventHeaders.EVENT_TYPE);
        if (eventType != null && !EVENT_TYPE.equals(eventType)) {
            return Outcome.IGNORED;
        }
        DeliveryLocation location = read(record);
        if (location == null) {
            return Outcome.IGNORED;
        }
        UUID orderId = location.orderId() != null ? location.orderId() : uuid(record.key());
        if (orderId == null || location.latitude() == null || location.longitude() == null) {
            log.warn("DeliveryLocationUpdated on {}-{}@{} lacks orderId or coordinates: dropped", record.topic(),
                    record.partition(), record.offset());
            return Outcome.IGNORED;
        }
        Instant now = clock.instant();
        Instant at = recordedAt(location, record);
        if (at == null || at.isBefore(now.minus(maxAge))) {
            log.debug("Location of order {} recorded at {} is older than {}: dropped", orderId, at, maxAge);
            return Outcome.TOO_OLD;
        }
        Optional<OrderRef> order = orders.find(orderId);
        if (order.isEmpty() || ENDED.contains(order.get().status())) {
            log.debug("Location of order {}: {}, dropped", orderId,
                    order.isEmpty() ? "unknown order" : "the order is " + order.get().status());
            return Outcome.UNKNOWN_ORDER;
        }
        if (!throttle.tryAcquire(orderId)) {
            return Outcome.THROTTLED;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId.toString());
        data.put("latitude", location.latitude());
        data.put("longitude", location.longitude());
        data.put("heading", location.heading());
        data.put("at", at.toString());
        Signal signal = new Signal(SignalType.DELIVERY_LOCATION, data, now.truncatedTo(ChronoUnit.MILLIS));
        publisher.publish(new RealtimeMessage(RealtimeMessage.SIGNAL,
                List.of(Audiences.user(order.get().customerId())), json.valueToTree(signal)));
        return Outcome.SENT;
    }

    private DeliveryLocation read(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return null;
        }
        try {
            return json.readValue(record.value(), DeliveryLocation.class);
        } catch (JacksonException e) {
            log.warn("Unreadable DeliveryLocationUpdated on {}-{}@{}: dropped ({})", record.topic(),
                    record.partition(), record.offset(), e.getMessage());
            return null;
        }
    }

    /** {@code recordedAt}; else the {@code occurredAt} header; else the record's own timestamp; else null. */
    private static Instant recordedAt(DeliveryLocation location, ConsumerRecord<String, String> record) {
        if (location.recordedAt() != null) {
            return location.recordedAt();
        }
        Instant occurredAt = OccurredAt.parse(EventEnvelope.header(record, EventHeaders.OCCURRED_AT));
        if (occurredAt != null) {
            return occurredAt;
        }
        return record.timestamp() > 0 ? Instant.ofEpochMilli(record.timestamp()) : null;
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
