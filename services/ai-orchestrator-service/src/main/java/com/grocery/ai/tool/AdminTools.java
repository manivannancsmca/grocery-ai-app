package com.grocery.ai.tool;

import com.grocery.ai.approval.ApprovalAction;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.link.PageLink;
import com.grocery.ai.link.Pages;
import com.grocery.ai.support.IndiaTime;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import com.grocery.common.security.Roles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code admin-bi} agent's tools (contract §3), all called as the admin through the gateway. Times reach the model
 * as India time, written out ("19 Nov, 2:32 pm IST"): the APIs answer in UTC, which the small model read out as local.
 */
@Component
public class AdminTools {

    /**
     * The onboarding statuses shared by vendors, restaurants and delivery partners (vendor-service VendorStatus,
     * restaurant-service RestaurantStatus, delivery-service PartnerStatus), in the order an answer lists them.
     */
    static final List<String> STAKEHOLDER_STATUSES = List.of("APPROVED", "PENDING_APPROVAL", "SUSPENDED",
            "REJECTED", "DRAFT");

    /** What the statuses mean, sent with the counts so the model explains them correctly. */
    static final String STATUS_MEANING = "APPROVED = approved by an admin and active now; SUSPENDED = approved "
            + "earlier, suspended now; PENDING_APPROVAL = submitted, waiting for an admin decision; REJECTED = an "
            + "admin rejected the submission; DRAFT = registered but not submitted yet.";

    /**
     * The three admin directories of business partners (Phase 2 admin APIs, any admin role may read them). The
     * {@code nameField} / {@code cityField} differ per service.
     */
    enum Directory {
        VENDOR("/api/admin/vendors", "vendors", "businessName", "address.city"),
        RESTAURANT("/api/admin/restaurants", "restaurants", "name", "address.city"),
        DELIVERY_PARTNER("/api/admin/delivery-partners", "deliveryPartners", "fullName", "city");

        final String path;
        final String key;
        final String nameField;
        final String cityField;

        Directory(String path, String key, String nameField, String cityField) {
            this.path = path;
            this.key = key;
            this.nameField = nameField;
            this.cityField = cityField;
        }

        /** "vendors", "restaurants", "delivery partners". */
        String plural() {
            return this == DELIVERY_PARTNER ? "delivery partners" : key;
        }

        static Set<String> names() {
            return Stream.of(values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
        }
    }

