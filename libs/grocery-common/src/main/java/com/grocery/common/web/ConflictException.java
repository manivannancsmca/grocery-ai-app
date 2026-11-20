package com.grocery.common.web;

import org.springframework.http.HttpStatus;

/** 409 — the request conflicts with the current state (duplicate, stale version, wrong status). */
public class ConflictException extends ApiException {

    public ConflictException(String errorCode, String message) {
        super(HttpStatus.CONFLICT, errorCode, message);
    }
}
