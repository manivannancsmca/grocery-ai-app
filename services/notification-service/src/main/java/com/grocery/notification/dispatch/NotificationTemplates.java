package com.grocery.notification.dispatch;

import com.grocery.notification.entity.AccountKind;
import com.grocery.notification.entity.NotificationType;
import com.grocery.notification.entity.ReviewTarget;
import com.grocery.notification.entity.StoreType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every title, body and link of contract §4 (Phase 7), §6 (Phase 8), §7 (Phase 9), Phase 10 §4, Phase 11 §8 and Phase
 * 12 §1 / §3 / §6 in one place. Money is formatted {@code ₹1,234.50} ({@link Money}), times shown to people are India time ({@code HH:mm}); a
 * missing name falls back to a neutral word ("The store", "a product", "A vendor"). {@code data} carries the ids the
 * app needs to refresh the right screens (contract §3): {@code orderId} + {@code orderNumber} on every ORDER and
 * PAYMENT notification; {@code kind} on APPROVAL_REQUESTED, PRICE_APPROVAL_REQUESTED and every ACCOUNT_*;
 * {@code productId} (+ {@code requestId} for price changes) on STOCK and PRICING notifications; {@code approvalId} +
 * {@code action} on every AI_* notification.
 */
public final class NotificationTemplates {

    static final String DOT = " · ";        // ·
    static final String DASH = " — ";       // —
    static final String ARROW = " → ";      // →
    /** Times shown to people are India time (contract Phase 8 §6: "accept by HH:mm"). */
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm").withZone(INDIA);
    /** The delivery partner's home in the React app (offers, the active delivery). */
    static final String PARTNER_HOME = "/delivery";

    private NotificationTemplates() {
    }

    // ---------------------------------------------------------------- orders

    /** Customer, OrderConfirmed. */
    public static NotificationDraft orderConfirmed(UUID orderId, String orderNumber, BigDecimal total,
                                                   String storeName) {
        String paid = total == null ? "Paid." : "Paid " + Money.inr(total) + ".";
        return draft(NotificationType.ORDER_CONFIRMED, "Order " + orderNumber + " confirmed",
                paid + " " + nameOr(storeName, "The store") + " will start preparing it soon.",
                orderLink(orderId), data("orderId", orderId, "orderNumber", orderNumber));
    }

    /**
     * Store owner, VendorOrderReceived / RestaurantOrderReceived (Phase 8; no longer from OrderConfirmed):
     * "{itemCount} items · {total} · accept by {HH:mm India time}", link = the store's order board, {@code data} with
     * {@code acceptBy}. {@code itemCount} = the ordered quantity over all lines.
     */
    public static NotificationDraft newOrder(UUID orderId, String orderNumber, Integer itemCount, BigDecimal total,
                                             Instant acceptBy, StoreType storeType) {
        String items = itemCount == null ? null : itemCount + (itemCount == 1 ? " item" : " items");
        String deadline = acceptBy == null ? null : "accept by " + HOUR_MINUTE.format(acceptBy);
        return draft(NotificationType.NEW_ORDER, "New order " + orderNumber,
                joinNonNull(DOT, items, Money.inr(total), deadline), storeType.board(),
                data("orderId", orderId, "orderNumber", orderNumber, "acceptBy",
                        acceptBy == null ? null : acceptBy.toString()));
    }

    /** Customer, OrderAccepted (Phase 8). */
    public static NotificationDraft orderAccepted(UUID orderId, String orderNumber, String storeName,
                                                  Integer readyInMinutes) {
        String body = readyInMinutes == null
                ? "It will be ready soon."
                : "Ready in about " + readyInMinutes + " min.";
        return draft(NotificationType.ORDER_ACCEPTED,
                nameOr(storeName, "The store") + " accepted order " + orderNumber, body, orderLink(orderId),
                data("orderId", orderId, "orderNumber", orderNumber));
    }

    /** Customer, OrderReadyForPickup (Phase 8): the wording depends on the store type. */
    public static NotificationDraft orderReady(UUID orderId, String orderNumber, StoreType storeType) {
        String body = storeType == StoreType.RESTAURANT
                ? "Your food is ready and waiting for a delivery partner."
                : storeType == StoreType.VENDOR
                ? "Packed and waiting for a delivery partner."
                : "Your order is ready and waiting for a delivery partner.";
        return draft(NotificationType.ORDER_READY, "Order " + orderNumber + " is ready", body, orderLink(orderId),
                data("orderId", orderId, "orderNumber", orderNumber));
    }

    /** Store owner, …OrderRejected with {@code auto: true} (Phase 8). */
    public static NotificationDraft orderAutoRejected(UUID orderId, String orderNumber, StoreType storeType) {
        return draft(NotificationType.ORDER_AUTO_REJECTED, "Order " + orderNumber + " was auto-rejected",
                "It was not accepted in time. The customer has been refunded.", storeType.board(),
                data("orderId", orderId, "orderNumber", orderNumber));
    }

    /**
     * The customer-facing label of a store rejection reason (Phase 8 §6): OUT_OF_STOCK "items out of stock",
     * STORE_CLOSED "store closed", TOO_BUSY "too busy right now", OTHER "" ; null for anything else.
     */
    static String rejectionLabel(String reason) {
        return switch (reason == null ? "" : reason) {
            case "OUT_OF_STOCK" -> "items out of stock";
            case "STORE_CLOSED" -> "store closed";
            case "TOO_BUSY" -> "too busy right now";
            case "OTHER" -> "";
            default -> null;
        };
    }

