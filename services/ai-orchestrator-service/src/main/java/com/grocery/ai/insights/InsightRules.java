package com.grocery.ai.insights;

import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rule-based insight report (contract §6 "Fallback"), from the same data the model gets: the day's order stats,
 * the stuck orders and the payment reconciliation summary (each may be missing when its API failed). Also used by the
 * scripted model to answer the insights prompt deterministically.
 */
public final class InsightRules {

    private InsightRules() {
    }

    /**
     * @param data {@code {date, stats, stuckOrders, reconciliation}} as collected by the insights service; a part
     *             that could not be read is {@code {"status":"ERROR",…}} or absent
     */
    public static InsightReport.Draft draft(JsonNode data) {
        JsonNode stats = usable(Json.at(data, "stats"));
        JsonNode stuck = usable(Json.at(data, "stuckOrders"));
        JsonNode reconciliation = usable(Json.at(data, "reconciliation"));
        String date = Objects.requireNonNullElse(Json.text(data, "date"), "today");

        List<InsightReport.Kpi> kpis = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        String headline;

        if (stats != null) {
            int orders = intOf(stats, "orders");
            BigDecimal revenue = Objects.requireNonNullElse(Json.decimal(stats, "confirmedRevenue"), BigDecimal.ZERO);
            int cancelled = intOf(stats, "byStatus.CANCELLED");
            int rejected = intOf(stats, "byStatus.REJECTED");
            int awaiting = intOf(stats, "byStatus.AWAITING_PAYMENT");
            headline = orders + (orders == 1 ? " order" : " orders") + " on " + date + ", " + Money.inr(revenue)
                    + " confirmed revenue";
            kpis.add(new InsightReport.Kpi("Orders", String.valueOf(orders), "created on " + date));
            kpis.add(new InsightReport.Kpi("Confirmed revenue", Money.inr(revenue), null));
            kpis.add(new InsightReport.Kpi("Average order value",
                    Money.inr(Json.decimal(stats, "averageOrderValue")), null));
            kpis.add(new InsightReport.Kpi("Cancelled", String.valueOf(cancelled), null));
            kpis.add(new InsightReport.Kpi("Rejected", String.valueOf(rejected), "out of stock"));
            observations.add("Grocery " + intOf(stats, "byType.GROCERY") + ", restaurant "
                    + intOf(stats, "byType.RESTAURANT") + " orders.");
            if (awaiting > 0) {
                observations.add(awaiting + (awaiting == 1 ? " order is" : " orders are") + " waiting for payment.");
            }
            if (orders > 0) {
                int cancelRate = BigDecimal.valueOf(cancelled + rejected).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(orders), 0, RoundingMode.HALF_UP).intValue();
                observations.add(cancelRate + "% of the orders were cancelled or rejected.");
                if (cancelRate >= 20) {
                    risks.add("A high share of orders (" + cancelRate + "%) was cancelled or rejected.");
                    actions.add("Review the cancellation and rejection reasons of today's orders.");
                }
            } else {
                observations.add("No orders yet on " + date + ".");
            }
        } else {
            headline = "Order statistics for " + date + " are not available right now";
            risks.add("Order statistics could not be loaded.");
        }

        if (stuck != null) {
            int count = intOf(stuck, "count");
            kpis.add(new InsightReport.Kpi("Stuck orders", String.valueOf(count), null));
            if (count > 0) {
                List<JsonNode> list = Json.list(stuck, "stuckOrders");
                JsonNode worst = list.isEmpty() ? null : list.getFirst();
                risks.add(count + (count == 1 ? " order is" : " orders are") + " stuck"
                        + (worst == null ? "." : "; the longest is " + Json.text(worst, "orderNumber") + " ("
                        + Json.text(worst, "status") + ", " + Json.text(worst, "reason") + ", "
                        + intOf(worst, "minutesOverdue") + " min overdue)."));
                actions.add("Check the stuck orders on the Operations page.");
            } else {
                observations.add("No orders are stuck.");
            }
        } else {
            risks.add("Stuck orders could not be checked.");
        }

        if (reconciliation != null) {
            int recovered = intOf(reconciliation, "recovered");
            int failures = intOf(reconciliation, "failures");
            kpis.add(new InsightReport.Kpi("Payments recovered", String.valueOf(recovered),
                    "by reconciliation, cumulative"));
            if (recovered > 0) {
                observations.add(recovered + " lost payments were recovered by reconciliation so far.");
            }
            if (failures > 0) {
                risks.add(failures + " reconciliation checks failed (provider unreachable or errors).");
                actions.add("Look at the payment provider's status and the reconciliation failures.");
            }
        } else {
            risks.add("The payment reconciliation summary could not be loaded.");
        }
        if (actions.isEmpty()) {
            actions.add("No action needed right now.");
        }
        return new InsightReport.Draft(headline, kpis, observations, risks, actions);
    }

    private static JsonNode usable(JsonNode part) {
        return part == null || "ERROR".equals(Json.text(part, "status")) ? null : part;
    }

    private static int intOf(JsonNode node, String path) {
        Integer value = Json.integer(node, path);
        return value == null ? 0 : value;
    }
}
