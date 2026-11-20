package com.grocery.notification.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.notification.channel.NotificationChannel;
import com.grocery.notification.config.NotificationProperties;
import com.grocery.notification.dispatch.DispatchPlan;
import com.grocery.notification.dispatch.DispatchPlan.PlannedEmail;
import com.grocery.notification.dispatch.DispatchPlan.PlannedNotification;
import com.grocery.notification.dispatch.DispatchPlan.PlannedSession;
import com.grocery.notification.dispatch.DispatchPlan.PlannedSignal;
import com.grocery.notification.dispatch.EventHandler;
import com.grocery.notification.dispatch.NotificationDraft;
import com.grocery.notification.dto.NotificationDtos.NotificationView;
import com.grocery.notification.dto.NotificationDtos.Session;
import com.grocery.notification.dto.NotificationDtos.Signal;
import com.grocery.notification.realtime.PublicSignalThrottle;
import com.grocery.notification.realtime.RealtimeMessage;
import com.grocery.notification.realtime.RealtimePublisher;
import com.grocery.notification.repository.NotificationRepository;
import com.grocery.notification.repository.NotificationRepository.NewNotification;
import com.grocery.notification.repository.PendingEventRepository;
import com.grocery.notification.repository.PendingEventRepository.PendingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The dispatch pipeline of one Kafka record, in ONE transaction:
 * <ol>
 *   <li>{@code processed_event} (consumer {@value #CONSUMER}) — a duplicate stops here;</li>
 *   <li>the handler updates its projections (store owners, order refs) — a payment event whose order is unknown is
 *       parked in {@code pending_event} instead (nothing else happens to it now);</li>
 *   <li>if the event is not older than {@code max-event-age} (header {@code occurredAt}; none = new), the handler
 *       plans the notifications, signals and session closes of contract §4, and the notifications are stored with
 *       their audiences — and (Phase 12) offered to every other {@link NotificationChannel} (the email outbox), which
 *       records its deliveries in this same transaction; the security emails of password management are stored
 *       without audiences (no inbox shows them) and handed to the channels directly;</li>
 *   <li>if the event made an order known, that order's parked payment events are applied the same way, in arrival
 *       order, each judged by its ORIGINAL occurredAt, and deleted;</li>
 *   <li>after commit, one Redis message per notification / signal / session ({@link RealtimePublisher}): the
 *       event's own first, then those of the parked events; public signals go through the
 *       {@link PublicSignalThrottle}.</li>
 * </ol>
 * Events no handler supports (catalog events, the order saga's reservation events, StockCommitted, …) are ignored
 * without touching the database. Phase 9: a DeliveryPartnerAssigned whose order is unknown is parked like a payment
 * event. The partners' live positions (delivery.location) do not come through here: see {@link LocationService}.
 */
@Service
public class DispatchService {

    public static final String CONSUMER = "notification-service.dispatch";
    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    /** What one event did (for logs and tests). */
    public enum Outcome {
        /** Fresh event: projections updated, notifications stored, messages published after commit. */
        DISPATCHED,
        /** Older than max-event-age: projections only. */
        PROJECTED_ONLY,
        /** A payment event whose order is not known yet: parked until it is. */
        PARKED,
        /** Already processed (redelivery). */
        DUPLICATE,
        /** Not an event this service uses. */
        IGNORED
    }

    /** A message to publish after commit; {@code throttleKey} only for public signals. */
    private record Outbound(RealtimeMessage message, String throttleKey) {
    }

    private final List<EventHandler> handlers;
    private final List<NotificationChannel> channels;
    private final ProcessedEventGuard processedEvents;
    private final NotificationRepository notifications;
    private final PendingEventRepository pendingEvents;
    private final RealtimePublisher publisher;
    private final PublicSignalThrottle throttle;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration maxEventAge;

    public DispatchService(List<EventHandler> handlers, List<NotificationChannel> channels,
                           ProcessedEventGuard processedEvents,
                           NotificationRepository notifications, PendingEventRepository pendingEvents,
                           RealtimePublisher publisher, PublicSignalThrottle throttle,
                           PlatformTransactionManager transactionManager, JsonMapper json, Clock clock,
                           NotificationProperties properties) {
        this.handlers = List.copyOf(handlers);
        this.channels = List.copyOf(channels);
        this.processedEvents = processedEvents;
        this.notifications = notifications;
        this.pendingEvents = pendingEvents;
        this.publisher = publisher;
        this.throttle = throttle;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
        this.maxEventAge = properties.getMaxEventAge();
    }

    /**
     * @param occurredAt the event's {@code occurredAt} header; null = treat as new
     */
    public Outcome dispatch(EventEnvelope event, Instant occurredAt) {
        EventHandler handler = handlerFor(event.topic(), event.eventType());
        if (handler == null) {
            return Outcome.IGNORED;
        }
        Outcome outcome = transaction.execute(status -> {
            if (!processedEvents.markProcessed(event.eventId(), CONSUMER)) {
                return Outcome.DUPLICATE;
            }
            Instant now = clock.instant();
            DispatchPlan plan = new DispatchPlan(isFresh(occurredAt, now), now, occurredAt);
            handler.handle(event, plan);
            List<Outbound> outbound = new ArrayList<>(plan.fresh() ? store(plan, event.eventId(), now) : List.of());
            for (UUID orderId : plan.knownOrders()) {
                outbound.addAll(applyParked(orderId, now));
            }
            if (!outbound.isEmpty()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publish(outbound);
                    }
                });
            }
            log.debug("{} {}: {} notifications, {} signals, {} session closes, {} emails only{}", event.eventType(),
                    event.eventId(), plan.notifications().size(), plan.signals().size(), plan.sessions().size(),
                    plan.emails().size(), plan.parked() ? " (parked)" : "");
            return plan.parked() ? Outcome.PARKED : plan.fresh() ? Outcome.DISPATCHED : Outcome.PROJECTED_ONLY;
        });
        if (outcome == Outcome.PROJECTED_ONLY) {
            log.debug("{} {} occurred at {}, older than {}: projections only", event.eventType(), event.eventId(),
                    occurredAt, maxEventAge);
        }
        return outcome;
    }

    /**
     * The order's parked payment events, in arrival order, as if they arrived now — but with their own occurredAt:
     * one that has become older than max-event-age meanwhile notifies nobody. They are deleted in this transaction.
     */
    private List<Outbound> applyParked(UUID orderId, Instant now) {
        List<Outbound> outbound = new ArrayList<>();
        for (PendingEvent parked : pendingEvents.takeForOrder(orderId)) {
            EventHandler handler = handlerFor(parked.topic(), parked.eventType());
            if (handler == null) {
                log.warn("Dropping parked {} {} of order {}: no handler", parked.eventType(), parked.eventId(),
                        orderId);
                continue;
            }
            DispatchPlan plan = new DispatchPlan(isFresh(parked.occurredAt(), now), now, parked.occurredAt());
            handler.handle(parked.envelope(), plan);
            if (plan.fresh()) {
                outbound.addAll(store(plan, parked.eventId(), now));
            }
            log.info("Applied parked {} {} of order {} (parked at {}){}", parked.eventType(), parked.eventId(),
                    orderId, parked.receivedAt(), plan.fresh() ? "" : ": older than max-event-age, nobody notified");
        }
        return outbound;
    }

    private EventHandler handlerFor(String topic, String eventType) {
        return handlers.stream()
                .filter(candidate -> candidate.supports(topic, eventType))
                .findFirst()
                .orElse(null);
    }

    private boolean isFresh(Instant occurredAt, Instant now) {
        return occurredAt == null || !occurredAt.isBefore(now.minus(maxEventAge));
    }

    /** Stores the notifications; returns the messages to publish: notifications, then signals, then sessions. */
    private List<Outbound> store(DispatchPlan plan, String eventId, Instant now) {
        List<Outbound> outbound = new ArrayList<>();
        for (PlannedNotification planned : plan.notifications()) {
            NotificationDraft draft = planned.draft();
            UUID id = Ids.newId();
            notifications.insert(new NewNotification(id, draft.type(), draft.title(), draft.body(), draft.link(),
                    json.writeValueAsString(draft.data()), eventId, now, planned.audiences()));
            // Phase 12: other channels (email) record what they will deliver, in this same transaction
            for (NotificationChannel channel : channels) {
                channel.enqueue(id, draft, planned.audiences(), now);
            }
            NotificationView view = new NotificationView(id, draft.type().name(), draft.type().category().name(),
                    draft.type().severity().name(), draft.title(), draft.body(), draft.link(),
                    json.valueToTree(draft.data()), false, now);
            outbound.add(new Outbound(message(RealtimeMessage.NOTIFICATION, planned.audiences(), view), null));
        }
        for (PlannedEmail planned : plan.emails()) {
            NotificationDraft draft = planned.draft();
            UUID id = Ids.newId();
            // The email rows belong to a notification (foreign key, 90-day retention). A security email gets one without
            // audience rows, so no inbox, unread count, stream or replay ever shows it. It keeps neither the link nor
            // the data: the reset link carries a secret that only the email needs.
            notifications.insert(new NewNotification(id, draft.type(), draft.title(), draft.body(), null, "{}",
                    eventId, now, List.of()));
            for (NotificationChannel channel : channels) {
                channel.enqueueDirect(id, draft, planned.userId(), planned.email(), planned.fullName(), now);
            }
        }
        Instant at = now.truncatedTo(ChronoUnit.MILLIS);
        for (PlannedSignal signal : plan.signals()) {
            outbound.add(new Outbound(message(RealtimeMessage.SIGNAL, signal.audiences(),
                    new Signal(signal.type(), signal.data(), at)), signal.throttleKey()));
        }
        for (PlannedSession session : plan.sessions()) {
            outbound.add(new Outbound(message(RealtimeMessage.SESSION, session.audiences(),
                    new Session(session.reason())), null));
        }
        return outbound;
    }

    private RealtimeMessage message(String kind, Collection<String> audiences, Object payload) {
        return new RealtimeMessage(kind, List.copyOf(audiences), json.valueToTree(payload));
    }

    /** After commit: never throws (the event is processed; a failed publish only costs the live update). */
    private void publish(List<Outbound> outbound) {
        for (Outbound item : outbound) {
            try {
                if (item.throttleKey() != null) {
                    throttle.submit(item.throttleKey(), item.message());
                } else {
                    publisher.publish(item.message());
                }
            } catch (RuntimeException e) {
                log.warn("Could not publish a realtime {} for {}: {}", item.message().kind(),
                        item.message().audiences(), e.toString());
            }
        }
    }
}