    /**
     * "({reason label}{: note})" of a STORE_REJECTED cancellation, from the order's cancellation note, which
     * order-service writes as "&lt;reason&gt;: &lt;note&gt;" or just "&lt;reason&gt;". An empty label (OTHER) leaves just the
     * note; nothing at all → "". A note that does not start with a known reason is shown as it is.
     */
    static String storeRejectionDetail(String cancellationNote) {
        if (cancellationNote == null || cancellationNote.isBlank()) {
            return "";
        }
        String text = cancellationNote.strip();
        int colon = text.indexOf(':');
        String code = colon < 0 ? text : text.substring(0, colon).strip();
        String note = colon < 0 ? "" : text.substring(colon + 1).strip();
        String label = rejectionLabel(code);
        if (label == null) {
            return " (" + text + ")";                       // not "<reason>: <note>": show it unchanged
        }
        String detail = label.isEmpty() ? note : note.isEmpty() ? label : label + ": " + note;
        return detail.isEmpty() ? "" : " (" + detail + ")";
    }

    /** Customer, OrderRejected. */
    public static NotificationDraft orderRejected(UUID orderId, String orderNumber) {
        return draft(NotificationType.ORDER_REJECTED, "Order " + orderNumber + " could not be placed",
                "Some items ran out of stock. You have not been charged.", orderLink(orderId),
                data("orderId", orderId, "orderNumber", orderNumber));
    }

    /** Customer, OrderCancelled, without the store's name (see the full variant). */
    public static NotificationDraft orderCancelledForCustomer(UUID orderId, String orderNumber, String reason,
                                                              String note, String previousStatus) {
        return orderCancelledForCustomer(orderId, orderNumber, reason, note, previousStatus, null);
    }

    /**
     * Customer, OrderCancelled: the reason's sentence, + the refund sentence when the order had been paid (previous
     * status CONFIRMED or later). The store's decisions (Phase 8) already say the refund is on its way:
     * STORE_REJECTED "{storeName} could not accept your order ({reason label}{: note}). A full refund is on its way.",
     * STORE_NO_RESPONSE "The store did not respond in time. A full refund is on its way."
     */
    public static NotificationDraft orderCancelledForCustomer(UUID orderId, String orderNumber, String reason,
                                                              String note, String previousStatus, String storeName) {
        String body = switch (reason == null ? "" : reason) {
            case "PAYMENT_TIMEOUT" -> "Payment was not completed in time.";
            case "CUSTOMER_CANCELLED" -> "You cancelled this order.";
            case "ADMIN_CANCELLED" -> "Cancelled by our support team."
                    + (note == null || note.isBlank() ? "" : " " + note.strip());
            case "RESERVATION_EXPIRED" -> "The items could not be held any longer.";
            case "STORE_REJECTED" -> nameOr(storeName, "The store") + " could not accept your order"
                    + storeRejectionDetail(note) + ".";
            case "STORE_NO_RESPONSE" -> "The store did not respond in time.";
            default -> "This order was cancelled.";
        };
        if (wasPaid(previousStatus) || "STORE_REJECTED".equals(reason) || "STORE_NO_RESPONSE".equals(reason)) {
            body += " A full refund is on its way.";
        }
        return draft(NotificationType.ORDER_CANCELLED, "Order " + orderNumber + " cancelled", body,
                orderLink(orderId), data("orderId", orderId, "orderNumber", orderNumber, "reason", reason));
    }

    /**
     * The order had been paid before it was cancelled, so the store had it (Phase 7: CONFIRMED; Phase 8 adds
     * ACCEPTED, PREPARING, READY_FOR_PICKUP).
     */
    public static boolean wasPaid(String previousStatus) {
        return previousStatus != null && switch (previousStatus) {
            case "CONFIRMED", "ACCEPTED", "PREPARING", "READY_FOR_PICKUP" -> true;
            default -> false;
        };
    }

    /** Store owner, OrderCancelled after confirmation. */
    public static NotificationDraft orderCancelledForStore(UUID orderId, String orderNumber, StoreType storeType) {
        return draft(NotificationType.ORDER_CANCELLED, "Order " + orderNumber + " cancelled",
                "The customer's order was cancelled; do not prepare it.", storeType.home(),
                data("orderId", orderId, "orderNumber", orderNumber));
    }

    // ---------------------------------------------------------------- delivery (Phase 9)

    /**
     * Partner, DeliveryOfferCreated: "New delivery from {store}" · "{distanceKm} km away · respond within {s} s" ·
     * {@code /delivery}. Only what the partner's OfferView shows (no customer details): {@code data} = orderId,
     * orderNumber, deliveryId, offerId. {@code distanceKm} with one decimal.
     */
    public static NotificationDraft deliveryOffer(UUID orderId, String orderNumber, UUID deliveryId, UUID offerId,
                                                  String storeName, BigDecimal distanceKm, long secondsToRespond) {
        String distance = distanceKm.setScale(1, RoundingMode.HALF_UP).toPlainString();
        return draft(NotificationType.DELIVERY_OFFER, "New delivery from " + nameOr(storeName, "a store"),
                distance + " km away" + DOT + "respond within " + secondsToRespond + " s", PARTNER_HOME,
                data("orderId", orderId, "orderNumber", orderNumber, "deliveryId", deliveryId, "offerId", offerId));
    }