    static final Set<String> ANY_ADMIN = Roles.ADMIN_ROLES;
    static final Set<String> REFUND_PROPOSERS = Set.of(Roles.SUPER_ADMIN, Roles.FINANCE_ADMIN, Roles.OPS_ADMIN,
            Roles.SUPPORT);
    static final Set<String> CANCEL_PROPOSERS = Set.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.SUPPORT);
    static final Set<String> SUSPEND_PROPOSERS = Set.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN);

    private final BusinessApi api;

    public AdminTools(BusinessApi api) {
        this.api = api;
    }

    public List<ToolSpec> tools() {
        return List.of(
                ToolSpec.run("getOrderStats", "Read order statistics",
                        "Order KPIs of one day in India time (default today): orders, by status and type, confirmed "
                                + "revenue, average order value.", RiskClass.READ, ANY_ADMIN,
                        ArgSchema.builder().date("date", "The day, yyyy-MM-dd (default today)").build(),
                        this::getOrderStats),
                ToolSpec.run("listOrders", "Listed orders", "Orders, newest first (up to 20), optionally filtered.",
                        RiskClass.READ, ANY_ADMIN,
                        ArgSchema.builder()
                                .enumeration("status", ShoppingTools.ORDER_STATUSES, "Order status")
                                .enumeration("type", Set.of("GROCERY", "RESTAURANT"), "Order type")
                                .date("from", "First day (India time), yyyy-MM-dd")
                                .date("to", "Last day (India time), yyyy-MM-dd")
                                .build(),
                        this::listOrders),
                ToolSpec.run("getOrder", "Looked up an order", "One order by its number, with its payment state.",
                        RiskClass.READ, ANY_ADMIN,
                        ArgSchema.builder().orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M").build(),
                        this::getOrder),
                ToolSpec.run("listStuckOrders", "Checked stuck orders",
                        "Orders that stayed too long in a status, longest overdue first.", RiskClass.READ, ANY_ADMIN,
                        ArgSchema.none(), this::listStuckOrders),
                ToolSpec.run("listPayments", "Listed payments", "Payments, newest first (up to 20).",
                        RiskClass.READ, ANY_ADMIN,
                        ArgSchema.builder()
                                .enumeration("status", Set.of("CREATED", "PENDING", "CAPTURED", "EXPIRED",
                                        "CANCELLED"), "Payment status")
                                .enumeration("refundStatus", Set.of("NONE", "PENDING", "PARTIAL", "FULL"),
                                        "Refund status")
                                .build(),
                        this::listPayments),
                ToolSpec.run("getReconciliationSummary", "Read payment reconciliation",
                        "What payment reconciliation recovered (lost payments found at the provider).",
                        RiskClass.READ, ANY_ADMIN, ArgSchema.none(), this::getReconciliationSummary),
                ToolSpec.run("getStakeholderCounts", "Counted vendors, restaurants and partners",
                        "How many vendors, restaurants and delivery partners the platform has, by onboarding status "
                                + "(APPROVED, PENDING_APPROVAL, SUSPENDED, REJECTED, DRAFT), and how many customers. "
                                + "Use it for questions such as \"how many vendors were approved\", \"how many "
                                + "restaurants joined\" or \"how many approvals are pending\".", RiskClass.READ,
                        ANY_ADMIN,
                        ArgSchema.builder()
                                .enumeration("type", Directory.names(), "VENDOR, RESTAURANT or DELIVERY_PARTNER; "
                                        + "leave out to count all of them and the customers")
                                .build(),
                        this::getStakeholderCounts),
                ToolSpec.run("listStakeholders", "Listed vendors, restaurants or partners",
                        "Vendors, restaurants or delivery partners (up to 10) with their status, city and dates, "
                                + "optionally only one status or matching a name. PENDING_APPROVAL lists the "
                                + "submissions waiting for an admin, oldest first. Gives the ids that "
                                + "suspendVendor and suspendRestaurant need.", RiskClass.READ, ANY_ADMIN,
                        ArgSchema.builder()
                                .requiredEnumeration("type", Directory.names(),
                                        "VENDOR, RESTAURANT or DELIVERY_PARTNER")
                                .enumeration("status", Set.copyOf(STAKEHOLDER_STATUSES), "Only this status")
                                .string("query", false, 1, 100, "Part of the name to look for")
                                .build(),
                        this::listStakeholders),
                ToolSpec.propose("refundOrder", "Prepared a refund",
                        "Propose refunding an order's payment. It does NOT refund: a finance admin approves it "
                                + "(a second admin above the four-eyes limit).", REFUND_PROPOSERS,
                        ArgSchema.builder()
                                .orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M")
                                .decimal("amount", false, "0.01", "100000.00",
                                        "Rupees to refund; leave out to refund everything not yet refunded")
                                .string("note", true, 1, 300, "Why the refund is given")
                                .rationale()
                                .build(),
                        ApprovalAction.REFUND_PAYMENT),
                ToolSpec.propose("cancelOrderAsAdmin", "Prepared an order cancellation",
                        "Propose cancelling an order as an admin. It does NOT cancel: an operations admin approves "
                                + "it.", CANCEL_PROPOSERS,
                        ArgSchema.builder()
                                .orderNumber("orderNumber", "The order number, e.g. GA-7K3F9Q2M")
                                .string("reason", true, 1, 300, "Why the order is cancelled")
                                .rationale()
                                .build(),
                        ApprovalAction.ADMIN_CANCEL_ORDER),
                ToolSpec.propose("suspendVendor", "Prepared a vendor suspension",
                        "Propose suspending a vendor. It does NOT suspend: a second operations admin approves it.",
                        SUSPEND_PROPOSERS,
                        ArgSchema.builder()
                                .uuid("vendorId", true, "The vendor id")
                                .string("reason", true, 1, 300, "Why the vendor is suspended")
                                .rationale()
                                .build(),
                        ApprovalAction.SUSPEND_VENDOR),
                ToolSpec.propose("suspendRestaurant", "Prepared a restaurant suspension",
                        "Propose suspending a restaurant. It does NOT suspend: a second operations admin approves it.",
                        SUSPEND_PROPOSERS,
                        ArgSchema.builder()
                                .uuid("restaurantId", true, "The restaurant id")
                                .string("reason", true, 1, 300, "Why the restaurant is suspended")
                                .rationale()
                                .build(),
                        ApprovalAction.SUSPEND_RESTAURANT));
    }

    private ToolResult getOrderStats(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("date", call.args().date("date"));
        return Results.from(api.get(call.http(), "/api/admin/stats", params), ok -> {
            ObjectNode result = Results.object();
            result.set("stats", Results.pick(ok.body(), "date", "timezone", "orders", "byStatus", "byType",
                    "confirmedRevenue", "averageOrderValue", "currency"));
            return Results.ok(result, Objects.requireNonNullElse(Json.integer(ok.body(), "orders"), 0)
                    + " orders on " + Json.text(ok.body(), "date") + ", confirmed revenue "
                    + Money.inr(Json.decimal(ok.body(), "confirmedRevenue")), ok, Pages.adminReports());
        });
    }

    private ToolResult listOrders(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", call.args().string("status"));
        params.put("type", call.args().string("type"));
        params.put("from", call.args().date("from"));
        params.put("to", call.args().date("to"));
        params.put("size", 20);
        return Results.from(api.get(call.http(), "/api/admin/orders", params), ok -> {
            ArrayNode orders = Results.array();
            for (JsonNode summary : Json.list(ok.body(), "content")) {
                ObjectNode order = Results.pick(summary, "orderNumber", "type", "status", "total", "itemCount",
                        "customerId");
                Results.put(order, "placedAt", IndiaTime.format(Json.text(summary, "createdAt")));
                Results.put(order, "store", Json.text(summary, "store.name"));
                Results.put(order, "paymentStatus", Json.text(summary, "payment.status"));
                orders.add(order);
            }
            ObjectNode result = Results.object();
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    orders.size()));
            result.set("orders", orders);
            return Results.ok(result, orders.size() + " orders listed", ok,
                    Pages.adminOrders(call.args().string("status")));
        });
    }

    private ToolResult getOrder(ToolCallContext call) {
        String number = call.args().string("orderNumber");
        return Results.from(api.adminOrder(call.http(), number), ok -> {
            ObjectNode order = ShoppingTools.order(ok.body());
            JsonNode adminCancellable = Json.at(ok.body(), "adminCancellable");
            if (adminCancellable != null) {
                order.put("adminCancellable", adminCancellable.asBoolean());
            }
            ObjectNode result = Results.object();
            result.set("order", order);
            UUID orderId = Json.uuid(ok.body(), "id");
            return Results.ok(result, "Order " + number + " is " + Json.text(ok.body(), "status"), ok,
                    orderId == null ? Pages.adminOrders(null) : Pages.adminOrder(orderId, number));
        });
    }

    private ToolResult listStuckOrders(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/admin/orders/stuck"), ok -> {
            ArrayNode stuck = Results.array();
            int count = 0;
            if (ok.body() != null && ok.body().isArray()) {
                for (JsonNode item : ok.body()) {
                    count++;
                    if (stuck.size() < 20) {
                        ObjectNode order = Results.pick(item, "orderNumber", "type", "status", "reason",
                                "minutesOverdue");
                        Results.put(order, "since", IndiaTime.format(Json.text(item, "since")));
                        Results.put(order, "store", Json.text(item, "store.name"));
                        stuck.add(order);
                    }
                }
            }
            ObjectNode result = Results.object();
            result.put("count", count);
            result.set("stuckOrders", stuck);
            return Results.ok(result, count == 0 ? "No stuck orders" : count + " stuck orders", ok,
                    Pages.stuckOrders());
        });
    }

    private ToolResult listPayments(ToolCallContext call) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", call.args().string("status"));
        params.put("refundStatus", call.args().string("refundStatus"));
        params.put("size", 20);
        return Results.from(api.get(call.http(), "/api/admin/payments", params), ok -> {
            ArrayNode payments = Results.array();
            for (JsonNode payment : Json.list(ok.body(), "content")) {
                ObjectNode entry = Results.pick(payment, "orderNumber", "status", "amount", "refundedAmount",
                        "refundStatus", "method");
                Results.put(entry, "createdAt", IndiaTime.format(Json.text(payment, "createdAt")));
                payments.add(entry);
            }
            ObjectNode result = Results.object();
            result.put("totalElements", Objects.requireNonNullElse(Json.integer(ok.body(), "totalElements"),
                    payments.size()));
            result.set("payments", payments);
            return Results.ok(result, payments.size() + " payments listed", ok,
                    Pages.payments(call.args().string("status")));
        });
    }

    private ToolResult getReconciliationSummary(ToolCallContext call) {
        return Results.from(api.get(call.http(), "/api/admin/payments/reconciliation"), ok -> {
            ObjectNode reconciliation = Results.pick(ok.body(), "checked", "recovered", "failures");
            Results.put(reconciliation, "lastRunAt", IndiaTime.format(Json.text(ok.body(), "lastRunAt")));
            ArrayNode recent = Results.array();
            for (JsonNode item : Json.list(ok.body(), "lastRecovered")) {
                if (recent.size() < 5) {
                    ObjectNode recovered = Results.pick(item, "orderNumber", "outcome");
                    Results.put(recovered, "at", IndiaTime.format(Json.text(item, "at")));
                    recent.add(recovered);
                }
            }
            reconciliation.set("lastRecovered", recent);
            ObjectNode result = Results.object();
            result.set("reconciliation", reconciliation);
            return Results.ok(result, "Reconciliation: " + Objects.requireNonNullElse(Json.integer(ok.body(),
                    "recovered"), 0) + " recovered, " + Objects.requireNonNullElse(Json.integer(ok.body(),
                    "failures"), 0) + " failures", ok, Pages.reconciliation());
        });
    }

    // ---- business partners: vendors, restaurants, delivery partners ------------------------------------------------

    /**
     * Counts per onboarding status, read from the admin directories as the admin ({@code ?status=X&size=1}, the
     * page's {@code totalElements}): exact numbers from the services, never estimated. Without a type, all three
     * directories and the customer count (one call per status and directory).
     */
    private ToolResult getStakeholderCounts(ToolCallContext call) {
        String type = call.args().string("type");
        List<Directory> directories = type == null ? List.of(Directory.values()) : List.of(Directory.valueOf(type));
        ObjectNode result = Results.object();
        List<String> summary = new ArrayList<>();
        List<PageLink> links = new ArrayList<>();
        int lastStatus = 200;
        for (Directory directory : directories) {
            ObjectNode byStatus = Results.object();
            long total = 0;
            for (String status : STAKEHOLDER_STATUSES) {
                Fetch fetch = api.get(call.http(), directory.path, Map.of("status", status, "size", 1));
                if (!(fetch instanceof Fetch.Ok ok)) {
                    return failed(fetch);
                }
                long count = totalElements(ok.body());
                byStatus.put(status, count);
                total += count;
                lastStatus = ok.httpStatus();
            }
            ObjectNode counts = Results.object();
            counts.put("total", total);
            counts.set("byStatus", byStatus);
            result.set(directory.key, counts);
            summary.add(byStatus.path("APPROVED").asLong() + " approved " + directory.plural() + " ("
                    + byStatus.path("PENDING_APPROVAL").asLong() + " pending)");
            links.add(Pages.stakeholders(directory.name(), null, null));
            if (byStatus.path("PENDING_APPROVAL").asLong() > 0 && links.size() < 4) {
                links.add(Pages.onboardingQueue(directory.name()));
            }
        }
        if (type == null) {
            // The customer count is extra information: when it cannot be read the partner counts still answer.
            if (api.get(call.http(), "/api/admin/customers", Map.of("size", 1)) instanceof Fetch.Ok customers) {
                result.put("customers", totalElements(customers.body()));
                summary.add(totalElements(customers.body()) + " customers");
            } else {
                result.put("customers", "unavailable right now");
            }
        }
        result.put("statusMeaning", STATUS_MEANING);
        return new ToolResult.Ok(result, String.join(", ", summary), lastStatus,
                links.stream().distinct().limit(4).toList());
    }

    private ToolResult listStakeholders(ToolCallContext call) {
        Directory directory = Directory.valueOf(call.args().string("type"));
        String status = call.args().string("status");
        String query = call.args().string("query");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status);
        params.put("query", query);
        params.put("size", 10);
        return Results.from(api.get(call.http(), directory.path, params), ok -> {
            ArrayNode entries = Results.array();
            for (JsonNode item : Json.list(ok.body(), "content")) {
                ObjectNode entry = Results.object();
                Results.put(entry, "id", Json.text(item, "id"));
                Results.put(entry, "name", Json.text(item, directory.nameField));
                Results.put(entry, "status", Json.text(item, "status"));
                Results.put(entry, "city", Json.text(item, directory.cityField));
                Results.put(entry, "submittedAt", IndiaTime.format(Json.text(item, "submittedAt")));
                Results.put(entry, "approvedAt", IndiaTime.format(Json.text(item, "approvedAt")));
                Results.put(entry, "statusReason", Results.truncate(Json.text(item, "statusReason"), 200));
                entries.add(entry);
            }
            long total = totalElements(ok.body());
            ObjectNode result = Results.object();
            result.put("type", directory.name());
            Results.put(result, "status", status);
            Results.put(result, "query", query);
            result.put("totalFound", total);
            result.put("listed", entries.size());
            result.set(directory.key, entries);
            result.put("statusMeaning", STATUS_MEANING);
            String what = (status == null ? "" : status.toLowerCase(Locale.ROOT).replace('_', ' ') + " ")
                    + directory.plural();
            String summary = total == 0 ? "No " + what + " found"
                    : "Found " + total + " " + what + (total > entries.size() ? " (showing " + entries.size() + ")"
                    : "");
            PageLink directoryPage = Pages.stakeholders(directory.name(), status, query);
            return "PENDING_APPROVAL".equals(status)
                    ? Results.ok(result, summary, ok, Pages.onboardingQueue(directory.name()), directoryPage)
                    : Results.ok(result, summary, ok, directoryPage);
        });
    }

    private static long totalElements(JsonNode page) {
        JsonNode total = Json.at(page, "totalElements");
        return total == null ? Json.list(page, "content").size() : total.asLong();
    }

    private static ToolResult failed(Fetch fetch) {
        Fetch.Problem problem = (Fetch.Problem) fetch;
        return new ToolResult.Failed(problem.code(), problem.message(), problem.httpStatus());
    }
}
