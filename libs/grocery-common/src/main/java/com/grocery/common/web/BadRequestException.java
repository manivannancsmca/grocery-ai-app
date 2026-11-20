package com.grocery.common.web;

import org.springframework.http.HttpStatus;

/** 400 — a request parameter has an invalid value (e.g. unknown status filter). */
public class BadRequestException extends ApiException {

    public BadRequestException(String errorCode, String message) {
        super(HttpStatus.BAD_REQUEST, errorCode, message);
    }

    /** Parses an optional enum query parameter; blank → null, unknown value → 400 INVALID_PARAMETER. */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String parameter, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_PARAMETER", "Unknown " + parameter + " '" + value + "'");
        }
    }
}
