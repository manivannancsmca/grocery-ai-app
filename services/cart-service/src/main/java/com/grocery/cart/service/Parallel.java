package com.grocery.cart.service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Waits for a task and rethrows what it threw (an ApiException stays an ApiException). */
final class Parallel {

    private Parallel() {
    }

    static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a downstream call", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
