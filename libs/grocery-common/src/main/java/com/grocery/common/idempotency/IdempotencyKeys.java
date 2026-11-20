package com.grocery.common.idempotency;

import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.BusinessRuleException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key} support for API calls that must happen at most once (create order, refund, …).
 * The client sends a unique key per logical operation and repeats it on retries; the server answers every
 * retry with the result of the first call instead of doing the work again.
 *
 * <p>Required table (in the service's own Flyway migration):
 * <pre>
 * CREATE TABLE idempotency_key (
 *   scope VARCHAR(100) NOT NULL, idem_key VARCHAR(100) NOT NULL, request_hash CHAR(64) NOT NULL,
 *   result_ref VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL,
 *   PRIMARY KEY (scope, idem_key));
 * </pre>
 *
 * <p>Usage — the claim runs in the SAME transaction as the work, with a result id chosen before the work:
 * <pre>{@code
 * String key = IdempotencyKeys.requireKey(header);
 * String hash = idempotencyKeys.hash(request);
 * UUID orderId = Ids.newId();
 * Optional<String> earlier = idempotencyKeys.claim("order:" + customerId, key, hash, orderId.toString());
 * if (earlier.isPresent()) return load(earlier.get());   // a retry: same result, nothing done again
 * ... create order orderId ...                            // commits together with the claim
 * }</pre>
 * Two concurrent calls with the same key are serialised by the primary key: the second waits until the first
 * commits and then receives its result (or, if the first rolled back, does the work itself). Reusing a key for a
 * different request is 422 {@code IDEMPOTENCY_KEY_REUSED}.
 */
public class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";
    public static final int MAX_KEY_LENGTH = 100;
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8," + MAX_KEY_LENGTH + "}$");

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO idempotency_key (scope, idem_key, request_hash, result_ref, created_at)
            VALUES (:scope, :key, :hash, :resultRef, :createdAt)
            """;
    private static final String SELECT_SQL = """
            SELECT request_hash, result_ref FROM idempotency_key
            WHERE scope = :scope AND idem_key = :key FOR SHARE
            """;
    private static final String FIND_SQL = """
            SELECT request_hash, result_ref FROM idempotency_key WHERE scope = :scope AND idem_key = :key
            """;

    /** What an earlier call with the same key stored. */
    public record Entry(String requestHash, String resultRef) {
    }

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public IdempotencyKeys(JdbcClient jdbcClient, JsonMapper jsonMapper, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * Validates the header value: required, 8–100 characters of {@code A-Z a-z 0-9 . _ : -} (a UUID fits).
     *
     * @throws BadRequestException 400 IDEMPOTENCY_KEY_REQUIRED or IDEMPOTENCY_KEY_INVALID
     */
    public static String requireKey(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_REQUIRED",
                    "The " + HEADER + " header is required for this request");
        }
        String key = headerValue.strip();
        if (!KEY.matcher(key).matches()) {
            throw new BadRequestException("IDEMPOTENCY_KEY_INVALID", "The " + HEADER
                    + " header must be 8-" + MAX_KEY_LENGTH + " letters, digits, '.', '_', ':' or '-'");
        }
        return key;
    }

    /** SHA-256 (hex) of the request serialised as JSON: equal requests give equal hashes. */
    public String hash(Object request) {
        String json = request == null ? "null" : jsonMapper.writeValueAsString(request);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Claims {@code key} within {@code scope} for a new result {@code resultRef}, inside the caller's transaction.
     *
     * @return empty when the key is new (the caller does the work and commits); otherwise the result reference
     *         stored by the earlier call with the same key and the same request
     * @throws BusinessRuleException 422 IDEMPOTENCY_KEY_REUSED when the key was used for a different request
     * @throws IllegalStateException if no transaction is active
     */
    public Optional<String> claim(String scope, String key, String requestHash, String resultRef) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("IdempotencyKeys.claim must run inside the transaction that does the work");
        }
        int inserted = jdbcClient.sql(INSERT_SQL)
                .param("scope", scope)
                .param("key", key)
                .param("hash", requestHash)
                .param("resultRef", resultRef)
                .param("createdAt", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        // A locking read always sees the latest committed row, whatever the transaction's snapshot.
        Entry earlier = jdbcClient.sql(SELECT_SQL).param("scope", scope).param("key", key)
                .query((rs, n) -> new Entry(rs.getString(1), rs.getString(2)))
                .optional()
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + scope + "/" + key));
        return Optional.of(checked(earlier, requestHash));
    }

    /**
     * Non-locking lookup, for a fast answer to retries before doing any slow work. Same 422 rule as
     * {@link #claim}.
     */
    public Optional<String> find(String scope, String key, String requestHash) {
        return jdbcClient.sql(FIND_SQL).param("scope", scope).param("key", key)
                .query((rs, n) -> new Entry(rs.getString(1), rs.getString(2)))
                .optional()
                .map(earlier -> checked(earlier, requestHash));
    }

    private static String checked(Entry earlier, String requestHash) {
        if (!earlier.requestHash().equals(requestHash)) {
            throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                    "This " + HEADER + " was already used for a different request");
        }
        return earlier.resultRef();
    }
}
