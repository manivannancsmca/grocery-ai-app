package com.grocery.delivery.dispatch.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.delivery.dispatch.config.DispatchProperties;
import com.grocery.delivery.dispatch.dto.DeliveryExports;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliverySummary;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.HistorySummary;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.entity.FailureReason;
import com.grocery.delivery.dispatch.event.DeliveryEventPublisher;
import com.grocery.delivery.dispatch.mapper.DeliveryMapper;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The assigned partner's side of a delivery (contract §3): the active delivery, pickup / deliver / fail, history and
 * summary. Only the assigned partner sees a delivery (anyone else: 404 DELIVERY_NOT_FOUND). Every transition locks the
 * delivery row first; repeating it in the state it leads to answers 200 unchanged.
 */
@Service
public class PartnerDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(PartnerDeliveryService.class);

    /** The outcomes in a partner's history. */
    private static final Set<DeliveryStatus> FINISHED = EnumSet.of(DeliveryStatus.DELIVERED, DeliveryStatus.FAILED);

    /**
     * The history's filters as the query takes them: the outcomes, and when the delivery ended, from {@code start}
     * (included) to {@code end} (excluded); null = no bound.
     */
    private record HistoryFilter(Set<DeliveryStatus> statuses, Instant start, Instant end) {
    }

    private final DeliveryRepository deliveries;
    private final DeliveryPartnerRepository partners;
    private final AssignmentEngine engine;
    private final DeliveryEventPublisher events;
    private final PartnerGuard guard;
    private final DispatchProperties properties;
    private final ExportQueries exports;
    private final Clock clock;

    public PartnerDeliveryService(DeliveryRepository deliveries, DeliveryPartnerRepository partners,
                                  AssignmentEngine engine, DeliveryEventPublisher events, PartnerGuard guard,
                                  DispatchProperties properties, ExportQueries exports, Clock clock) {
        this.deliveries = deliveries;
        this.partners = partners;
        this.engine = engine;
        this.events = events;
        this.guard = guard;
        this.properties = properties;
        this.exports = exports;
        this.clock = clock;
    }

    /** The partner's ASSIGNED / PICKED_UP delivery, if any. */
    @Transactional(readOnly = true)
    public Optional<DeliveryView> active(UUID partnerId) {
        guard.requireApproved(partnerId);
        return partners.findById(partnerId)
                .map(partner -> partner.getActiveDeliveryId())
                .flatMap(deliveries::findById)
                .filter(delivery -> delivery.isAssignedTo(partnerId)
                        && DeliveryStatus.ACTIVE.contains(delivery.getStatus()))
                .map(DeliveryMapper::toView);
    }

    @Transactional(readOnly = true)
    public DeliveryView get(UUID partnerId, UUID deliveryId) {
        guard.requireApproved(partnerId);
        return deliveries.findById(deliveryId).filter(delivery -> delivery.isAssignedTo(partnerId))
                .map(DeliveryMapper::toView).orElseThrow(DispatchException::deliveryNotFound);
    }

    /** ASSIGNED → PICKED_UP (+ DeliveryPickedUp), only once the store marked the order ready. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryView pickUp(UUID partnerId, UUID deliveryId) {
        guard.requireApproved(partnerId);
        Delivery delivery = lockOwn(partnerId, deliveryId);
        if (delivery.getStatus() == DeliveryStatus.PICKED_UP) {
            return DeliveryMapper.toView(delivery);
        }
        if (delivery.getStatus() != DeliveryStatus.ASSIGNED) {
            throw DispatchException.invalidTransition(delivery.getStatus(), "picked up");
        }
        if (!delivery.isReadyForPickup()) {
            throw DispatchException.orderNotReady(delivery.getStatus());
        }
        delivery.pickUp(partnerId, clock.instant());
        events.pickedUp(delivery);
        log.info("Order {} picked up by partner {} (delivery {})", delivery.getOrderNumber(), partnerId,
                delivery.getId());
        return DeliveryMapper.toView(delivery);
    }

    /** PICKED_UP → DELIVERED (+ DeliveryCompleted); the partner is free again. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryView deliver(UUID partnerId, UUID deliveryId) {
        guard.requireApproved(partnerId);
        Delivery delivery = lockOwn(partnerId, deliveryId);
        if (delivery.getStatus() == DeliveryStatus.DELIVERED) {
            return DeliveryMapper.toView(delivery);
        }
        if (delivery.getStatus() != DeliveryStatus.PICKED_UP) {
            throw DispatchException.invalidTransition(delivery.getStatus(), "delivered");
        }
        delivery.deliver(partnerId, clock.instant());
        engine.releasePartner(delivery, partnerId);
        events.completed(delivery);
        log.info("Order {} delivered by partner {} (delivery {})", delivery.getOrderNumber(), partnerId,
                delivery.getId());
        return DeliveryMapper.toView(delivery);
    }

    /** PICKED_UP → FAILED (+ DeliveryFailed); the partner is free again. {@code note} required for OTHER. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeliveryView fail(UUID partnerId, UUID deliveryId, FailureReason reason, String note) {
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (reason == FailureReason.OTHER && cleanNote == null) {
            throw DispatchException.validation("note", "is required when the reason is OTHER");
        }
        guard.requireApproved(partnerId);
        Delivery delivery = lockOwn(partnerId, deliveryId);
        if (delivery.getStatus() == DeliveryStatus.FAILED) {
            return DeliveryMapper.toView(delivery);
        }
        if (delivery.getStatus() != DeliveryStatus.PICKED_UP) {
            throw DispatchException.invalidTransition(delivery.getStatus(), "failed");
        }
        delivery.fail(reason, cleanNote, partnerId, clock.instant());
        engine.releasePartner(delivery, partnerId);
        events.failed(delivery);
        log.warn("Delivery {} of order {} failed ({}) reported by partner {}", delivery.getId(),
                delivery.getOrderNumber(), reason, partnerId);
        return DeliveryMapper.toView(delivery);
    }

    /**
     * DELIVERED and FAILED deliveries of the partner, newest first: only {@code status} when given, only those that
     * ended on India-time days {@code from}–{@code to} (both included; either may be left out).
     */
    @Transactional(readOnly = true)
    public PageResponse<DeliverySummary> history(UUID partnerId, String status, LocalDate from, LocalDate to,
                                                 Integer page, Integer size) {
        guard.requireApproved(partnerId);
        HistoryFilter filter = historyFilter(status, from, to);
        return Pages.toResponse(deliveries.history(partnerId, filter.statuses(), filter.start(), filter.end(),
                Pages.request(page, size, Sort.unsorted())), DeliveryMapper::toSummary);
    }

    /**
     * {@code GET /api/delivery/history/export}: the caller's own finished deliveries with the list's filters, every
     * page, newest first (the partner id is the caller's, from the JWT). No transaction here: each batch has its own.
     */
    public RowSource<DeliverySummary> historyExport(UUID partnerId, String status, LocalDate from, LocalDate to) {
        guard.requireApproved(partnerId);
        HistoryFilter filter = historyFilter(status, from, to);
        return exports.jpql(Delivery.class, "d", DeliveryRepository.PARTNER_HISTORY)
                .param("partnerId", partnerId)
                .param("statuses", filter.statuses())
                .param("start", filter.start())
                .param("end", filter.end())
                .orderBy(DeliveryExports.HISTORY_SORT.resolve(null))
                .rows(DeliveryMapper::toSummary);
    }

    /**
     * Checks the history's filters: {@code status} DELIVERED or FAILED (else 400 INVALID_PARAMETER, like an unknown
     * status anywhere), {@code from} not after {@code to} (else 400 VALIDATION_FAILED, as for the statistics). The
     * days are India time, the same days as the summary's "today" and "this week".
     */
    private HistoryFilter historyFilter(String status, LocalDate from, LocalDate to) {
        DeliveryStatus outcome = BadRequestException.parseEnum(DeliveryStatus.class, "status", status);
        if (outcome != null && !FINISHED.contains(outcome)) {
            throw new BadRequestException("INVALID_PARAMETER",
                    "The history has DELIVERED and FAILED deliveries only, not '" + status.trim() + "'");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw DispatchException.validation("from", "must be on or before to");
        }
        ZoneId zone = properties.zone();
        return new HistoryFilter(outcome == null ? FINISHED : EnumSet.of(outcome),
                from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** Delivered today, delivered and failed in the last 7 days (India-time days, today included). */
    @Transactional(readOnly = true)
    public HistorySummary summary(UUID partnerId) {
        guard.requireApproved(partnerId);
        ZoneId zone = properties.zone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        Instant startOfToday = today.atStartOfDay(zone).toInstant();
        Instant startOfWeek = today.minusDays(6).atStartOfDay(zone).toInstant();
        return new HistorySummary(
                deliveries.countFinishedSince(partnerId, DeliveryStatus.DELIVERED, startOfToday),
                deliveries.countFinishedSince(partnerId, DeliveryStatus.DELIVERED, startOfWeek),
                deliveries.countFinishedSince(partnerId, DeliveryStatus.FAILED, startOfWeek));
    }

    /** The delivery, locked, if the caller is its assigned partner; otherwise 404 DELIVERY_NOT_FOUND. */
    private Delivery lockOwn(UUID partnerId, UUID deliveryId) {
        return deliveries.lockById(deliveryId).filter(delivery -> delivery.isAssignedTo(partnerId))
                .orElseThrow(DispatchException::deliveryNotFound);
    }
}
