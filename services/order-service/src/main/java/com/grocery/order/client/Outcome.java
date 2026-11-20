package com.grocery.order.client;

/**
 * The result of one downstream call: a body, a definite "not found" (the owner answered 404 with the expected
 * error code), or a failure (unreachable, timeout, 5xx, unexpected answer, open circuit breaker). Calls never throw.
 */
public sealed interface Outcome<T> permits Outcome.Ok, Outcome.NotFound, Outcome.Failed {

    record Ok<T>(T value) implements Outcome<T> {
    }

    record NotFound<T>() implements Outcome<T> {
    }

    /**
     * @param dependency the dependency to name in the 503 problem: the called service, or — when it answered 503
     *                   {@code DEPENDENCY_UNAVAILABLE} itself — the service it could not reach
     */
    record Failed<T>(String dependency) implements Outcome<T> {
    }
}
