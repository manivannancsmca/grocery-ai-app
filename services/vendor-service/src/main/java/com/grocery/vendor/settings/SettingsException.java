package com.grocery.vendor.settings;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** The platform settings API's own errors, rendered by grocery-common as problem+json. */
public class SettingsException extends ApiException {

    private SettingsException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** 409: the settings changed since the admin loaded them (stale {@code expectedVersion}). */
    public static SettingsException changed() {
        return new SettingsException(HttpStatus.CONFLICT, "SETTINGS_CHANGED",
                "Someone else changed these settings. Reload them and try again.", Map.of());
    }

    /** 400 in the same shape as a Bean Validation failure, for rules that span a field's parts. */
    public static SettingsException invalid(String field, String message) {
        return new SettingsException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
