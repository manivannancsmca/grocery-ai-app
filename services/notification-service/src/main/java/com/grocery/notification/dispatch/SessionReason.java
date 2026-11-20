package com.grocery.notification.dispatch;

/** The {@code reason} of a {@code session} event; the server closes the stream right after it. */
public final class SessionReason {

    /** The access token expired: the client reconnects with a fresh token and its Last-Event-ID. */
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    /** The account was suspended: the client signs out. */
    public static final String REVOKED = "REVOKED";
    /** A newer stream of the same user replaced this one (more than {@code max-streams-per-user}). */
    public static final String REPLACED = "REPLACED";

    private SessionReason() {
    }
}
