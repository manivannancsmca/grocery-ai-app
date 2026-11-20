package com.grocery.notification.email;

import com.grocery.notification.entity.NotificationType;
import org.springframework.stereotype.Component;

/**
 * The email of one notification (Phase 12 §5): subject = the notification title; a plain-text part and a simple HTML
 * part made from the same title, body and link. The link is the notification's app route made absolute with
 * {@code APP_BASE_URL} ({@code grocery.notification.email.app-base-url}). Everything in the HTML part is escaped
 * ({@link Html}); the text part is plain text. The security emails of password management say that they are always
 * sent (no "turn it off" footer), and their link says what it does.
 */
@Component
public class EmailRenderer {

    /** What the sender puts into the MIME message. {@code url} is null when the notification has no link. */
    public record RenderedEmail(String subject, String text, String html, String url) {
    }

    static final String BUTTON = "Open in Grocery";
    static final String RESET_BUTTON = "Choose a new password";
    static final String NOT_ME_BUTTON = "Reset your password";

    private final String baseUrl;

    public EmailRenderer(EmailProperties properties) {
        String base = properties.getAppBaseUrl() == null ? "" : properties.getAppBaseUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
    }

    public RenderedEmail render(String type, String title, String body, String link) {
        String subject = oneLine(title);
        String text = body == null ? "" : body.strip();
        String url = absolute(link);
        boolean security = isSecurity(type);
        // Security emails are sent whatever the settings, so their footer must not offer to turn them off.
        String footer = security
                ? "This is a security email about your Grocery account. It is sent whatever your notification settings."
                : "You get this email because email for " + category(type).label()
                        + " is on. You can turn it off in your notification settings in Grocery.";
        String button = button(type);

        StringBuilder plain = new StringBuilder(subject).append("\n\n");
        if (!text.isEmpty()) {
            plain.append(text).append("\n\n");
        }
        if (url != null) {
            plain.append(button).append(": ").append(url).append("\n\n");
        }
        plain.append("-- \n").append("Grocery\n").append(footer).append('\n');

        StringBuilder html = new StringBuilder(1024)
                .append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(Html.escape(subject)).append("</title></head>\n")
                .append("<body style=\"margin:0;padding:24px 12px;background:#f4f5f7;")
                .append("font-family:Arial,Helvetica,sans-serif;color:#1f2933\">\n")
                .append("<div style=\"max-width:560px;margin:0 auto;background:#ffffff;border-radius:8px;")
                .append("padding:24px\">\n")
                .append("<p style=\"margin:0 0 8px;font-size:13px;font-weight:bold;color:#2f855a\">Grocery</p>\n")
                .append("<h1 style=\"margin:0 0 12px;font-size:20px;line-height:1.3\">").append(Html.escape(subject))
                .append("</h1>\n");
        if (!text.isEmpty()) {
            html.append("<p style=\"margin:0 0 20px;font-size:15px;line-height:1.5;white-space:pre-line\">")
                    .append(Html.escape(text)).append("</p>\n");
        }
        if (url != null) {
            html.append("<p style=\"margin:0\"><a href=\"").append(Html.escape(url))
                    .append("\" style=\"display:inline-block;background:#2f855a;color:#ffffff;text-decoration:none;")
                    .append("padding:10px 18px;border-radius:6px;font-size:15px\">").append(Html.escape(button))
                    .append("</a></p>\n");
        }
        html.append("</div>\n<p style=\"max-width:560px;margin:12px auto 0;font-size:12px;color:#6b7280\">")
                .append(Html.escape(footer)).append("</p>\n</body></html>\n");
        return new RenderedEmail(subject, plain.toString(), html.toString(), url);
    }

    /** The app route made absolute ({@code APP_BASE_URL} + route); null when there is none. */
    String absolute(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        String route = link.strip();
        return baseUrl + (route.startsWith("/") ? route : "/" + route);
    }

    /** A subject is one line: line breaks and other control characters become spaces. */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder line = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            line.append(Character.isISOControl(c) ? ' ' : c);
        }
        return line.toString().strip();
    }

    private static EmailCategory category(String type) {
        NotificationType known = type(type);
        return known == null ? EmailCategory.ORDERS : EmailCategory.of(known);
    }

    /** The security emails of password management ({@link EmailProperties#SECURITY_TYPES}). */
    private static boolean isSecurity(String type) {
        NotificationType known = type(type);
        return known != null && EmailProperties.SECURITY_TYPES.contains(known);
    }

    /** The link's label: what it does for the security emails, "Open in Grocery" for every other email. */
    static String button(String type) {
        NotificationType known = type(type);
        if (known == NotificationType.PASSWORD_RESET_REQUESTED) {
            return RESET_BUTTON;
        }
        if (known == NotificationType.PASSWORD_CHANGED) {
            return NOT_ME_BUTTON;
        }
        return BUTTON;
    }

    private static NotificationType type(String type) {
        try {
            return NotificationType.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
