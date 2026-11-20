package com.grocery.apitests.security;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Chat;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rate limiting answers 429: the gateway's per-user token bucket (20/s, burst 40), the per-client-IP bucket of
 * login/registration (2/s, burst 10) and the assistant's per-user message budget ({@code AI_MESSAGES_PER_HOUR}).
 * Runs last, so the drained buckets cannot slow the other tests.
 */
@Tag("security")
@Order(Integer.MAX_VALUE)
@DisplayName("Rate limiting")
class RateLimitTest {

    @Test
    void a_burst_above_the_per_user_bucket_gets_429_and_the_user_recovers() throws Exception {
        Session user = Session.register("CUSTOMER", "Apit Burst");
        List<Res> results = burst(120, () -> Api.as(user).noRetry().get("/api/notifications/unread-count"));
        List<Res> limited = results.stream().filter(r -> r.status() == 429).toList();
        assertThat(limited).as("429s among %s answers", results.size()).isNotEmpty();
        assertThat(results).as("the bucket's burst still passes").anyMatch(r -> r.status() == 200);
        assertThat(limited.getFirst().header("X-RateLimit-Remaining")).isEqualTo("0");
        Api.sleep(3000);
        Api.as(user).noRetry().get("/api/notifications/unread-count").ok();
        // another user is not affected by this user's bucket
        Session other = Session.register("CUSTOMER", "Apit Calm");
        Api.as(other).noRetry().get("/api/notifications/unread-count").ok();
    }

    @Test
    void login_attempts_are_limited_per_client_ip() throws Exception {
        // The gateway keys this bucket on the client IP behind one trusted proxy hop (X-Forwarded-For). A private,
        // random "client" keeps this test from throttling other clients that share our NAT address.
        String client = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256)
                + "." + (1 + ThreadLocalRandom.current().nextInt(254));
        List<Res> results = burst(30, () -> Api.anon().noRetry().header("X-Forwarded-For", client)
                .body(obj("email", "apit-nobody@example.com", "password", "Wrong1234")).post("/api/auth/login"));
        assertThat(results).as("statuses").extracting(Res::status).containsOnly(401, 429).contains(429);
    }

    @Test
    void the_assistant_limits_messages_per_user_and_hour() {
        Res status = Api.as(Session.register("CUSTOMER", "Apit Status")).get("/api/ai/status");
        assumeTrue(status.status() == 200 && "scripted".equals(status.str("provider")),
                "the assistant is not running the scripted model");
        Session chatty = Session.register("CUSTOMER", "Apit Chatty");
        Chat last = null;
        for (int i = 0; i < 40; i++) {
            last = Chat.send(chatty, "shopping", "hello " + i);
            if (last.status() == 429) {
                break;
            }
            last.streamed();
        }
        assertThat(last).isNotNull();
        last.response().problem(429, "AI_RATE_LIMITED");
        Api.as(chatty).get("/api/cart").ok();        // the rest of the platform is unaffected
    }

    private static List<Res> burst(int requests, java.util.concurrent.Callable<Res> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(40);
        try {
            List<Future<Res>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(call));
            }
            List<Res> results = new ArrayList<>();
            for (Future<Res> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
