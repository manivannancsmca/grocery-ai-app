package com.grocery.identity.service;

import jakarta.servlet.http.HttpServletRequest;

/** Who is calling, for the audit trail (the gateway sets X-Forwarded-For). */
public record ClientInfo(String ipAddress, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return new ClientInfo(truncate(ip, 64), truncate(request.getHeader("User-Agent"), 255));
    }

    public static ClientInfo system() {
        return new ClientInfo(null, "system");
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
