package com.grocery.apitests.support;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Where the stack is and who the bootstrap admin is. Each value is read from a system property, then an environment
 * variable of the same name, then the default (the local Docker Compose stack seen from the host).
 */
public final class Config {

    public static final String GATEWAY_URL = trimSlash(value("GATEWAY_URL", "http://localhost:8080"));
    public static final String KAFKA_BOOTSTRAP = value("KAFKA_BOOTSTRAP", "localhost:9092");
    public static final String SCHEMA_REGISTRY_URL = trimSlash(value("SCHEMA_REGISTRY_URL", "http://localhost:8081"));
    public static final String ADMIN_EMAIL = value("ADMIN_EMAIL", value("BOOTSTRAP_ADMIN_EMAIL", "admin@grocery.local"));
    public static final String ADMIN_PASSWORD = value("ADMIN_PASSWORD",
            value("BOOTSTRAP_ADMIN_PASSWORD", "Admin@12345"));

    /** Optional: JDBC URL of the platform's MySQL (e.g. jdbc:mysql://localhost:3307/ai_db) for the tampered-body check. */
    public static final String MYSQL_URL = value("MYSQL_URL", "");
    public static final String MYSQL_USER = value("MYSQL_USER", "root");
    public static final String MYSQL_PASSWORD = value("MYSQL_PASSWORD", "root");

    /** Unique per run: every user, store, product and coupon of a run carries it, so runs never collide. */
    public static final String RUN = runId();
    /** Letters only (catalog tokens such as cuisines allow A-Z and _). */
    public static final String TAG = lettersOf(RUN);

    private Config() {
    }

    static String value(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            value = System.getenv(name);
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String runId() {
        String time = Long.toString(System.currentTimeMillis() / 1000, 36);
        String random = Integer.toString(new SecureRandom().nextInt(36 * 36), 36);
        return (time + random).toLowerCase(Locale.ROOT);
    }

    private static String lettersOf(String run) {
        StringBuilder out = new StringBuilder("q");
        for (char c : run.toCharArray()) {
            out.append(Character.isDigit(c) ? (char) ('a' + (c - '0')) : c);
        }
        return out.toString();
    }
}
