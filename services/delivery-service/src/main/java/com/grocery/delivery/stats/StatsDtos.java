package com.grocery.delivery.stats;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** {@code GET /api/delivery/stats}: a delivery partner's own performance over a range of India-time days. */
public final class StatsDtos {

    private StatsDtos() {
    }

    /**
     * @param from     first day (inclusive)
     * @param to       last day (inclusive)
     * @param timezone the days' time zone (Asia/Kolkata)
     * @param totals   the whole range
     * @param days     one entry per day, oldest first (every day of the range, also the empty ones)
     * @param payRate  the rate the earnings are estimated with
     */
    public record PartnerStatsView(LocalDate from, LocalDate to, String timezone, Totals totals, List<Day> days,
                                   PayRate payRate) {
    }

    /**
     * Counts by when things happened: {@code assigned} by the assignment time, {@code delivered} / {@code failed} by
     * when the delivery ended, {@code cancelled} (after it was assigned to this partner) by the cancellation time, and
     * the offers by when they were sent. {@code inProgress} and {@code offersPending} are right now, whatever the range.
     *
     * @param successRate      delivered / (delivered + failed), 0–1; null without finished deliveries
     * @param acceptanceRate   accepted / (accepted + rejected + expired), 0–1; null without answered offers (offers
     *                         withdrawn because another partner was quicker are left out)
     * @param distanceKm       trip distance of the delivered orders (straight line, store → customer)
     * @param earnings         estimated earnings of the delivered orders (see payRate)
     * @param avgPickupMinutes assigned → picked up, over the delivered orders; null without any
     * @param avgRideMinutes   picked up → delivered
     * @param avgTotalMinutes  assigned → delivered
     * @param onlineMinutes    time online in the range (see OnlineSessions)
     */
    public record Totals(int assigned, int delivered, int failed, int cancelled, int inProgress, long offersPending,
                         int offersReceived, int offersAccepted, int offersRejected, int offersExpired,
                         Double successRate, Double acceptanceRate, double distanceKm, BigDecimal earnings,
                         Double avgPickupMinutes, Double avgRideMinutes, Double avgTotalMinutes, long onlineMinutes) {
    }

    public record Day(LocalDate date, int delivered, int failed, int cancelled, BigDecimal earnings, double distanceKm,
                      long onlineMinutes) {
    }

    public record PayRate(BigDecimal basePerDelivery, BigDecimal perKm, String currency) {
    }
}
