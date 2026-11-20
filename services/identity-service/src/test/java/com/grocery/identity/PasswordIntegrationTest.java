package com.grocery.identity;

import com.grocery.identity.security.RefreshTokens;
import com.grocery.identity.security.TokenRevocationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Password management (contract §3): change while signed in, forgot (emailed link) and reset. The "email" is the
 * PasswordResetRequested outbox row, which carries the raw token for notification-service.
 */
class PasswordIntegrationTest extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "Fresh2026x";

    @Autowired
    DataSource dataSource;
    @Autowired
    StringRedisTemplate redis;

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    private MockHttpServletResponse change(String accessToken, String refreshCookie, String current, String next)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/password/change")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        if (refreshCookie != null) {
            request.cookie(new Cookie("grocery_rt", refreshCookie));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse forgot(String email) throws Exception {
        return mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email))).andReturn().getResponse();
    }

    private MockHttpServletResponse reset(String token, String newPassword) throws Exception {
        return mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"%s\",\"newPassword\":\"%s\"}".formatted(token, newPassword)))
                .andReturn().getResponse();
    }

    private int refresh(String cookie) throws Exception {
        return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("grocery_rt", cookie))
                .header("X-Requested-With", "XMLHttpRequest")).andReturn().getResponse().getStatus();
    }

    private String errorCode(MockHttpServletResponse response) throws Exception {
        return body(response).get("errorCode").asString();
    }

    /** The JSON payloads of the user's events of one type, oldest first (what Debezium publishes). */
    private List<JsonNode> events(String userId, String eventType) {
        return jdbc().sql("""
                        SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                        ORDER BY occurred_at, id""")
                .params(userId, eventType).query(String.class).list().stream().map(json::readTree).toList();
    }

    private List<String> audit(String userId) {
        return jdbc().sql("SELECT event_type FROM auth_audit WHERE user_id = UUID_TO_BIN(?) ORDER BY occurred_at")
                .param(userId).query(String.class).list();
    }

    @Test
    void changingThePasswordKeepsThisSessionAndSignsOutTheOthers() throws Exception {
        String email = uniqueEmail("change");
        MockHttpServletResponse registered = register(email, "CUSTOMER");
        String userId = body(registered).at("/user/id").asString();
        String thisSession = refreshCookie(registered);
        String otherSession = refreshCookie(login(email, PASSWORD));

        MockHttpServletResponse changed = change(accessToken(registered), thisSession, PASSWORD, NEW_PASSWORD);

        assertThat(changed.getStatus()).isEqualTo(204);
        assertThat(redis.hasKey(TokenRevocationService.KEY_PREFIX + userId))
                .as("gateway marker: access tokens issued before now die (the caller simply refreshes)").isTrue();
        assertThat(refresh(thisSession)).as("the caller's session stays").isEqualTo(200);
        assertThat(refresh(otherSession)).as("another device is signed out").isEqualTo(401);
        assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);
        assertThat(login(email, NEW_PASSWORD).getStatus()).isEqualTo(200);

        List<JsonNode> published = events(userId, "PasswordChanged");
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().get("method").asString()).isEqualTo("CHANGE");
        assertThat(published.getFirst().get("email").asString()).isEqualTo(email);
        assertThat(published.getFirst().get("fullName").asString()).isEqualTo("Test CUSTOMER");
        assertThat(published.getFirst().get("changedAt").asString()).isNotBlank();
        assertThat(audit(userId)).contains("PASSWORD_CHANGED");
        assertThat(jdbc().sql("""
                        SELECT COUNT(*) FROM refresh_token
                        WHERE user_id = UUID_TO_BIN(?) AND revoke_reason = 'PASSWORD_CHANGED'""")
                .param(userId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void withoutTheRefreshCookieEverySessionIsSignedOut() throws Exception {
        MockHttpServletResponse registered = register(uniqueEmail("nocookie"), "VENDOR");

        assertThat(change(accessToken(registered), null, PASSWORD, NEW_PASSWORD).getStatus()).isEqualTo(204);
        assertThat(refresh(refreshCookie(registered))).isEqualTo(401);
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndCountsTowardsTheLockout() throws Exception {
        String email = uniqueEmail("wrongcurrent");
        MockHttpServletResponse registered = register(email, "CUSTOMER");
        String userId = body(registered).at("/user/id").asString();
        String token = accessToken(registered);

        for (int i = 0; i < 4; i++) {
            MockHttpServletResponse wrong = change(token, null, "NotMine123", NEW_PASSWORD);
            assertThat(wrong.getStatus()).isEqualTo(400);
            assertThat(errorCode(wrong)).isEqualTo("CURRENT_PASSWORD_INCORRECT");
        }
        assertThat(login(email, "NotMine123").getStatus()).as("the 5th failure locks").isEqualTo(401);
        assertThat(login(email, PASSWORD).getStatus()).as("locked, even with the right password").isEqualTo(401);
        MockHttpServletResponse whileLocked = change(token, null, PASSWORD, NEW_PASSWORD);
        assertThat(whileLocked.getStatus()).isEqualTo(400);
        assertThat(errorCode(whileLocked)).isEqualTo("CURRENT_PASSWORD_INCORRECT");

        assertThat(events(userId, "PasswordChanged")).isEmpty();
        assertThat(audit(userId)).filteredOn("PASSWORD_CHANGE_FAILURE"::equals).hasSize(5);
    }

    @Test
    void theNewPasswordFollowsTheRegistrationRuleAndDiffersFromTheCurrentOne() throws Exception {
        MockHttpServletResponse registered = register(uniqueEmail("rule"), "CUSTOMER");
        String token = accessToken(registered);

        MockHttpServletResponse weak = change(token, null, PASSWORD, "letters-only");
        assertThat(weak.getStatus()).isEqualTo(400);
        assertThat(errorCode(weak)).isEqualTo("VALIDATION_FAILED");
        assertThat(body(weak).at("/errors/0/field").asString()).isEqualTo("newPassword");

        MockHttpServletResponse same = change(token, null, PASSWORD, PASSWORD);
        assertThat(same.getStatus()).isEqualTo(400);
        assertThat(errorCode(same)).isEqualTo("PASSWORD_UNCHANGED");

        MockHttpServletResponse anonymous = change(null, null, PASSWORD, NEW_PASSWORD);
        assertThat(anonymous.getStatus()).isEqualTo(401);
        assertThat(refresh(refreshCookie(registered))).as("nothing changed").isEqualTo(200);
    }

    @Test
    void forgotAnswersAlikeForEveryAddressAndTakesAtLeastTheMinimumTime() throws Exception {
        String email = uniqueEmail("forgot");
        String userId = body(register(email, "CUSTOMER")).at("/user/id").asString();

        long started = System.nanoTime();
        MockHttpServletResponse unknown = forgot(uniqueEmail("nobody"));
        Duration unknownTook = Duration.ofNanos(System.nanoTime() - started);
        started = System.nanoTime();
        MockHttpServletResponse known = forgot(email.toUpperCase());
        Duration knownTook = Duration.ofNanos(System.nanoTime() - started);

        for (MockHttpServletResponse response : List.of(unknown, known)) {
            assertThat(response.getStatus()).isEqualTo(202);
            assertThat(response.getContentAsString()).isEmpty();
        }
        assertThat(unknownTook).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(knownTook).isGreaterThanOrEqualTo(Duration.ofMillis(250));

        List<JsonNode> requested = events(userId, "PasswordResetRequested");
        assertThat(requested).hasSize(1);
        JsonNode event = requested.getFirst();
        String rawToken = event.get("token").asString();
        assertThat(event.get("email").asString()).isEqualTo(email);
        assertThat(event.get("fullName").asString()).isEqualTo("Test CUSTOMER");
        assertThat(rawToken).matches("[A-Za-z0-9_-]{43}");   // 32 random bytes, URL-safe base64 without padding
        assertThat(Duration.between(Instant.now(), Instant.parse(event.get("expiresAt").asString())))
                .isBetween(Duration.ofMinutes(29), Duration.ofMinutes(30));

        // Only the hash is stored, with who asked
        List<String> hashes = jdbc().sql("SELECT token_hash FROM password_reset_token WHERE user_id = UUID_TO_BIN(?)")
                .param(userId).query(String.class).list();
        assertThat(hashes).containsExactly(RefreshTokens.hash(rawToken));
        assertThat(audit(userId)).contains("PASSWORD_RESET_REQUESTED");
        assertThat(jdbc().sql("SELECT COUNT(*) FROM auth_audit WHERE event_type = 'PASSWORD_RESET_IGNORED' AND detail = 'unknown email'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void aSuspendedAccountGetsNoLink() throws Exception {
        String email = uniqueEmail("suspendedforgot");
        String userId = body(register(email, "CUSTOMER")).at("/user/id").asString();
        jdbc().sql("UPDATE user_account SET status = 'SUSPENDED' WHERE id = UUID_TO_BIN(?)").param(userId).update();

        assertThat(forgot(email).getStatus()).isEqualTo(202);
        assertThat(events(userId, "PasswordResetRequested")).isEmpty();
    }

    @Test
    void atMostThreeLinksPerAccountPerHourAndOnlyTheNewestWorks() throws Exception {
        String email = uniqueEmail("limit");
        String userId = body(register(email, "CUSTOMER")).at("/user/id").asString();

        for (int i = 0; i < 4; i++) {
            assertThat(forgot(email).getStatus()).isEqualTo(202);
        }

        List<String> tokens = events(userId, "PasswordResetRequested").stream()
                .map(event -> event.get("token").asString()).toList();
        assertThat(tokens).as("the 4th request in the hour does nothing").hasSize(3).doesNotHaveDuplicates();
        assertThat(audit(userId)).filteredOn("PASSWORD_RESET_IGNORED"::equals).hasSize(1);

        MockHttpServletResponse replaced = reset(tokens.get(1), NEW_PASSWORD);
        assertThat(replaced.getStatus()).as("a newer link replaced it").isEqualTo(400);
        assertThat(errorCode(replaced)).isEqualTo("RESET_TOKEN_INVALID");
        assertThat(reset(tokens.get(2), NEW_PASSWORD).getStatus()).isEqualTo(204);
    }

    @Test
    void simultaneousRequestsForOneAccountStillGetAtMostThreeLinks() throws Exception {
        String email = uniqueEmail("burst");
        String userId = body(register(email, "CUSTOMER")).at("/user/id").asString();

        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                statuses.add(pool.submit(() -> {
                    start.await();
                    return forgot(email).getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> status : statuses) {
                assertThat(status.get()).isEqualTo(202);
            }
        }

        assertThat(events(userId, "PasswordResetRequested")).as("the account row lock serialises the count").hasSize(3);
    }

    @Test
    void resettingSetsThePasswordUsesTheLinkLiftsTheLockoutAndSignsOutEverySession() throws Exception {
        String email = uniqueEmail("reset");
        MockHttpServletResponse registered = register(email, "RESTAURANT_OWNER");
        String userId = body(registered).at("/user/id").asString();
        String session = refreshCookie(registered);
        for (int i = 0; i < 5; i++) {
            login(email, "Forgotten99");   // locked now
        }
        forgot(email);
        String token = events(userId, "PasswordResetRequested").getFirst().get("token").asString();

        MockHttpServletResponse done = reset(token, NEW_PASSWORD);

        assertThat(done.getStatus()).isEqualTo(204);
        assertThat(done.getHeader("Set-Cookie")).as("nobody is signed in").isNull();
        assertThat(refresh(session)).as("every session is signed out").isEqualTo(401);
        assertThat(redis.hasKey(TokenRevocationService.KEY_PREFIX + userId)).isTrue();
        assertThat(login(email, NEW_PASSWORD).getStatus()).as("the lockout ended").isEqualTo(200);

        MockHttpServletResponse again = reset(token, "Another2026");
        assertThat(again.getStatus()).as("the link works once").isEqualTo(400);
        assertThat(errorCode(again)).isEqualTo("RESET_TOKEN_INVALID");

        List<JsonNode> changed = events(userId, "PasswordChanged");
        assertThat(changed).hasSize(1);
        assertThat(changed.getFirst().get("method").asString()).isEqualTo("RESET");
        assertThat(audit(userId)).contains("PASSWORD_RESET", "PASSWORD_RESET_FAILURE");
        assertThat(jdbc().sql("SELECT used_at IS NOT NULL FROM password_reset_token WHERE user_id = UUID_TO_BIN(?)")
                .param(userId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void unknownExpiredAndOutdatedLinksAreRefused() throws Exception {
        MockHttpServletResponse unknown = reset(RefreshTokens.newRawToken(), NEW_PASSWORD);
        assertThat(unknown.getStatus()).isEqualTo(400);
        assertThat(errorCode(unknown)).isEqualTo("RESET_TOKEN_INVALID");

        String email = uniqueEmail("expired");
        MockHttpServletResponse registered = register(email, "CUSTOMER");
        String userId = body(registered).at("/user/id").asString();
        forgot(email);
        String token = events(userId, "PasswordResetRequested").getFirst().get("token").asString();

        MockHttpServletResponse weak = reset(token, "short1");
        assertThat(weak.getStatus()).isEqualTo(400);
        assertThat(errorCode(weak)).isEqualTo("VALIDATION_FAILED");

        jdbc().sql("UPDATE password_reset_token SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE user_id = UUID_TO_BIN(?)")
                .param(userId).update();
        MockHttpServletResponse expired = reset(token, NEW_PASSWORD);
        assertThat(expired.getStatus()).isEqualTo(400);
        assertThat(errorCode(expired)).isEqualTo("RESET_TOKEN_INVALID");
        assertThat(login(email, PASSWORD).getStatus()).as("the password did not change").isEqualTo(200);

        // A link asked for before a change of password stops working with the change
        forgot(email);
        String beforeChange = events(userId, "PasswordResetRequested").getLast().get("token").asString();
        assertThat(change(accessToken(registered), null, PASSWORD, NEW_PASSWORD).getStatus()).isEqualTo(204);
        assertThat(errorCode(reset(beforeChange, "Another2026"))).isEqualTo("RESET_TOKEN_INVALID");
    }
}
