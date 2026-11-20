package com.grocery.delivery.stats;

import com.grocery.delivery.dispatch.config.DispatchProperties;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryOffer;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.mapper.DeliveryMapper;
import com.grocery.delivery.dispatch.repository.DeliveryOfferRepository;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.dispatch.service.DispatchException;
import com.grocery.delivery.dispatch.service.PartnerGuard;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import com.grocery.delivery.stats.StatsDtos.Day;
import com.grocery.delivery.stats.StatsDtos.PartnerStatsView;
import com.grocery.delivery.stats.StatsDtos.PayRate;
import com.grocery.delivery.stats.StatsDtos.Totals;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A delivery partner's own performance over a range of India-time days (the partner dashboard's charts): deliveries
 * by outcome, offers and how they were answered, delivery times, distance, estimated earnings (PayProperties) and
 * online time (OnlineSessions). Everything is read for the caller only — the partner id is the token's subject.
 */
@Service
public class PartnerStatsService {

    /** At most this many days per request (about a quarter), like the store reports. */
    public static final int MAX_DAYS = 92;
    /** Without dates: the last 7 days, today included. */
    static final int DEFAULT_DAYS = 7;

    private final DeliveryRepository deliveries;
    private final DeliveryOfferRepository offers;
    private final DeliveryPartnerRepository partners;
    private final OnlineSessionRepository sessions;
    private final PartnerGuard guard;
    private final DispatchProperties dispatch;
    private final PayProperties pay;
    private final Clock clock;

    public PartnerStatsService(DeliveryRepository deliveries, DeliveryOfferRepository offers,
                               DeliveryPartnerRepository partners, OnlineSessionRepository sessions, PartnerGuard guard,
                               DispatchProperties dispatch, PayProperties pay, Clock clock) {
        this.deliveries = deliveries;
        this.offers = offers;
        this.partners = partners;
        this.sessions = sessions;
        this.guard = guard;
        this.dispatch = dispatch;
        this.pay = pay;
        this.clock = clock;
    }

    /** One day's running counts while the stats are added up. */
    private static final class Tally {
        int delivered;
        int failed;
        int cancelled;
        BigDecimal earnings = BigDecimal.ZERO;
        double distanceKm;
        Duration online = Duration.ZERO;
    }

    @Transactional(readOnly = true)
    public PartnerStatsView stats(UUID partnerId, LocalDate from, LocalDate to) {
        guard.requireApproved(partnerId);
        ZoneId zone = dispatch.zone();
        Instant now = clock.instant();
        LocalDate last = to != null ? to : LocalDate.ofInstant(now, zone);
        LocalDate first = from != null ? from : last.minusDays(DEFAULT_DAYS - 1);
        if (first.isAfter(last)) {
            throw DispatchException.validation("from", "must be on or before to");
        }
        if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
            throw DispatchException.validation("to", "at most " + MAX_DAYS + " days at a time");
        }
        Instant start = first.atStartOfDay(zone).toInstant();
        Instant end = last.plusDays(1).atStartOfDay(zone).toInstant();

