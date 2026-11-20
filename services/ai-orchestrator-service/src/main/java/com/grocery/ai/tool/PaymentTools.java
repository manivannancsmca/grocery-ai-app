package com.grocery.ai.tool;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.link.Pages;
import com.grocery.ai.support.IndiaTime;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The {@code payment} assistant's own tools, called as the customer through the gateway (their own orders and
 * payments only). Both are READ tools: the chat never takes a payment — paying happens on the order page ("Pay now"),
 * and refunds are started by the system or by admins.
 *
 * <ul>
 *   <li>{@code listMyPayments}: the customer's recent orders with their payment status, filtered (waiting for
 *       payment, paid, refunds, time ran out).</li>
 *   <li>{@code getPaymentStatus}: one order's payment in full — attempts, failure reasons, refunds — plus
 *       {@link PaymentGuidance}'s situation, "what happened" and "next step" in plain words.</li>
 * </ul>
 * The payment assistant also uses the shopping assistant's {@code cancelOrder} (see {@code Agents}).
 */
@Component
public class PaymentTools {

    /** {@code listMyPayments}' filters. */
    static final Set<String> PAYMENT_FILTERS = Set.of("ALL", "WAITING_FOR_PAYMENT", "PAID", "REFUNDS", "EXPIRED");
    /** Orders scanned for a filter that the order API cannot apply itself (newest first). */
    static final int SCAN_SIZE = 50;
    /** Orders listed at most. */
    static final int LIST_SIZE = 10;
    private static final Logger log = LoggerFactory.getLogger(PaymentTools.class);

    private final BusinessApi api;
    private final Clock clock;
    private final String refundTimeline;

    public PaymentTools(BusinessApi api, Clock clock, AiProperties properties) {
        this.api = api;
        this.clock = clock;
        this.refundTimeline = properties.customerCare().refundTimeline();
    }

    public List<ToolSpec> tools() {
        return List.of(listMyPayments(), getPaymentStatus());
    }

    /** Also used by the support assistant (refund questions). */
    public ToolSpec getPaymentStatus() {
        return ToolSpec.run("getPaymentStatus", "Checked a payment",
                "One order's payment and refund (\"did my payment go through\", \"did I get my refund\", \"where "
                        + "is my money\"): whether it is paid, waiting for payment (with the deadline), failed (with "
                        + "the reason) or refunded, every attempt and refund, and whatHappened / nextStep in plain "
                        + "words to tell the customer.", RiskClass.READ, ShoppingTools.CUSTOMER,
                ArgSchema.builder().orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M").build(),
                this::getPaymentStatus);
    }

    private ToolSpec listMyPayments() {
        return ToolSpec.run("listMyPayments", "Listed payments",
                "The customer's recent orders with their payment status, newest first (up to 10). filter: "
                        + "WAITING_FOR_PAYMENT (orders to pay now, with the deadline), PAID, REFUNDS (refund in "
                        + "progress or done), EXPIRED (the time to pay ran out) or ALL (default).",
                RiskClass.READ, ShoppingTools.CUSTOMER,
                ArgSchema.builder().enumeration("filter", PAYMENT_FILTERS,
                        "WAITING_FOR_PAYMENT, PAID, REFUNDS, EXPIRED or ALL").build(),
                this::listMyPayments);
    }

    // ---- listMyPayments -------------------------------------------------------------------------------------------

