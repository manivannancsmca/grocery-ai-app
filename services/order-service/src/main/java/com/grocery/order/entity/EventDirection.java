package com.grocery.order.entity;

/** An entry of the order event timeline ({@code order_event_log}): an event order-service published or consumed. */
public enum EventDirection {
    PUBLISHED,
    CONSUMED
}
