package com.grocery.common.web;

import org.springframework.http.HttpStatus;

/** 404 — the resource does not exist (or the caller is not allowed to know it exists). */
public class NotFoundException extends ApiException {

    public NotFoundException(String errorCode, String message) {
        super(HttpStatus.NOT_FOUND, errorCode, message);
    }
}
