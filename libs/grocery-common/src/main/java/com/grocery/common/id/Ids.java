package com.grocery.common.id;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;

/**
 * Application-generated identifiers.
 *
 * <p>We use UUIDv7: time-ordered (good for InnoDB clustered indexes) and known before the INSERT,
 * which the outbox pattern and idempotency handling rely on.
 */
public final class Ids {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private Ids() {
    }

    public static UUID newId() {
        return GENERATOR.generate();
    }
}