        Map<LocalDate, Tally> days = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            days.put(day, new Tally());
        }

        // Deliveries: assigned, and how the ones that ended in the range ended.
        int assigned = 0;
        List<Double> pickupMinutes = new ArrayList<>();
        List<Double> rideMinutes = new ArrayList<>();
        List<Double> totalMinutes = new ArrayList<>();
        for (Delivery delivery : deliveries.findForPartnerStats(partnerId, start, end)) {
            if (within(delivery.getAssignedAt(), start, end)) {
                assigned++;
            }
            if (delivery.getStatus() == DeliveryStatus.DELIVERED && within(delivery.getFinishedAt(), start, end)) {
                Tally day = days.get(LocalDate.ofInstant(delivery.getFinishedAt(), zone));
                Double km = DeliveryMapper.tripKm(delivery);
                day.delivered++;
                day.distanceKm += km != null ? km : 0;
                day.earnings = day.earnings.add(earning(km));
                addMinutes(pickupMinutes, delivery.getAssignedAt(), delivery.getPickedUpAt());
                addMinutes(rideMinutes, delivery.getPickedUpAt(), delivery.getDeliveredAt());
                addMinutes(totalMinutes, delivery.getAssignedAt(), delivery.getDeliveredAt());
            } else if (delivery.getStatus() == DeliveryStatus.FAILED && within(delivery.getFinishedAt(), start, end)) {
                days.get(LocalDate.ofInstant(delivery.getFinishedAt(), zone)).failed++;
            } else if (delivery.getStatus() == DeliveryStatus.CANCELLED && within(delivery.getCancelledAt(), start, end)) {
                days.get(LocalDate.ofInstant(delivery.getCancelledAt(), zone)).cancelled++;
            }
        }

        // Offers sent in the range, by how the partner answered them.
        int received = 0;
        int accepted = 0;
        int rejected = 0;
        int expired = 0;
        for (DeliveryOffer offer : offers.findSentToPartner(partnerId, start, end)) {
            received++;
            switch (offer.getStatus()) {
                case ACCEPTED -> accepted++;
                case REJECTED -> rejected++;
                case EXPIRED -> expired++;
                default -> {
                    // PENDING (still open) and WITHDRAWN (someone else was quicker) are not an answer.
                }
            }
        }

        // Online time, split over the days.
        List<OnlineSession> online = sessions.findOverlapping(partnerId, start, end);
        for (Map.Entry<LocalDate, Tally> entry : days.entrySet()) {
            Instant dayStart = entry.getKey().atStartOfDay(zone).toInstant();
            Instant dayEnd = entry.getKey().plusDays(1).atStartOfDay(zone).toInstant();
            for (OnlineSession session : online) {
                entry.getValue().online = entry.getValue().online.plus(session.overlap(dayStart, dayEnd));
            }
        }

        List<Day> dayViews = new ArrayList<>();
        int delivered = 0;
        int failed = 0;
        int cancelled = 0;
        BigDecimal earnings = BigDecimal.ZERO;
        double distanceKm = 0;
        Duration onlineTotal = Duration.ZERO;
        for (Map.Entry<LocalDate, Tally> entry : days.entrySet()) {
            Tally day = entry.getValue();
            delivered += day.delivered;
            failed += day.failed;
            cancelled += day.cancelled;
            earnings = earnings.add(day.earnings);
            distanceKm += day.distanceKm;
            onlineTotal = onlineTotal.plus(day.online);
            dayViews.add(new Day(entry.getKey(), day.delivered, day.failed, day.cancelled, money(day.earnings),
                    roundKm(day.distanceKm), day.online.toMinutes()));
        }

        boolean onDelivery = partners.findById(partnerId).map(partner -> partner.getActiveDeliveryId() != null)
                .orElse(false);
        Totals totals = new Totals(assigned, delivered, failed, cancelled, onDelivery ? 1 : 0,
                offers.countOpenByPartner(partnerId, now), received, accepted, rejected, expired,
                ratio(delivered, delivered + failed), ratio(accepted, accepted + rejected + expired),
                roundKm(distanceKm), money(earnings), average(pickupMinutes), average(rideMinutes),
                average(totalMinutes), onlineTotal.toMinutes());
        return new PartnerStatsView(first, last, zone.getId(), totals, dayViews,
                new PayRate(pay.basePerDelivery(), pay.perKm(), pay.currency()));
    }

    /** The estimated earning of one delivered order: base + per km × trip distance (base only without a distance). */
    BigDecimal earning(Double tripKm) {
        BigDecimal distance = tripKm != null ? BigDecimal.valueOf(tripKm) : BigDecimal.ZERO;
        return money(pay.basePerDelivery().add(pay.perKm().multiply(distance)));
    }

    private static boolean within(Instant at, Instant start, Instant end) {
        return at != null && !at.isBefore(start) && at.isBefore(end);
    }

    private static void addMinutes(List<Double> minutes, Instant from, Instant to) {
        if (from != null && to != null && !to.isBefore(from)) {
            minutes.add(Duration.between(from, to).toSeconds() / 60.0);
        }
    }

    private static Double average(List<Double> values) {
        return values.isEmpty() ? null
                : Math.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    private static Double ratio(int part, int whole) {
        return whole == 0 ? null : Math.round(part * 10_000.0 / whole) / 10_000.0;
    }

    private static double roundKm(double km) {
        return Math.round(km * 10) / 10.0;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
