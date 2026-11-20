package com.grocery.common.web;

import org.springframework.http.HttpStatus;

/** 403 — authenticated, but not allowed to act on this resource (e.g. another vendor's product). */
public class ForbiddenOperationException extends ApiException {

    public ForbiddenOperationException(String errorCode, String message) {
        super(HttpStatus.FORBIDDEN, errorCode, message);
    }
}