    /** Customer, DeliveryPartnerAssigned: "{partnerFirstName} will deliver order {no}" · {@code /orders/{id}}. */
    public static NotificationDraft partnerAssigned(UUID orderId, String orderNumber, UUID deliveryId,
                                                    String partnerName) {
        String who = firstName(partnerName);
        return draft(NotificationType.PARTNER_ASSIGNED,
                (who == null ? "A delivery partner" : who) + " will deliver order " + orderNumber, "",
                orderLink(orderId), data("orderId", orderId, "orderNumber", orderNumber, "deliveryId", deliveryId));
    }

    /** Partner, DeliveryPartnerUnassigned: "Order {no} was reassigned" (no body) · {@code /delivery}. */
    public static NotificationDraft deliveryReassigned(UUID orderId, String orderNumber, UUID deliveryId) {
        return draft(NotificationType.DELIVERY_REASSIGNED, "Order " + orderNumber + " was reassigned", "",
                PARTNER_HOME, data("orderId", orderId, "orderNumber", orderNumber, "deliveryId", deliveryId));
    }

    /** Assigned partner, OrderReadyForPickup: "Order {no} is ready at {store}" · {@code /delivery}. */
    public static NotificationDraft pickupReady(UUID orderId, String orderNumber, UUID deliveryId, String storeName) {
        return draft(NotificationType.PICKUP_READY,
                "Order " + orderNumber + " is ready at " + nameOr(storeName, "the store"), "", PARTNER_HOME,
                data("orderId", orderId, "orderNumber", orderNumber, "deliveryId", deliveryId));
    }

    /** Customer, OrderOutForDelivery: "Order {no} is on the way" · {@code /orders/{id}}. */
    public static NotificationDraft outForDelivery(UUID orderId, String orderNumber) {
        return draft(NotificationType.OUT_FOR_DELIVERY, "Order " + orderNumber + " is on the way", "",
                orderLink(orderId), data("orderId", orderId, "orderNumber", orderNumber));
    }

    /** Customer, OrderDelivered: "Order {no} delivered" · "Enjoy!" · {@code /orders/{id}}. */
    public static NotificationDraft orderDelivered(UUID orderId, String orderNumber) {
        return draft(NotificationType.ORDER_DELIVERED, "Order " + orderNumber + " delivered", "Enjoy!",
                orderLink(orderId), data("orderId", orderId, "orderNumber", orderNumber));
    }

    /**
     * Customer, OrderDeliveryFailed: "We could not deliver order {no}" · "Our support team will contact you about a
     * refund." · {@code /orders/{id}}.
     */
    public static NotificationDraft deliveryFailedForCustomer(UUID orderId, String orderNumber) {
        return draft(NotificationType.DELIVERY_FAILED, "We could not deliver order " + orderNumber,
                "Our support team will contact you about a refund.", orderLink(orderId),
                data("orderId", orderId, "orderNumber", orderNumber));
    }

    /**
     * SUPER_ADMIN, OPS_ADMIN, SUPPORT, DELIVERY_MANAGER, OrderDeliveryFailed: "Delivery failed for order {no}" ·
     * "{reason}. Decide on a refund in the payment monitor." · {@code /admin/orders}; {reason} = the reason's label
     * and the partner's note ({@link #deliveryFailureReason}).
     */
    public static NotificationDraft deliveryFailedForAdmins(UUID orderId, String orderNumber, UUID deliveryId,
                                                            String reason, String note) {
        return draft(NotificationType.DELIVERY_FAILED_ADMIN, "Delivery failed for order " + orderNumber,
                deliveryFailureReason(reason, note) + ". Decide on a refund in the payment monitor.", "/admin/orders",
                data("orderId", orderId, "orderNumber", orderNumber, "deliveryId", deliveryId, "reason", reason));
    }

    /**
     * The failure as admins read it: CUSTOMER_UNREACHABLE "Customer unreachable", ADDRESS_NOT_FOUND "Address not
     * found", CUSTOMER_REFUSED "Customer refused the order", OTHER "Other reason", anything else as it is; + ": {note}"
     * when the partner left one (without trailing periods).
     */
    static String deliveryFailureReason(String reason, String note) {
        String label = switch (reason == null ? "" : reason.strip()) {
            case "CUSTOMER_UNREACHABLE" -> "Customer unreachable";
            case "ADDRESS_NOT_FOUND" -> "Address not found";
            case "CUSTOMER_REFUSED" -> "Customer refused the order";
            case "OTHER" -> "Other reason";
            case "" -> "Unknown reason";
            default -> reason.strip();
        };
        String cleanNote = note == null ? "" : withoutTrailingPeriods(note.strip());
        return cleanNote.isEmpty() ? label : label + ": " + cleanNote;
    }

    /**
     * SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER, DeliveryAssignmentFailed: "No delivery partner for order {no}" ·
     * "Assign one by hand." · {@code /admin/deliveries}.
     */
    public static NotificationDraft noDeliveryPartner(UUID orderId, String orderNumber, UUID deliveryId) {
        return draft(NotificationType.NO_DELIVERY_PARTNER, "No delivery partner for order " + orderNumber,
                "Assign one by hand.", "/admin/deliveries",
                data("orderId", orderId, "orderNumber", orderNumber, "deliveryId", deliveryId));
    }

