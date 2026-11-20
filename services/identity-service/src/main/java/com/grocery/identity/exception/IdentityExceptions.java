package com.grocery.identity.exception;

import com.grocery.common.web.ApiException;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import com.grocery.common.web.ForbiddenOperationException;
import com.grocery.common.web.NotFoundException;
import org.springframework.http.HttpStatus;

/** Identity-specific errors; each maps to a stable errorCode in the API contract. */
public final class IdentityExceptions {

    private IdentityExceptions() {
    }

    /** Same answer for unknown email, wrong password and temporary lockout (no account enumeration). */
    public static class InvalidCredentialsException extends ApiException {
        public InvalidCredentialsException() {
            super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Invalid email or password, or the account is temporarily locked.");
        }
    }

    public static class RefreshTokenInvalidException extends ApiException {
        public RefreshTokenInvalidException() {
            super(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "Your session has ended. Please sign in again.");
        }
    }

    /** Two refreshes raced (e.g. two tabs). The client should simply retry once. */
    public static class RefreshRaceException extends ConflictException {
        public RefreshRaceException() {
            super("REFRESH_RACE", "The session was refreshed concurrently. Retry the request.");
        }
    }

    public static class AccountSuspendedException extends ForbiddenOperationException {
        public AccountSuspendedException() {
            super("ACCOUNT_SUSPENDED", "This account is suspended. Please contact support.");
        }
    }

    /** Change of password: wrong current password, or the account is locked after too many wrong passwords. */
    public static class CurrentPasswordIncorrectException extends ApiException {
        public CurrentPasswordIncorrectException() {
            super(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INCORRECT",
                    "The current password is not correct, or the account is temporarily locked.");
        }
    }

    /** Reset link unknown, expired, already used, or its account is no longer active (one answer for all). */
    public static class ResetTokenInvalidException extends ApiException {
        public ResetTokenInvalidException() {
            super(HttpStatus.BAD_REQUEST, "RESET_TOKEN_INVALID",
                    "This password reset link is invalid or has expired. Ask for a new one.");
        }
    }

    public static BadRequestException passwordUnchanged() {
        return new BadRequestException("PASSWORD_UNCHANGED", "The new password must be different from the current one");
    }

    public static ConflictException emailAlreadyRegistered() {
        return new ConflictException("EMAIL_ALREADY_REGISTERED", "Email is already registered");
    }

    public static NotFoundException userNotFound() {
        return new NotFoundException("USER_NOT_FOUND", "User not found");
    }

    public static BusinessRuleException cannotSuspendSelf() {
        return new BusinessRuleException("CANNOT_SUSPEND_SELF", "You cannot suspend your own account");
    }

    public static ForbiddenOperationException csrfHeaderMissing() {
        return new ForbiddenOperationException("CSRF_HEADER_MISSING", "Missing X-Requested-With header");
    }
}
