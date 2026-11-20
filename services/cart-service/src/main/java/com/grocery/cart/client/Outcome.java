package com.grocery.cart.client;

import com.grocery.cart.web.CartErrors;

/**
 * The result of one downstream call: a body, a definite "not found" (the owner answered 404 with the expected
 * error code), or a failure (unreachable, timeout, 5xx, unexpected answer, open circuit breaker). Calls never throw;
 * the caller decides whether a failure fails the request (adding) or becomes an issue (viewing).
 */
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.NotFound, Outcome.Failed {

    record Ok<T>(T value) implements Outcome<T> {
    }

    record NotFound<T>() implements Outcome<T> {
    }

    record Failed<T>(Dependency dependency) implements Outcome<T> {
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    default boolean isFailed() {
        return this instanceof Failed<T>;
    }

    /** The body, or null when not found or failed. */
    default T valueOrNull() {
        return this instanceof Ok<T>(T value) ? value : null;
    }

    /** The body, null when not found; a failure → 503 DEPENDENCY_UNAVAILABLE (the request cannot go on). */
    default T requireAvailable() {
        if (this instanceof Failed<T>(Dependency dependency)) {
            throw CartErrors.dependencyUnavailable(dependency);
        }
        return valueOrNull();
    }
}
