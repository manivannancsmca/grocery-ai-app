package com.grocery.notification.dispatch;

import com.grocery.common.security.Roles;

import java.util.UUID;

/**
 * The app page where a reader of an assistant's approval notification sees (and, as a decider, decides) the request
 * (Phase 11 §8, extended by Phase 12 §6). Every link carries {@code ?id={approvalId}}.
 */
public enum ApprovalPage {

    /** The customer the action is for (Shopping assistant). */
    CUSTOMER("/assistant/approvals"),
    /** Phase 12: a vendor (Vendor assistant). */
    VENDOR("/vendor/assistant"),
    /** Phase 12: a restaurant owner (Restaurant assistant). */
    RESTAURANT("/restaurant/assistant"),
    /** Phase 12: a delivery partner or delivery manager (Delivery assistant). */
    DELIVERY("/delivery/assistant"),
    /** Every admin role (except a delivery manager, see {@link #ofRole}). */
    ADMIN("/admin/ai/approvals");

    private final String path;

    ApprovalPage(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public String link(UUID approvalId) {
        return path + "?id=" + approvalId;
    }

    /**
     * The page of a requester (or self-deciding approver) of the Phase 12 assistants: {@code vendor} → VENDOR,
     * {@code restaurant} → RESTAURANT, {@code delivery} → DELIVERY; null for the others ({@code shopping},
     * {@code admin-bi}), whose readers keep the Phase 11 rule.
     */
    public static ApprovalPage ofAgent(String agent) {
        return switch (agent == null ? "" : agent.strip()) {
            case "vendor" -> VENDOR;
            case "restaurant" -> RESTAURANT;
            case "delivery" -> DELIVERY;
            default -> null;
        };
    }

    /**
     * The page of a role-addressed approver: VENDOR → VENDOR, RESTAURANT_OWNER → RESTAURANT, DELIVERY_PARTNER and
     * DELIVERY_MANAGER → DELIVERY, CUSTOMER → CUSTOMER; every other (admin) role → ADMIN.
     */
    public static ApprovalPage ofRole(String role) {
        return switch (role == null ? "" : role) {
            case Roles.VENDOR -> VENDOR;
            case Roles.RESTAURANT_OWNER -> RESTAURANT;
            case Roles.DELIVERY_PARTNER, Roles.DELIVERY_MANAGER -> DELIVERY;
            case Roles.CUSTOMER -> CUSTOMER;
            default -> ADMIN;
        };
    }
}