    /** Partner, DeliveryCancelled with a partner: "Order {no} was cancelled" · "Do not pick it up." · /delivery. */
    public static NotificationDraft deliveryCancelled(UUID orderId, String orderNumber, UUID deliveryId) {
        return draft(NotificationType.DELIVERY_CANCELLED, "Order " + orderNumber + " was cancelled",
                "Do not pick it up.", PARTNER_HOME,
                data("orderId", orderId, "orderNumber", orderNumber, "deliveryId", deliveryId));
    }

    /** "Ravi Kumar" → "Ravi"; blank → null. Partners are shown to customers by first name only. */
    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.strip().split("\\s+", 2)[0];
    }

    // ---------------------------------------------------------------- stuck orders (Phase 10)

    /**
     * SUPER_ADMIN, OPS_ADMIN, SUPPORT, OrderStuckDetected: "Order {no} is stuck: {reason label}" · "{status} since
     * {HH:mm India time}." · {@code /admin/orders}; {@code data} = orderId, orderNumber, status, reason.
     */
    public static NotificationDraft stuckOrder(UUID orderId, String orderNumber, String status, String reason,
                                              Instant since) {
        String body = since == null ? status + "." : status + " since " + HOUR_MINUTE.format(since) + ".";
        return draft(NotificationType.STUCK_ORDER, "Order " + orderNumber + " is stuck: " + stuckReasonLabel(reason),
                body, "/admin/orders",
                data("orderId", orderId, "orderNumber", orderNumber, "status", status, "reason", reason));
    }

    /**
     * The watchdog's reason as admins read it: STOCK_CHECK_OVERDUE "stock check overdue", PAYMENT_EXPIRY_OVERDUE
     * "payment expiry overdue", STORE_RESPONSE_OVERDUE "store response overdue", PREPARATION_OVERDUE "preparation
     * overdue", PICKUP_OVERDUE "pickup overdue", DELIVERY_OVERDUE "delivery overdue"; anything else as it is.
     */
    static String stuckReasonLabel(String reason) {
        return switch (reason == null ? "" : reason.strip()) {
            case "STOCK_CHECK_OVERDUE" -> "stock check overdue";
            case "PAYMENT_EXPIRY_OVERDUE" -> "payment expiry overdue";
            case "STORE_RESPONSE_OVERDUE" -> "store response overdue";
            case "PREPARATION_OVERDUE" -> "preparation overdue";
            case "PICKUP_OVERDUE" -> "pickup overdue";
            case "DELIVERY_OVERDUE" -> "delivery overdue";
            case "" -> "overdue";
            default -> reason.strip();
        };
    }

    // ---------------------------------------------------------------- payments

    /** Customer, PaymentFailed with {@code retryable}. */
    public static NotificationDraft paymentFailed(UUID orderId, String orderNumber, String errorDescription) {
        String reason = errorDescription == null || errorDescription.isBlank()
                ? "Your bank declined the payment"
                : stripTrailingPeriods(errorDescription.strip());
        return draft(NotificationType.PAYMENT_FAILED, "Payment declined for order " + orderNumber,
                reason + ". You can try again before the payment window closes.", orderLink(orderId),
                data("orderId", orderId, "orderNumber", orderNumber));
    }

    /** Customer, RefundCompleted. */
    public static NotificationDraft refundCompleted(UUID orderId, String orderNumber, UUID refundId,
                                                    BigDecimal amount) {
        return refundCompleted(orderId, orderNumber, refundId, amount, null);
    }

    /** Customer, RefundCompleted; Phase 12: a return's refund also carries its {@code returnId} in {@code data}. */
    public static NotificationDraft refundCompleted(UUID orderId, String orderNumber, UUID refundId,
                                                    BigDecimal amount, UUID returnId) {
        String title = amount == null ? "Refund processed" : "Refund of " + Money.inr(amount) + " processed";
        return draft(NotificationType.REFUND_COMPLETED, title,
                "For order " + orderNumber + ". Banks usually take 5–7 working days to show it.",
                orderLink(orderId), data("orderId", orderId, "orderNumber", orderNumber, "refundId", refundId,
                        "amount", amount, "returnId", returnId));
    }

    /** SUPER_ADMIN + FINANCE_ADMIN, RefundFailed. */
    public static NotificationDraft refundFailed(UUID orderId, String orderNumber, UUID paymentId, UUID refundId,
                                                 BigDecimal amount, String reason, String errorCode) {
        String what = amount == null ? "The refund" : Money.inr(amount);
        if (reason != null && !reason.isBlank()) {
            what += " (" + reason.strip() + ")";
        }
        String code = errorCode == null || errorCode.isBlank() ? "unknown error" : errorCode.strip();
        return draft(NotificationType.REFUND_FAILED, "Refund failed for order " + orderNumber,
                what + DASH + code + ". Retry it from the payment monitor.", "/admin/payments",
                data("orderId", orderId, "orderNumber", orderNumber, "paymentId", paymentId, "refundId", refundId));
    }

    // ---------------------------------------------------------------- stock and pricing

    /** Vendor owner, LowStockDetected. */
    public static NotificationDraft lowStock(UUID productId, String productName, Integer available,
                                             Integer threshold) {
        String body = available == null || threshold == null
                ? "Stock is running low."
                : available + " left (alert at " + threshold + ").";
        return draft(NotificationType.LOW_STOCK, "Low stock: " + nameOr(productName, "a product"), body,
                "/vendor/inventory", data("productId", productId));
    }

    /** SUPER_ADMIN + OPS_ADMIN + FINANCE_ADMIN, PriceChangeRequested. */
    public static NotificationDraft priceApprovalRequested(UUID requestId, UUID productId, UUID vendorId,
                                                           String productName, BigDecimal changePercent,
                                                           BigDecimal oldSellingPrice, BigDecimal newSellingPrice) {
        String prices = nameOr(Money.inr(oldSellingPrice), "the old price") + ARROW
                + nameOr(Money.inr(newSellingPrice), "the new price");
        String body = changePercent == null ? prices : percent(changePercent) + DASH + prices;
        return draft(NotificationType.PRICE_APPROVAL_REQUESTED,
                "Price change waiting: " + nameOr(productName, "a product"), body, "/admin/price-approvals",
                data("kind", "PRICE_CHANGE", "requestId", requestId, "productId", productId, "vendorId", vendorId));
    }

    /** Vendor owner, PriceChangeApproved. */
    public static NotificationDraft priceChangeApproved(UUID requestId, UUID productId) {
        return draft(NotificationType.PRICE_CHANGE_APPROVED, "Price change approved", "The new price is live.",
                "/vendor/pricing", data("requestId", requestId, "productId", productId));
    }

    /** Vendor owner, PriceChangeRejected. */
    public static NotificationDraft priceChangeRejected(UUID requestId, UUID productId, String reason) {
        return draft(NotificationType.PRICE_CHANGE_REJECTED, "Price change rejected", textOrEmpty(reason),
                "/vendor/pricing", data("requestId", requestId, "productId", productId));
    }

    // ---------------------------------------------------------------- accounts and approvals

    /** Admin roles of the kind, Vendor/Restaurant/DeliveryPartner Submitted. */
    public static NotificationDraft approvalRequested(AccountKind kind, UUID accountId, String name) {
        return draft(NotificationType.APPROVAL_REQUESTED,
                capitalize(nameOr(name, kind.neutralName())) + " is waiting for approval", kind.approvalBody(),
                "/admin/approvals", data("kind", kind.name(), "id", accountId));
    }

    public static NotificationDraft accountApproved(AccountKind kind, UUID accountId) {
        return draft(NotificationType.ACCOUNT_APPROVED, "Your " + kind.accountNoun() + " is approved",
                "You can start now.", kind.home(), data("kind", kind.name(), "id", accountId));
    }

    public static NotificationDraft accountRejected(AccountKind kind, UUID accountId, String reason) {
        return draft(NotificationType.ACCOUNT_REJECTED, "Your application needs changes", textOrEmpty(reason),
                kind.home(), data("kind", kind.name(), "id", accountId));
    }

    /** No link: the owner is signed out right after it ({@code session REVOKED}). */
    public static NotificationDraft accountSuspended(AccountKind kind, UUID accountId, String reason) {
        return draft(NotificationType.ACCOUNT_SUSPENDED, "Your account is suspended", textOrEmpty(reason), null,
                data("kind", kind.name(), "id", accountId));
    }

    /** Reinstated (link = the role home) and UserReactivated (link = {@code /}, the app's home for every role). */
    public static NotificationDraft accountReinstated(AccountKind kind, UUID accountId) {
        return draft(NotificationType.ACCOUNT_REINSTATED, "Your account is active again", "", kind.home(),
                data("kind", kind.name(), "id", accountId));
    }

    // ---------------------------------------------------------------- security emails (password management)

    /** The React app's pages behind the security emails' links. */
    static final String RESET_PASSWORD_PAGE = "/reset-password";
    static final String FORGOT_PASSWORD_PAGE = "/forgot-password";
    private static final DateTimeFormatter DAY_AND_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH).withZone(INDIA);

    /**
     * PasswordResetRequested (an email only, never an inbox entry): the link carries the raw token. It works once; the
     * expiry is shown as minutes from now (rounded up) and as India time.
     */
    public static NotificationDraft passwordResetRequested(String fullName, String email, String token,
                                                           Instant expiresAt, Instant now) {
        long minutes = Math.max(1, (Duration.between(now, expiresAt).toSeconds() + 59) / 60);
        String body = greeting(fullName)
                + "We received a request to reset the password of your Grocery account (" + email + ").\n\n"
                + "Choose a new password with the link below. It works once and expires in " + minutes
                + (minutes == 1 ? " minute" : " minutes") + ", at " + HOUR_MINUTE.format(expiresAt) + " India time.\n\n"
                + "If you did not ask for this, you can ignore this email: your password stays the same.";
        return draft(NotificationType.PASSWORD_RESET_REQUESTED, "Reset your Grocery password", body,
                RESET_PASSWORD_PAGE + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8), data());
    }

    /**
     * PasswordChanged (an email only): how and when it changed, and what to do if it was not the owner. The link leads
     * to "forgot password", which works without the (possibly stolen) password.
     */
    public static NotificationDraft passwordChanged(String fullName, String email, String method, Instant changedAt) {
        boolean reset = "RESET".equals(method);
        String body = greeting(fullName)
                + "The password of your Grocery account (" + email + ") was changed "
                + (reset ? "with a password reset link" : "in your account settings")
                + (changedAt == null ? "" : " on " + DAY_AND_TIME.format(changedAt) + " (India time)") + ". "
                + (reset ? "Every device was signed out." : "Your other devices were signed out.") + "\n\n"
                + "If this wasn't you, reset your password now with the link below, and tell our support team.";
        return draft(NotificationType.PASSWORD_CHANGED, "Your Grocery password was changed", body,
                FORGOT_PASSWORD_PAGE, data("method", method));
    }

    /** "Hi Asha Rao," and a blank line; "Hello," without a name. */
    private static String greeting(String fullName) {
        return fullName == null || fullName.isBlank() ? "Hello,\n\n" : "Hi " + fullName.strip() + ",\n\n";
    }

    // ---------------------------------------------------------------- assistant approvals (Phase 11, Phase 12 §6)

    /** The customer's approvals page in the React app. */
    static final String CUSTOMER_APPROVALS = ApprovalPage.CUSTOMER.path();
    /** The admins' approvals page (to decide, plus history). */
    static final String ADMIN_APPROVALS = ApprovalPage.ADMIN.path();
    /** {@code notification.title} is VARCHAR(200), {@code body} VARCHAR(1000): longer event texts are shortened. */
    static final int MAX_TITLE = 200;
    static final int MAX_BODY = 1000;

    /** Phase 11 form: {@code customer} = the customer page, else the admin page. */
    public static NotificationDraft aiApprovalRequested(UUID approvalId, String action, String agent, String title,
                                                        Instant expiresAt, boolean customer) {
        return aiApprovalRequested(approvalId, action, agent, title, expiresAt, page(customer));
    }

    /**
     * Approver, ApprovalRequested: "Approval needed: {title}" · "Proposed by the {agent label} assistant. Expires at
     * {HH:mm India time}." An unknown agent leaves its label out ("Proposed by the assistant."), a missing expiry the
     * second sentence.
     *
     * @param page the reader's approvals page ({@link ApprovalPage}; link {@code <page>?id=})
     */
    public static NotificationDraft aiApprovalRequested(UUID approvalId, String action, String agent, String title,
                                                        Instant expiresAt, ApprovalPage page) {
        String label = agentLabel(agent);
        String body = "Proposed by the " + (label == null ? "" : label + " ") + "assistant."
                + (expiresAt == null ? "" : " Expires at " + HOUR_MINUTE.format(expiresAt) + ".");
        return aiDraft(NotificationType.AI_APPROVAL_REQUESTED, "Approval needed: ", title, body, approvalId, action,
                page);
    }

    public static NotificationDraft aiApprovalRejected(UUID approvalId, String action, String title, String comment,
                                                       boolean customer) {
        return aiApprovalRejected(approvalId, action, title, comment, page(customer));
    }

    /** Requester and the customer it is for, ApprovalDecided REJECTED: "Not approved: {title}" · "{comment}". */
    public static NotificationDraft aiApprovalRejected(UUID approvalId, String action, String title, String comment,
                                                       ApprovalPage page) {
        return aiDraft(NotificationType.AI_APPROVAL_DECIDED, "Not approved: ", title, textOrEmpty(comment),
                approvalId, action, page);
    }

    public static NotificationDraft aiApprovalExpired(UUID approvalId, String action, String title,
                                                      boolean customer) {
        return aiApprovalExpired(approvalId, action, title, page(customer));
    }

    /** Requester and the customer it is for, ApprovalDecided EXPIRED: "Expired: {title}" · "Nobody decided in time." */
    public static NotificationDraft aiApprovalExpired(UUID approvalId, String action, String title,
                                                      ApprovalPage page) {
        return aiDraft(NotificationType.AI_APPROVAL_DECIDED, "Expired: ", title, "Nobody decided in time.",
                approvalId, action, page);
    }

    public static NotificationDraft aiActionCompleted(UUID approvalId, String action, String title,
                                                      String resultReference, boolean customer) {
        return aiActionCompleted(approvalId, action, title, resultReference, page(customer));
    }

    /** Requester and the customer it is for, ApprovalExecuted: "Done: {title}" · "{resultReference}" (or ""). */
    public static NotificationDraft aiActionCompleted(UUID approvalId, String action, String title,
                                                      String resultReference, ApprovalPage page) {
        return aiDraft(NotificationType.AI_ACTION_COMPLETED, "Done: ", title, textOrEmpty(resultReference),
                approvalId, action, page);
    }

    public static NotificationDraft aiActionFailed(UUID approvalId, String action, String title, String errorMessage,
                                                   String errorCode, boolean customer) {
        return aiActionFailed(approvalId, action, title, errorMessage, errorCode, page(customer));
    }

    /**
     * Requester and the customer it is for, ApprovalFailed: "Could not complete: {title}" · "{errorMessage}"; without
     * a message the error code is shown as it is (like REFUND_FAILED does), without both "".
     */
    public static NotificationDraft aiActionFailed(UUID approvalId, String action, String title, String errorMessage,
                                                   String errorCode, ApprovalPage page) {
        String body = errorMessage != null && !errorMessage.isBlank() ? errorMessage : textOrEmpty(errorCode);
        return aiDraft(NotificationType.AI_ACTION_FAILED, "Could not complete: ", title, body.strip(), approvalId,
                action, page);
    }

    /**
     * The assistant's name as people read it: {@code shopping} "Shopping", {@code admin-bi} "Business insights",
     * (Phase 12) {@code vendor} "Vendor", {@code restaurant} "Restaurant", {@code delivery} "Delivery".
     */
    static String agentLabel(String agent) {
        return switch (agent == null ? "" : agent.strip()) {
            case "shopping" -> "Shopping";
            case "admin-bi" -> "Business insights";
            case "vendor" -> "Vendor";
            case "restaurant" -> "Restaurant";
            case "delivery" -> "Delivery";
            default -> null;
        };
    }

    /** {@code /assistant/approvals?id={id}} for the customer the action is for, {@code /admin/ai/approvals?id={id}} else. */
    static String approvalLink(UUID approvalId, boolean customer) {
        return page(customer).link(approvalId);
    }

    private static ApprovalPage page(boolean customer) {
        return customer ? ApprovalPage.CUSTOMER : ApprovalPage.ADMIN;
    }

    /** {@code data} = approvalId, action (contract §8); a missing title falls back to "an assistant action". */
    private static NotificationDraft aiDraft(NotificationType type, String titlePrefix, String title, String body,
                                             UUID approvalId, String action, ApprovalPage page) {
        return draft(type, shorten(titlePrefix + nameOr(title, "an assistant action"), MAX_TITLE),
                shorten(body, MAX_BODY), page.link(approvalId),
                data("approvalId", approvalId, "action", action));
    }

    // ---------------------------------------------------------------- reviews (Phase 12 §1)

    /** The customer's reviews page. */
    static final String CUSTOMER_REVIEWS = "/account/reviews";
    /** The admins' moderation queue. */
    static final String ADMIN_REVIEWS = "/admin/reviews";
    static final String STAR = "★";

    /**
     * Store owner, ReviewSubmitted PUBLISHED: "New {rating}★ review" · "{targetName}" (+ " (edited)" for an edited
     * review) · {@code /vendor/reviews} or {@code /restaurant/reviews}. The contract's "{title or first 80 chars}" is
     * left out: review.events (schemas 1.5.0) carry neither the title nor the comment. {@code targetName} falls back to
     * "your product" / "your dish" / "your store" / "your restaurant".
     */
    public static NotificationDraft newReview(UUID reviewId, ReviewTarget target, String targetType, UUID targetId,
                                              Integer rating, String targetName, boolean edited, StoreType storeType) {
        String title = rating == null ? "New review" : "New " + rating + STAR + " review";
        String name = nameOr(targetName, target == null ? "your product" : target.ownerFallback());
        return draft(NotificationType.NEW_REVIEW, title, shorten(name + (edited ? " (edited)" : ""), MAX_BODY),
                storeType.reviews(),
                data("reviewId", reviewId, "targetType", targetType, "targetId", targetId, "rating", rating));
    }

    /**
     * SUPER_ADMIN, OPS_ADMIN, SUPPORT, ReviewSubmitted FLAGGED: "A {rating}★ review needs moderation" ·
     * "{targetName} · {flag reason label}" · {@code /admin/reviews}.
     */
    public static NotificationDraft reviewFlagged(UUID reviewId, ReviewTarget target, String targetType,
                                                  UUID targetId, Integer rating, String targetName,
                                                  String flagReason) {
        String title = rating == null ? "A review needs moderation"
                : "A " + rating + STAR + " review needs moderation";
        String name = nameOr(targetName, target == null ? "an item" : target.adminFallback());
        return draft(NotificationType.REVIEW_FLAGGED, title,
                shorten(capitalize(name) + DOT + flagReasonLabel(flagReason), MAX_BODY), ADMIN_REVIEWS,
                data("reviewId", reviewId, "targetType", targetType, "targetId", targetId, "rating", rating,
                        "flagReason", flagReason));
    }

    /**
     * Why automatic moderation flagged a review, as admins read it: LINK / URL "contains a link", PHONE "contains a phone
     * number", EMAIL "contains an email address", PROFANITY "contains a blocked word", UPPERCASE / CAPS / SHOUTING
     * "mostly capital letters"; other codes in lower case; several (comma- or space-separated) joined with ", ";
     * nothing → "flagged by automatic moderation".
     */
    static String flagReasonLabel(String flagReason) {
        if (flagReason == null || flagReason.isBlank()) {
            return "flagged by automatic moderation";
        }
        return Arrays.stream(flagReason.strip().split("[,;\\s]+"))
                .filter(code -> !code.isBlank())
                .map(code -> switch (code.toUpperCase(java.util.Locale.ROOT)) {
                    case "LINK", "URL", "LINKS" -> "contains a link";
                    case "PHONE", "PHONE_NUMBER" -> "contains a phone number";
                    case "EMAIL", "EMAIL_ADDRESS" -> "contains an email address";
                    case "PROFANITY", "BLOCKED_WORD", "BAD_WORD" -> "contains a blocked word";
                    case "UPPERCASE", "UPPER_CASE", "CAPS", "ALL_CAPS", "SHOUTING" -> "mostly capital letters";
                    default -> code.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                })
                .distinct()
                .collect(Collectors.joining(", "));
    }

    /**
     * Customer, ReviewModerated REJECTED / REMOVED: "Your review of {targetName} was not published" / "… was removed" ·
     * "{reason}" · {@code /account/reviews}.
     */
    public static NotificationDraft reviewNotPublished(UUID reviewId, ReviewTarget target, String targetType,
                                                       UUID targetId, String decision, String targetName,
                                                       String reason) {
        String name = nameOr(targetName, target == null ? "an item" : target.customerFallback());
        String what = "REMOVED".equals(decision) ? " was removed" : " was not published";
        return draft(NotificationType.REVIEW_NOT_PUBLISHED, shorten("Your review of " + name + what, MAX_TITLE),
                shorten(textOrEmpty(reason), MAX_BODY), CUSTOMER_REVIEWS,
                data("reviewId", reviewId, "targetType", targetType, "targetId", targetId, "decision", decision));
    }

    /**
     * Customer, ReviewReplied: "{storeName} replied to your review" · "Your review of {targetName}." ·
     * {@code /account/reviews}.
     */
    public static NotificationDraft reviewReply(UUID reviewId, ReviewTarget target, String targetType, UUID targetId,
                                                String storeName, String targetName) {
        String name = nameOr(targetName, target == null ? "an item" : target.customerFallback());
        return draft(NotificationType.REVIEW_REPLY,
                shorten(nameOr(storeName, "The store") + " replied to your review", MAX_TITLE),
                shorten("Your review of " + name + ".", MAX_BODY), CUSTOMER_REVIEWS,
                data("reviewId", reviewId, "targetType", targetType, "targetId", targetId));
    }

    // ---------------------------------------------------------------- returns (Phase 12 §3)

    /** The admins' returns page. */
    static final String ADMIN_RETURNS = "/admin/returns";

    /**
     * Store owner, ReturnRequested: "Return requested for order {no}" · "{n} items · {refund} · decide by {HH:mm India
     * time}" · {@code /vendor/returns} or {@code /restaurant/returns}. {@code itemCount} = the returned quantity.
     */
    public static NotificationDraft returnRequested(UUID orderId, String orderNumber, UUID returnId,
                                                    String returnNumber, Integer itemCount, BigDecimal refundAmount,
                                                    Instant decideBy, StoreType storeType) {
        String items = itemCount == null ? null : itemCount + (itemCount == 1 ? " item" : " items");
        String deadline = decideBy == null ? null : "decide by " + HOUR_MINUTE.format(decideBy);
        return draft(NotificationType.RETURN_REQUESTED, "Return requested for order " + orderNumber,
                joinNonNull(DOT, items, Money.inr(refundAmount), deadline), storeType.returns(),
                data("orderId", orderId, "orderNumber", orderNumber, "returnId", returnId, "returnNumber",
                        returnNumber, "decideBy", decideBy == null ? null : decideBy.toString()));
    }

    /**
     * SUPER_ADMIN, OPS_ADMIN, SUPPORT, ReturnEscalated: "Return {RT} needs a decision" · "Order {no} · {refund} · the
     * store did not decide in time." · {@code /admin/returns}.
     */
    public static NotificationDraft returnEscalated(UUID orderId, String orderNumber, UUID returnId,
                                                    String returnNumber, BigDecimal refundAmount) {
        return draft(NotificationType.RETURN_ESCALATED, "Return " + returnNumber + " needs a decision",
                joinNonNull(DOT, "Order " + orderNumber, Money.inr(refundAmount),
                        "the store did not decide in time."), ADMIN_RETURNS,
                data("orderId", orderId, "orderNumber", orderNumber, "returnId", returnId, "returnNumber",
                        returnNumber));
    }

    /** Customer, ReturnApproved: "Return {RT} approved" · "{refund} will be refunded." · {@code /orders/{id}}. */
    public static NotificationDraft returnApproved(UUID orderId, String orderNumber, UUID returnId,
                                                   String returnNumber, BigDecimal refundAmount) {
        String body = refundAmount == null ? "Your refund is on its way."
                : Money.inr(refundAmount) + " will be refunded.";
        return draft(NotificationType.RETURN_APPROVED, "Return " + returnNumber + " approved", body,
                orderLink(orderId), data("orderId", orderId, "orderNumber", orderNumber, "returnId", returnId,
                        "returnNumber", returnNumber, "refundAmount", refundAmount));
    }

    /** Customer, ReturnRejected: "Return {RT} rejected" · "{comment}" · {@code /orders/{id}}. */
    public static NotificationDraft returnRejected(UUID orderId, String orderNumber, UUID returnId,
                                                   String returnNumber, String comment) {
        return draft(NotificationType.RETURN_REJECTED, "Return " + returnNumber + " rejected",
                shorten(textOrEmpty(comment), MAX_BODY), orderLink(orderId),
                data("orderId", orderId, "orderNumber", orderNumber, "returnId", returnId, "returnNumber",
                        returnNumber));
    }

    /** At most {@code max} characters: a longer text is cut and ends with "…" (never inside a surrogate pair). */
    static String shorten(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + "…";
    }

    // ---------------------------------------------------------------- helpers

    static String orderLink(UUID orderId) {
        return "/orders/" + orderId;
    }

    /** {@code -48.98} → "-48.98%", {@code 12.5} → "+12.50%". */
    static String percent(BigDecimal changePercent) {
        BigDecimal value = changePercent.setScale(2, RoundingMode.HALF_UP);
        return (value.signum() > 0 ? "+" : "") + value.toPlainString() + "%";
    }

    /** Keys with a null value are left out; UUIDs become strings. */
    public static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                data.put((String) keyValues[i], value instanceof UUID ? value.toString() : value);
            }
        }
        return data;
    }

    private static NotificationDraft draft(NotificationType type, String title, String body, String link,
                                           Map<String, Object> data) {
        return new NotificationDraft(type, title, body, link, data);
    }

    static String nameOr(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name.strip();
    }

    private static String textOrEmpty(String text) {
        return text == null ? "" : text.strip();
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String stripTrailingPeriods(String text) {
        String result = text;
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1).stripTrailing();
        }
        return result.isEmpty() ? "Your bank declined the payment" : result;
    }

    private static String withoutTrailingPeriods(String text) {
        String result = text;
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1).stripTrailing();
        }
        return result;
    }

    private static String joinNonNull(String separator, String... parts) {
        return Arrays.stream(parts).filter(Objects::nonNull).collect(Collectors.joining(separator));
    }
}
