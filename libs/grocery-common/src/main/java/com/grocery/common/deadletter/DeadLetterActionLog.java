package com.grocery.common.deadletter;

/** Where the state of a dead letter is written ({@code _grocery.dead-letter-actions} in production). */
public interface DeadLetterActionLog {

    /**
     * Writes the new state synchronously.
     *
     * @return the record's offset in the action topic
     * @throws RuntimeException when the state could not be written
     */
    long append(String key, DeadLetterAction action);
}
