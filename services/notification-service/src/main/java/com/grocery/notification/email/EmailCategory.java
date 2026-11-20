package com.grocery.notification.email;

import com.grocery.notification.entity.NotificationType;

/**
 * The email preference categories of {@code GET/PUT /api/notifications/preferences} (Phase 12 §5): orders, refunds and
 * returns → {@code orders}; ACCOUNT_* → {@code account}; assistant approvals → {@code approvals}. A type added to
 * {@code grocery.notification.email.types} later follows its notification category: APPROVAL → approvals, ACCOUNT →
 * account, everything else → orders.
 */
public enum EmailCategory {

    ORDERS("orders", "order updates"),
    ACCOUNT("account", "account updates"),
    APPROVALS("approvals", "approval requests");

    private final String column;
    private final String label;

    EmailCategory(String column, String label) {
        this.column = column;
        this.label = label;
    }

    /** The {@code email_preference} column (also the JSON field name). */
    public String column() {
        return column;
    }

    /** As people read it ("You get these emails for order updates."). */
    public String label() {
        return label;
    }

    public static EmailCategory of(NotificationType type) {
        return switch (type.category()) {
            case APPROVAL -> APPROVALS;
            case ACCOUNT -> ACCOUNT;
            default -> ORDERS;
        };
    }
}