    private ToolResult listMyPayments(ToolCallContext call) {
        String filter = call.args().string("filter") == null ? "ALL" : call.args().string("filter");
        Map<String, Object> params = new LinkedHashMap<>();
        // The order API filters by order status, which is exactly "waiting for payment"; the other filters are on the
        // payment status, so the newest orders are scanned instead.
        if ("WAITING_FOR_PAYMENT".equals(filter)) {
            params.put("status", "AWAITING_PAYMENT");
            params.put("size", LIST_SIZE);
        } else {
            params.put("size", "ALL".equals(filter) ? LIST_SIZE : SCAN_SIZE);
        }
        Instant now = clock.instant();
        return Results.from(api.get(call.http(), "/api/orders", params), ok -> {
            Predicate<JsonNode> wanted = switch (filter) {
                case "PAID" -> order -> "PAID".equals(Json.text(order, "payment.status"));
                case "REFUNDS" -> order -> Set.of("REFUND_PENDING", "REFUNDED", "PARTIALLY_REFUNDED")
                        .contains(Json.text(order, "payment.status"));
                case "EXPIRED" -> order -> "EXPIRED".equals(Json.text(order, "payment.status"));
                default -> order -> true;
            };
            List<JsonNode> matching = Json.list(ok.body(), "content").stream().filter(wanted).toList();
            ArrayNode orders = Results.array();
            for (JsonNode summary : matching.subList(0, Math.min(LIST_SIZE, matching.size()))) {
                orders.add(paymentLine(summary, now));
            }
            ObjectNode result = Results.object();
            result.put("filter", filter);
            result.put("count", matching.size());
            result.set("orders", orders);
            return Results.ok(result, listSummary(filter, matching.size()), ok,
                    Pages.myOrders("WAITING_FOR_PAYMENT".equals(filter) ? "AWAITING_PAYMENT" : null));
        });
    }

    /** One order of the list: number, store, total, order and payment status (codes and words), deadline. */
    private static ObjectNode paymentLine(JsonNode summary, Instant now) {
        ObjectNode line = Results.pick(summary, "orderNumber", "total");
        Results.put(line, "store", Json.text(summary, "store.name"));
        Results.put(line, "orderStatus", Json.text(summary, "status"));
        Results.put(line, "orderStatusText", StatusWords.order(Json.text(summary, "status")));
        Results.put(line, "paymentStatus", Json.text(summary, "payment.status"));
        Results.put(line, "paymentStatusText", StatusWords.orderPayment(Json.text(summary, "payment.status")));
        Instant payBy = IndiaTime.parse(Json.text(summary, "payment.dueAt"));
        if (payBy != null) {
            line.put("payBy", IndiaTime.format(payBy));
            Long minutesLeft = PaymentGuidance.minutesLeft(payBy, now);
            if (minutesLeft != null) {
                line.put("minutesLeftToPay", minutesLeft);
            }
        }
        Results.put(line, "placedAt", IndiaTime.format(Json.text(summary, "createdAt")));
        return line;
    }

    /** "2 orders waiting for payment", "No refunds among your recent orders", "5 recent orders". */
    static String listSummary(String filter, int count) {
        String orders = count == 1 ? " order" : " orders";
        return switch (filter) {
            case "WAITING_FOR_PAYMENT" -> count == 0 ? "No orders waiting for payment"
                    : count + orders + " waiting for payment";
            case "PAID" -> count == 0 ? "No paid orders among your recent orders" : count + " paid" + orders;
            case "REFUNDS" -> count == 0 ? "No refunds among your recent orders"
                    : count + orders + " with a refund";
            case "EXPIRED" -> count == 0 ? "No orders where the time to pay ran out"
                    : count + orders + " where the time to pay ran out";
            default -> count == 0 ? "No orders yet" : count + " recent" + orders;
        };
    }

    // ---- getPaymentStatus -----------------------------------------------------------------------------------------

