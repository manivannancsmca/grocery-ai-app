package com.grocery.reporting.audit;

import com.grocery.reporting.audit.AuditEntry.KnownUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which events are audit entries, and what each one says (README "Audit trail"): the events that name who acted (an
 * admin's decision, a store's or a customer's action, the system's), the sign-in and account audit of identity-service
 * ({@code SecurityEventRecorded}) and the platform settings changes ({@code PlatformSettingChanged}). Every other event
 * is not an audit entry. Pure: the payload in, the entry out ({@link AuditService} writes it).
 * <p>
 * identity-service's {@code UserSuspended}, {@code UserReactivated}, {@code UserRegistered} and {@code PasswordChanged}
 * are left out on purpose: the same actions arrive as {@code SecurityEventRecorded} rows, with the client's address.
 */
public final class AuditMapper {

    /** The topics the audit consumer reads (one producer each). */
    public static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("identity.events", "identity-service"),
            Map.entry("vendor.events", "vendor-service"),
            Map.entry("restaurant.events", "restaurant-service"),
            Map.entry("delivery.events", "delivery-service"),
            Map.entry("pricing.events", "pricing-service"),
            Map.entry("inventory.events", "inventory-service"),
            Map.entry("order.events", "order-service"),
            Map.entry("payment.events", "payment-service"),
            Map.entry("review.events", "review-service"),
            Map.entry("ai.events", "ai-orchestrator-service"));

    /** Every event type that becomes an audit entry. */
    public static final Set<String> AUDITED = Set.of(
            "SecurityEventRecorded", "PlatformSettingChanged",
            "VendorSubmitted", "VendorApproved", "VendorRejected", "VendorSuspended", "VendorReinstated",
            "RestaurantSubmitted", "RestaurantApproved", "RestaurantRejected", "RestaurantSuspended",
            "RestaurantReinstated",
            "DeliveryPartnerSubmitted", "DeliveryPartnerApproved", "DeliveryPartnerRejected",
            "DeliveryPartnerSuspended", "DeliveryPartnerReinstated", "DeliveryPartnerUnassigned",
            "VendorOrderRejected", "RestaurantOrderRejected",
            "PriceChangeRequested", "PriceChangeApproved", "PriceChangeRejected", "PriceChangeCancelled",
            "StockAdjusted",
            "OrderCancelled", "ReturnApproved", "ReturnRejected",
            "RefundCompleted", "RefundFailed",
            "ReviewModerated",
            "ApprovalRequested", "ApprovalDecided", "ApprovalExecuted", "ApprovalFailed");

    /** Sign-in events where whoever acted is not known to be the account holder (no one signed in yet). */
    private static final Set<String> ANONYMOUS_TYPES = Set.of("LOGIN_FAILURE", "LOGIN_LOCKED", "LOGIN_SUSPENDED",
            "REFRESH_REUSE_DETECTED", "PASSWORD_RESET_REQUESTED", "PASSWORD_RESET_IGNORED", "PASSWORD_RESET_FAILURE");
    private static final Set<String> ACCOUNT_TYPES = Set.of("REGISTERED", "USER_SUSPENDED", "USER_REACTIVATED",
            "ADMIN_CREATED");
    private static final Map<String, String> SECURITY_SUMMARIES = Map.ofEntries(
            Map.entry("REGISTERED", "Account registered"),
            Map.entry("LOGIN_SUCCESS", "Signed in"),
            Map.entry("LOGIN_FAILURE", "Sign-in failed"),
            Map.entry("LOGIN_LOCKED", "Sign-in refused: account locked after failed attempts"),
            Map.entry("LOGIN_SUSPENDED", "Sign-in refused: account suspended"),
            Map.entry("REFRESH_REUSE_DETECTED", "A used session token came back: the session was ended"),
            Map.entry("LOGOUT", "Signed out"),
            Map.entry("USER_SUSPENDED", "Account suspended"),
            Map.entry("USER_REACTIVATED", "Account reactivated"),
            Map.entry("ADMIN_CREATED", "Admin account created"),
            Map.entry("PASSWORD_CHANGED", "Password changed"),
            Map.entry("PASSWORD_CHANGE_FAILURE", "Password change failed"),
            Map.entry("PASSWORD_RESET_REQUESTED", "Password reset requested"),
            Map.entry("PASSWORD_RESET_IGNORED", "Password reset request ignored"),
            Map.entry("PASSWORD_RESET", "Password reset with an e-mailed link"),
            Map.entry("PASSWORD_RESET_FAILURE", "Password reset failed"));

    /** An entry, and what it tells about users (for the actor column). */
    public record Mapped(AuditEntry entry, List<KnownUser> users) {
    }

    /** The target labels the events do not carry, looked up in the facts (best effort; null when unknown). */
    public interface Labels {
        String orderNumber(UUID orderId);

        String itemName(UUID itemId);
    }

    private final JsonMapper json;
    private final Labels labels;

    public AuditMapper(JsonMapper json, Labels labels) {
        this.json = json;
        this.labels = labels;
    }

    /**
     * The audit entry of an event, or empty when it is not an audit entry.
     *
     * @param headerTime the event's {@code occurredAt} header (or the processing time): used when the payload has no
     *                   time of its own
     */
    public Optional<Mapped> map(String topic, String eventType, UUID eventId, JsonNode p, Instant headerTime) {
        String source = SOURCES.get(topic);
        if (source == null || !AUDITED.contains(eventType)) {
            return Optional.empty();
        }
        Builder b = new Builder(eventId, eventType, source, headerTime);
        switch (eventType) {
            case "SecurityEventRecorded" -> security(b, p);
            case "PlatformSettingChanged" -> setting(b, p);
            case "VendorSubmitted", "VendorApproved", "VendorRejected", "VendorSuspended", "VendorReinstated" ->
                    lifecycle(b, p, eventType.substring("Vendor".length()), "STORES", "VENDOR", "vendorId",
                            "businessName", "ownerUserId", "Vendor");
            case "RestaurantSubmitted", "RestaurantApproved", "RestaurantRejected", "RestaurantSuspended",
                 "RestaurantReinstated" ->
                    lifecycle(b, p, eventType.substring("Restaurant".length()), "STORES", "RESTAURANT",
                            "restaurantId", "name", "ownerUserId", "Restaurant");
            case "DeliveryPartnerSubmitted", "DeliveryPartnerApproved", "DeliveryPartnerRejected",
                 "DeliveryPartnerSuspended", "DeliveryPartnerReinstated" ->
                    lifecycle(b, p, eventType.substring("DeliveryPartner".length()), "PARTNERS", "PARTNER",
                            "partnerId", "fullName", "partnerId", "Delivery partner");
            case "DeliveryPartnerUnassigned" -> b.category("DELIVERIES").action("PARTNER_UNASSIGNED")
                    .actor(uuid(p, "actorUserId"), uuid(p, "actorUserId") == null ? "SYSTEM" : "ADMIN")
                    .target("ORDER", text(p, "orderId"), text(p, "orderNumber"))
                    .summary("Delivery partner taken off the order")
                    .detail(join(text(p, "reason"), "partner " + text(p, "partnerId")))
                    .time(instant(p, "unassignedAt"));
            case "VendorOrderRejected", "RestaurantOrderRejected" -> {
                boolean auto = p.path("auto").asBoolean(false) || uuid(p, "actorUserId") == null;
                b.category("ORDERS").action("STORE_REJECTED_ORDER")
                        .actor(uuid(p, "actorUserId"), auto ? "SYSTEM" : "STORE")
                        .target("ORDER", text(p, "orderId"), text(p, "orderNumber"))
                        .summary(auto ? "Order rejected for the store (no answer in time)" : "Store rejected the order")
                        .detail(join(words(text(p, "reason")), text(p, "note")))
                        .time(instant(p, "rejectedAt"));
            }
            case "PriceChangeRequested" -> b.category("PRICES").action("PRICE_CHANGE_REQUESTED")
                    .actor(uuid(p, "requestedBy"), "STORE")
                    .target("PRODUCT", text(p, "productId"), text(p, "productName"))
                    .summary("Price change requested (needs approval)")
                    .detail("MRP " + money(p, "oldMrp") + " → " + money(p, "newMrp") + ", selling price "
                            + money(p, "oldSellingPrice") + " → " + money(p, "newSellingPrice") + " ("
                            + percent(p, "changePercent") + ")");
            case "PriceChangeApproved" -> price(b, p, "PRICE_CHANGE_APPROVED", "approvedBy", "ADMIN",
                    "Price change approved", null);
            case "PriceChangeRejected" -> price(b, p, "PRICE_CHANGE_REJECTED", "rejectedBy", "ADMIN",
                    "Price change rejected", text(p, "reason"));
            case "PriceChangeCancelled" -> price(b, p, "PRICE_CHANGE_CANCELLED", "cancelledBy",
                    uuid(p, "cancelledBy") == null ? "SYSTEM" : "STORE", "Price change request withdrawn",
                    words(text(p, "reason")));
            case "StockAdjusted" -> {
                long delta = p.path("delta").asLong(0);
                b.category("STOCK").action("STOCK_ADJUSTED")
                        .actor(uuid(p, "actorUserId"), uuid(p, "actorUserId") == null ? "SYSTEM" : "STORE")
                        .target("PRODUCT", text(p, "productId"), labels.itemName(uuid(p, "productId")))
                        .summary("Stock adjusted (" + (delta > 0 ? "+" : "") + delta + ")")
                        .detail(join(words(text(p, "type")), "on hand " + p.path("onHand").asLong(0),
                                text(p, "reason")));
            }
            case "OrderCancelled" -> {
                String by = text(p, "cancelledBy");
                String role = by == null ? "SYSTEM" : switch (by) {
                    case "CUSTOMER" -> "CUSTOMER";
                    case "ADMIN" -> "ADMIN";
                    case "STORE" -> "STORE";
                    default -> "SYSTEM";
                };
                b.category("ORDERS").action("ORDER_CANCELLED").actor(uuid(p, "actorUserId"), role)
                        .target("ORDER", text(p, "orderId"), text(p, "orderNumber"))
                        .summary("Order cancelled by " + role.toLowerCase(Locale.ROOT))
                        .detail(join(words(text(p, "reason")), text(p, "note")))
                        .time(instant(p, "cancelledAt"));
            }
            case "ReturnApproved", "ReturnRejected" -> {
                boolean approved = "ReturnApproved".equals(eventType);
                b.category("ORDERS").action(approved ? "RETURN_APPROVED" : "RETURN_REJECTED")
                        .actor(uuid(p, "decidedBy"), "ADMIN".equals(text(p, "decidedByRole")) ? "ADMIN" : "STORE")
                        .target("ORDER", text(p, "orderId"), text(p, "orderNumber"))
                        .summary("Return " + text(p, "returnNumber") + (approved ? " approved" : " rejected"))
                        .detail(approved ? "refund " + money(p, "refundAmount")
                                + (p.path("restock").asBoolean(false) ? ", items restocked" : "")
                                : text(p, "comment"))
                        .time(instant(p, "decidedAt"));
            }
            case "RefundCompleted", "RefundFailed" -> {
                boolean done = "RefundCompleted".equals(eventType);
                b.category("PAYMENTS").action(done ? "REFUND_COMPLETED" : "REFUND_FAILED").actor(null, "SYSTEM")
                        .target("ORDER", text(p, "orderId"), labels.orderNumber(uuid(p, "orderId")))
                        .summary("Refund of " + money(p, "amount") + (done ? " completed" : " failed"))
                        .detail(join(words(text(p, "reason")), done ? "total refunded " + money(p, "totalRefunded")
                                : text(p, "errorCode")));
            }
            case "ReviewModerated" -> b.category("REVIEWS").action("REVIEW_MODERATED")
                    .actor(uuid(p, "moderatorId"), "ADMIN")
                    .target("REVIEW", text(p, "reviewId"), null)
                    .summary("Review " + words(text(p, "decision")))
                    .detail(text(p, "reason"))
                    .time(instant(p, "moderatedAt"));
            case "ApprovalRequested", "ApprovalDecided", "ApprovalExecuted", "ApprovalFailed" -> approval(b, p);
            default -> {
                return Optional.empty();
            }
        }
        return Optional.of(new Mapped(b.build(), b.users));
    }

    // ---------------------------------------------------------------- the event families

    private void security(Builder b, JsonNode p) {
        String type = text(p, "type");
        UUID userId = uuid(p, "userId");
        UUID actor = uuid(p, "actorUserId");
        String email = text(p, "email");
        Instant at = instant(p, "occurredAt");
        if (actor != null) {
            b.actor(actor, "ADMIN");
        } else if (userId == null || ANONYMOUS_TYPES.contains(type)) {
            b.actor(null, "ANONYMOUS");
        } else {
            b.actor(userId, "USER");
        }
        b.category(ACCOUNT_TYPES.contains(type) ? "ACCOUNTS" : "SECURITY")
                .action(type)
                .target("USER", userId == null ? null : userId.toString(), email)
                .summary(SECURITY_SUMMARIES.getOrDefault(type, capitalize(words(type))))
                .detail(text(p, "detail"))
                .client(text(p, "ipAddress"), text(p, "userAgent"))
                .time(at);
        if (userId != null && email != null) {
            b.users.add(new KnownUser(userId, email, null, at == null ? b.time : at));
        }
    }

    private void setting(Builder b, JsonNode p) {
        String area = text(p, "area");
        String field = text(p, "field");
        UUID admin = uuid(p, "changedBy");
        Instant at = instant(p, "changedAt");
        b.category("SETTINGS").action("SETTING_CHANGED").actor(admin, "ADMIN", text(p, "changedByName"))
                .target("SETTING", text(p, "settingKey"), area + " · " + field)
                .summary("Setting " + field + " (" + area + ") changed")
                .detail(value(text(p, "oldValue")) + " → " + value(text(p, "newValue")))
                .time(at);
        if (admin != null && text(p, "changedByName") != null) {
            b.users.add(new KnownUser(admin, null, text(p, "changedByName"), at == null ? b.time : at));
        }
    }

    /** Registered / Submitted / Approved / Rejected / Suspended / Reinstated of a vendor, restaurant or partner. */
    private void lifecycle(Builder b, JsonNode p, String step, String category, String targetType, String idField,
                           String nameField, String ownerField, String what) {
        boolean submitted = "Submitted".equals(step);
        UUID actor = submitted ? uuid(p, ownerField) : uuid(p, "actorUserId");
        b.category(category).action(targetType + "_" + step.toUpperCase(Locale.ROOT))
                .actor(actor, submitted ? ("PARTNER".equals(targetType) ? "PARTNER" : "STORE")
                        : actor == null ? "SYSTEM" : "ADMIN")
                .target(targetType, text(p, idField), text(p, nameField))
                .summary(what + (submitted ? " submitted for review" : " " + step.toLowerCase(Locale.ROOT)))
                .detail(text(p, "reason"));
    }

    private void price(Builder b, JsonNode p, String action, String actorField, String role, String summary,
                       String detail) {
        b.category("PRICES").action(action).actor(uuid(p, actorField), role)
                .target("PRODUCT", text(p, "productId"), labels.itemName(uuid(p, "productId")))
                .summary(summary)
                .detail(join(detail, "request " + text(p, "requestId")));
    }

    private void approval(Builder b, JsonNode p) {
        String title = text(p, "title");
        String action = words(text(p, "action"));
        b.category("AI").target("APPROVAL", text(p, "approvalId"), title);
        switch (b.eventType) {
            case "ApprovalRequested" -> b.action("APPROVAL_REQUESTED").actor(uuid(p, "requestedBy"), "USER")
                    .summary("The " + text(p, "agent") + " assistant proposed: " + action)
                    .detail(join(p.hasNonNull("amount") ? money(p, "amount") : null,
                            p.path("fourEyes").asBoolean(false) ? "needs two admins" : null))
                    .time(instant(p, "requestedAt"));
            case "ApprovalDecided" -> {
                String decision = text(p, "decision");
                UUID decidedBy = uuid(p, "decidedBy");
                b.action("APPROVAL_" + decision).actor(decidedBy, decidedBy == null ? "SYSTEM" : "USER")
                        .summary("Approval " + words(decision) + ": " + action)
                        .detail(text(p, "comment"))
                        .time(instant(p, "decidedAt"));
            }
            case "ApprovalExecuted" -> b.action("APPROVAL_EXECUTED").actor(uuid(p, "decidedBy"), "USER")
                    .summary("Approved action done: " + action)
                    .detail(text(p, "resultReference"))
                    .time(instant(p, "executedAt"));
            default -> b.action("APPROVAL_FAILED").actor(uuid(p, "decidedBy"), "USER")
                    .summary("Approved action failed: " + action)
                    .detail(join(text(p, "errorCode"), text(p, "errorMessage")))
                    .time(instant(p, "failedAt"));
        }
    }

    // ---------------------------------------------------------------- reading the payload

    private static String text(JsonNode p, String field) {
        JsonNode value = p == null ? null : p.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }

    private static UUID uuid(JsonNode p, String field) {
        String text = text(p, field);
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private Instant instant(JsonNode p, String field) {
        JsonNode value = p == null ? null : p.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return json.convertValue(value, Instant.class);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String money(JsonNode p, String field) {
        JsonNode value = p == null ? null : p.get(field);
        if (value == null || value.isNull()) {
            return "?";
        }
        BigDecimal amount = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString());
        return "₹" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(JsonNode p, String field) {
        JsonNode value = p == null ? null : p.get(field);
        if (value == null || value.isNull()) {
            return "?";
        }
        BigDecimal change = value.isNumber() ? value.decimalValue() : new BigDecimal(value.asString());
        return (change.signum() > 0 ? "+" : "") + change.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** A stored setting text for people: null is the default. */
    private static String value(String text) {
        return text == null ? "default" : text;
    }

    /** "ADMIN_CANCELLED" → "admin cancelled". */
    private static String words(String code) {
        return code == null ? null : code.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String capitalize(String text) {
        return text == null || text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** The non-null parts, joined with " · "; null when there are none. */
    private static String join(String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part);
            }
        }
        return present.isEmpty() ? null : String.join(" · ", present);
    }

    /** Collects one entry's columns. */
    private static final class Builder {
        private final UUID id;
        private final String eventType;
        private final String source;
        private Instant time;
        private String category;
        private String action;
        private UUID actorId;
        private String actorRole = "SYSTEM";
        private String actorName;
        private String targetType;
        private String targetId;
        private String targetLabel;
        private String summary;
        private String detail;
        private String ipAddress;
        private String userAgent;
        private final List<KnownUser> users = new ArrayList<>();

        Builder(UUID id, String eventType, String source, Instant headerTime) {
            this.id = id;
            this.eventType = eventType;
            this.source = source;
            this.time = headerTime;
        }

        Builder category(String value) {
            category = value;
            return this;
        }

        Builder action(String value) {
            action = value;
            return this;
        }

        Builder actor(UUID id, String role) {
            return actor(id, role, null);
        }

        Builder actor(UUID id, String role, String name) {
            actorId = id;
            actorRole = role;
            actorName = name;
            return this;
        }

        Builder target(String type, String id, String label) {
            targetType = type;
            targetId = id;
            targetLabel = label;
            return this;
        }

        Builder summary(String value) {
            summary = value;
            return this;
        }

        Builder detail(String value) {
            detail = value;
            return this;
        }

        Builder client(String ip, String agent) {
            ipAddress = ip;
            userAgent = agent;
            return this;
        }

        /** The event's own time, when it has one (else the header's). */
        Builder time(Instant value) {
            if (value != null) {
                time = value;
            }
            return this;
        }

        AuditEntry build() {
            return new AuditEntry(id, time, category, action, source, actorId, actorRole, actorName, targetType,
                    targetId, cut(targetLabel, 255), cut(summary, 500), cut(detail, 1000), cut(ipAddress, 64),
                    cut(userAgent, 255), eventType);
        }

        private static String cut(String text, int max) {
            return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
        }
    }
}
