package com.grocery.common.deadletter;

/** Lifecycle of one dead letter. A missing entry in {@code _grocery.dead-letter-actions} means {@link #PENDING}. */
public enum DeadLetterStatus {
    /** In the DLT, nothing done yet. */
    PENDING,
    /** Handed to the service's own listener again, which succeeded. Closed. */
    REPLAYED,
    /** Handed to the listener again, which failed ({@code lastError}); can be replayed again or discarded. */
    REPLAY_FAILED,
    /** Deliberately dropped by an operator (with a reason). Closed. */
    DISCARDED;

    /** REPLAYED and DISCARDED records cannot be replayed or discarded again (409 DEAD_LETTER_CLOSED). */
    public boolean isClosed() {
        return this == REPLAYED || this == DISCARDED;
    }
}
