package com.grocery.ai.gateway;

/** The gateway (or the service behind it) could not be reached in time: no answer at all. */
public class GatewayUnavailableException extends RuntimeException {

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
