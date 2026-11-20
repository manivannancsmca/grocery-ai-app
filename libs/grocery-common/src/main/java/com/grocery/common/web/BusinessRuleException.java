package com.grocery.common.web;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** 422 — the request is well-formed but breaks a business rule (e.g. "store is closed"). */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String errorCode, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, errorCode, message);
    }

    /** With per-field problems, rendered like validation errors: {@code errors: [{field, message}]}. */
    public BusinessRuleException(String errorCode, String message, List<Map<String, String>> fieldErrors) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, errorCode, message, Map.of("errors", fieldErrors));
    }
}
