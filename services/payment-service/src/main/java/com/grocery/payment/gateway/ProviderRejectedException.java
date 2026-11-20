package com.grocery.payment.gateway;

/**
 * The provider answered 4xx: the request is wrong for good (unknown id, nothing left to refund, bad credentials).
 * Retrying the same request does not help.
 *
 * @see <a href="https://razorpay.com/docs/api/errors/">Razorpay errors: {@code {"error": {"code", "description"}}}</a>
 */
public class ProviderRejectedException extends RuntimeException {

    private final int httpStatus;
    private final String code;
    private final String description;

    public ProviderRejectedException(int httpStatus, String code, String description) {
        super("Provider rejected the request (" + httpStatus + " " + code + "): " + description);
        this.httpStatus = httpStatus;
        this.code = code == null ? "BAD_REQUEST_ERROR" : code;
        this.description = description;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }
}
