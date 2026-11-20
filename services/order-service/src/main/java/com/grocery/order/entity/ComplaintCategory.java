package com.grocery.order.entity;

/** What a complaint is about (docs/api/payment-and-support-agents.md §2.2). */
public enum ComplaintCategory {
    /** Late, not delivered, delivered to the wrong place, the delivery partner's behaviour. */
    DELIVERY,
    /** Charged but the order is not confirmed, charged twice, refund not received. */
    PAYMENT,
    /** Quality, damaged, wrong or missing items (a refund for items is a return request, not a complaint). */
    ITEMS,
    /** The store's or restaurant's service. */
    STORE,
    /** The app or website does not work. */
    APP,
    /** Anything else. */
    OTHER
}
