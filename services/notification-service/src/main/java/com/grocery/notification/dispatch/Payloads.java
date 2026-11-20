package com.grocery.notification.dispatch;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;

/** Small checks shared by the handlers: a payload without a field we cannot do without is poison (DLT at once). */
final class Payloads {

    private Payloads() {
    }

    static <V> V require(EventEnvelope event, V value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " lacks " + field);
        }
        return value;
    }
}
