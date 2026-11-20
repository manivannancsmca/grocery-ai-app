package com.grocery.apitests.support;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.fail;

/** Polls an API call until its response satisfies a condition (event-driven steps take a second or two). */
public final class Await {

    private Await() {
    }

    public static Res until(String what, Duration max, Supplier<Res> call, Predicate<Res> condition) {
        long deadline = System.nanoTime() + max.toNanos();
        Res last = null;
        RuntimeException lastError = null;
        while (true) {
            try {
                last = call.get();
                lastError = null;
                if (condition.test(last)) {
                    return last;
                }
            } catch (RuntimeException e) {
                lastError = e;            // e.g. a JSON path on an error body
            }
            if (System.nanoTime() > deadline) {
                break;
            }
            Api.sleep(400);
        }
        return fail("Timed out after %s waiting for: %s. Last response: %s%s", max, what,
                last == null ? "none" : last.describe(), lastError == null ? "" : " (" + lastError + ")");
    }

    public static Res status(String what, Duration max, Supplier<Res> call, int status) {
        return until(what, max, call, r -> r.status() == status);
    }

    public static Res field(String what, Duration max, Supplier<Res> call, String gpath, Object value) {
        return until(what, max, call, r -> r.status() == 200 && value.equals(r.json().get(gpath)));
    }

    public static Duration seconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }
}