    private ToolResult getPaymentStatus(ToolCallContext call) {
        String number = call.args().string("orderNumber");
        Fetch order = api.customerOrder(call.http(), number);
        if (!(order instanceof Fetch.Ok orderOk)) {
            Fetch.Problem problem = (Fetch.Problem) order;          // ORDER_NOT_FOUND, SERVICE_UNAVAILABLE, …
            return new ToolResult.Failed(problem.code(), problem.message(), problem.httpStatus());
        }
        JsonNode view = orderOk.body();
        UUID orderId = Json.uuid(view, "id");
        // payment-service: attempts, failure reasons and refunds. 404 = no payment started yet (still checking stock,
        // or cancelled before payment); any other problem still lets us answer from the order's own payment view.
        Fetch payment = api.get(call.http(), "/api/payments/orders/" + orderId);
        JsonNode paymentBody = payment instanceof Fetch.Ok paymentOk ? paymentOk.body() : null;
        boolean paymentDetailsMissing = payment instanceof Fetch.Problem problem
                && !Integer.valueOf(404).equals(problem.httpStatus());
        if (paymentDetailsMissing) {
            log.warn("Payment details of order {} unavailable: {}", orderId, ((Fetch.Problem) payment).code());
        }

        Instant now = clock.instant();
        PaymentGuidance.Facts facts = facts(view, paymentBody);
        PaymentGuidance.Assessment assessment = PaymentGuidance.assess(facts, now, refundTimeline);

        ObjectNode result = Results.object();
        ObjectNode orderNode = Results.pick(view, "orderNumber", "total", "currency");
        Results.put(orderNode, "store", Json.text(view, "store.name"));
        Results.put(orderNode, "orderStatus", Json.text(view, "status"));
        Results.put(orderNode, "orderStatusText", StatusWords.order(Json.text(view, "status")));
        Results.put(orderNode, "placedAt", IndiaTime.format(Json.text(view, "createdAt")));
        result.set("order", orderNode);
        result.set("payment", paymentNode(facts, assessment, paymentBody));
        result.put("situation", assessment.situation().name());
        result.put("whatHappened", assessment.whatHappened());
        result.put("nextStep", assessment.nextStep());
        result.put("canPayNow", assessment.canPayNow());
        if (paymentDetailsMissing) {
            result.put("note", "The payment details (attempts and refunds) could not be loaded right now; this "
                    + "answer uses the order's own payment status.");
        }
        String summary = "Payment for " + number + ": " + situationWords(assessment.situation());
        return Results.ok(result, summary, orderOk, assessment.canPayNow()
                ? Pages.payForOrder(orderId, number) : Pages.myOrder(orderId, number));
    }

    /**
     * One order's payment in the words getPaymentStatus gives, for the payment assistant's context line:
     * {@code , what happened "You paid ₹239.00 … (the store could not accept it).", next step "It went back …"}.
     * With only "order status CANCELLED, payment status REFUNDED" in the line, the small model answered without a
     * tool call and said the payment "was not completed". Two reads, like getPaymentStatus; "" when the order cannot
     * be read.
     */
    public String paymentNote(CallContext context, UUID orderId) {
        if (!(api.get(context, "/api/orders/" + orderId) instanceof Fetch.Ok order)) {
            return "";
        }
        Fetch payment = api.get(context, "/api/payments/orders/" + orderId);
        PaymentGuidance.Assessment assessment = PaymentGuidance.assess(
                facts(order.body(), payment instanceof Fetch.Ok paymentOk ? paymentOk.body() : null),
                clock.instant(), refundTimeline);
        return ", what happened \"" + assessment.whatHappened().replace('"', '\'') + "\", next step \""
                + assessment.nextStep().replace('"', '\'') + "\"";
    }

    /** The facts PaymentGuidance needs, from the OrderView and (when there is one) the PaymentView. */
    static PaymentGuidance.Facts facts(JsonNode order, JsonNode payment) {
        List<PaymentGuidance.Attempt> attempts = new ArrayList<>();
        List<PaymentGuidance.Refund> refunds = new ArrayList<>();
        if (payment != null) {
            for (JsonNode attempt : Json.list(payment, "attempts")) {
                attempts.add(new PaymentGuidance.Attempt(Json.text(attempt, "status"), Json.text(attempt, "method"),
                        Json.text(attempt, "errorCode"), Json.text(attempt, "errorDescription"),
                        IndiaTime.parse(Json.text(attempt, "at"))));
            }
            for (JsonNode refund : Json.list(payment, "refunds")) {
                refunds.add(new PaymentGuidance.Refund(Json.decimal(refund, "amount"), Json.text(refund, "status"),
                        Json.text(refund, "reason"), IndiaTime.parse(Json.text(refund, "createdAt")),
                        IndiaTime.parse(Json.text(refund, "processedAt"))));
            }
        }
        // Without payment-service's attempts, order-service still knows the last failure.
        JsonNode lastFailure = Json.at(order, "payment.lastFailure");
        if (attempts.isEmpty() && lastFailure != null && lastFailure.isObject()) {
            attempts.add(new PaymentGuidance.Attempt("FAILED", null, Json.text(lastFailure, "code"),
                    Json.text(lastFailure, "description"), IndiaTime.parse(Json.text(lastFailure, "at"))));
        }
        Instant paidAt = IndiaTime.parse(Json.text(order, "payment.paidAt"));
        if (paidAt == null && payment != null) {
            paidAt = IndiaTime.parse(Json.text(payment, "capturedAt"));
        }
        return new PaymentGuidance.Facts(Json.text(order, "status"), Json.text(order, "payment.status"),
                Json.text(order, "cancellation.reason"), Json.decimal(order, "total"),
                IndiaTime.parse(Json.text(order, "payment.dueAt")), paidAt,
                Json.decimal(order, "payment.refundedAmount"),
                payment == null ? null : Json.text(payment, "status"),
                payment == null ? null : Json.text(payment, "method"), attempts, refunds);
    }

