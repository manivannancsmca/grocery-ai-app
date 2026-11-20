package com.grocery.apitests.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A signed-in user. The access token lives 15 minutes, so it is renewed by logging in again after 10 (the suite
 * knows every password it created). After a role or status change (approval, suspension) the platform revokes older
 * tokens; {@link #reloginUntil} waits for a token with the new claims.
 */
public final class Session {

    public static final String PASSWORD = "Secret123";
    private static final AtomicInteger USERS = new AtomicInteger();
    private static final long RENEW_AFTER_MS = Duration.ofMinutes(10).toMillis();

    private final String email;
    private final String password;
    private final String role;
    private volatile String token;
    private volatile long issuedAtMs;

    private Session(String email, String password, String role, String token) {
        this.email = email;
        this.password = password;
        this.role = role;
        this.token = token;
        this.issuedAtMs = System.currentTimeMillis();
    }

    /** Self-registration (CUSTOMER, VENDOR, RESTAURANT_OWNER, DELIVERY_PARTNER). */
    public static Session register(String role, String fullName) {
        String email = uniqueEmail(role);
        Res r = Api.anon().body(Json.obj("email", email, "password", PASSWORD, "fullName", fullName,
                "phone", "+919876543210", "role", role)).post("/api/auth/register");
        r.created();
        return new Session(email, PASSWORD, role, r.str("accessToken"));
    }

    public static Session login(String email, String password) {
        Res r = Api.anon().body(Json.obj("email", email, "password", password)).post("/api/auth/login");
        r.ok();
        return new Session(email, password, null, r.str("accessToken"));
    }

    public static String uniqueEmail(String role) {
        return "apit-" + role.toLowerCase().replace('_', '-') + "-" + USERS.incrementAndGet() + "-" + Config.RUN
                + "@example.com";
    }

    public synchronized String token() {
        if (System.currentTimeMillis() - issuedAtMs > RENEW_AFTER_MS) {
            relogin();
        }
        return token;
    }

    /** The current token without renewal (tests that check revocation hold on to an old one). */
    public String rawToken() {
        return token;
    }

    public synchronized void relogin() {
        Res r = Api.anon().body(Json.obj("email", email, "password", password)).post("/api/auth/login");
        r.ok();
        token = r.str("accessToken");
        issuedAtMs = System.currentTimeMillis();
    }

    /** Logs in again until the token's claims satisfy the condition (e.g. the vendor_id claim after approval). */
    public synchronized void reloginUntil(String what, Predicate<Session> condition, Duration max) {
        long deadline = System.currentTimeMillis() + max.toMillis();
        List<String> seen = new ArrayList<>();
        while (true) {
            relogin();
            if (condition.test(this)) {
                return;
            }
            seen.add(String.valueOf(Jwt.claims(token)));
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for %s for %s; last claims: %s", what, email, seen.getLast());
            }
            Api.sleep(700);
        }
    }

    public String claim(String name) {
        return Jwt.claim(token, name);
    }

    public String userId() {
        return claim("sub");
    }

    public String email() {
        return email;
    }

    public String password() {
        return password;
    }

    public String role() {
        return role;
    }

    public List<String> roles() {
        List<String> roles = new ArrayList<>();
        Jwt.claims(token).path("roles").forEach(node -> roles.add(node.asText()));
        return roles;
    }

    public static void assertHasRole(Session session, String role) {
        assertThat(session.roles()).as("roles of %s", session.email).contains(role);
    }

    @Override
    public String toString() {
        return email;
    }
}
