package com.grocery.order.entity;

/** What an entry of the order event timeline did ({@code order_event_log.outcome}). */
public enum EventOutcome {
    /** Published, or consumed and applied to the order. */
    APPLIED,
    /** Consumed, but a state guard rejected it (the guard's reason is the entry's {@code detail}). */
    IGNORED,
    /** Consumed again: it had already been processed (nothing changed). */
    DUPLICATE
}