    /** The payment for the model: status (code and words), method, deadline, newest attempts and every refund. */
    private static ObjectNode paymentNode(PaymentGuidance.Facts facts, PaymentGuidance.Assessment assessment,
                                          JsonNode payment) {
        ObjectNode node = Results.object();
        Results.put(node, "status", facts.paymentStatus());
        Results.put(node, "statusText", StatusWords.orderPayment(facts.paymentStatus()));
        Results.put(node, "amount", payment == null ? facts.total() : Json.decimal(payment, "amount"));
        Results.put(node, "method", StatusWords.paymentMethod(facts.method()));
        Results.put(node, "paidAt", IndiaTime.format(facts.paidAt()));
        Results.put(node, "payBy", IndiaTime.format(facts.payBy()));
        if (assessment.minutesLeftToPay() != null) {
            node.put("minutesLeftToPay", assessment.minutesLeftToPay());
        }
        if (facts.refundedAmount() != null && facts.refundedAmount().signum() > 0) {
            node.put("refundedAmount", Money.scale(facts.refundedAmount()));
        }
        ArrayNode attempts = Results.array();
        List<PaymentGuidance.Attempt> newestFirst = new ArrayList<>(facts.attempts());
        Collections.reverse(newestFirst);
        for (PaymentGuidance.Attempt attempt : newestFirst.subList(0, Math.min(3, newestFirst.size()))) {
            ObjectNode entry = Results.object();
            Results.put(entry, "status", attempt.status());
            Results.put(entry, "method", StatusWords.paymentMethod(attempt.method()));
            Results.put(entry, "failureReason", attempt.errorDescription());
            Results.put(entry, "at", IndiaTime.format(attempt.at()));
            attempts.add(entry);
        }
        if (!attempts.isEmpty()) {
            node.set("attempts", attempts);
        }
        ArrayNode refunds = Results.array();
        for (PaymentGuidance.Refund refund : facts.refunds()) {
            ObjectNode entry = Results.object();
            Results.put(entry, "amount", Money.scale(refund.amount()));
            Results.put(entry, "status", refund.status());
            Results.put(entry, "reason", StatusWords.refundReason(refund.reason()));
            Results.put(entry, "startedAt", IndiaTime.format(refund.createdAt()));
            Results.put(entry, "completedAt", IndiaTime.format(refund.processedAt()));
            refunds.add(entry);
        }
        if (!refunds.isEmpty()) {
            node.set("refunds", refunds);
        }
        return node;
    }

    /** The situation in a few words, for the UI's tool line ("Payment for GA-…: last attempt failed"). */
    static String situationWords(PaymentGuidance.Situation situation) {
        return switch (situation) {
            case CHECKING_STOCK -> "the store is checking stock";
            case WAITING_FOR_PAYMENT -> "waiting for payment";
            case PAYMENT_FAILED_CAN_RETRY -> "last attempt failed, can try again";
            case CONFIRMING_PAYMENT -> "payment being confirmed";
            case PAYMENT_TIME_OVER -> "the time to pay ran out";
            case PAID -> "paid";
            case REFUND_IN_PROGRESS -> "refund in progress";
            case REFUND_FAILED_RETRYING -> "refund being retried";
            case REFUNDED -> "refunded";
            case PARTIALLY_REFUNDED -> "partly refunded";
            case NOTHING_TO_PAY -> "nothing to pay";
            case UNKNOWN -> situation.name().toLowerCase(Locale.ROOT);
        };
    }
}
