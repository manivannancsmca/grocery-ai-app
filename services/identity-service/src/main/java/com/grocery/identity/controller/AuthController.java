package com.grocery.identity.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.identity.config.IdentityProperties;
import com.grocery.identity.dto.AuthDtos.AuthResponse;
import com.grocery.identity.dto.AuthDtos.ChangePasswordRequest;
import com.grocery.identity.dto.AuthDtos.ForgotPasswordRequest;
import com.grocery.identity.dto.AuthDtos.LoginRequest;
import com.grocery.identity.dto.AuthDtos.RegisterRequest;
import com.grocery.identity.dto.AuthDtos.ResetPasswordRequest;
import com.grocery.identity.dto.AuthDtos.UserView;
import com.grocery.identity.exception.IdentityExceptions;
import com.grocery.identity.service.AuthService;
import com.grocery.identity.service.AuthService.Session;
import com.grocery.identity.service.ClientInfo;
import com.grocery.identity.service.PasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Authentication endpoints. The access token is returned in the body (kept in memory by the SPA);
 * the refresh token only ever travels in an httpOnly, SameSite=Strict cookie scoped to /api/auth.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String CSRF_HEADER = "X-Requested-With";
    private static final String CSRF_VALUE = "XMLHttpRequest";

    private final AuthService authService;
    private final PasswordService passwordService;
    private final IdentityProperties properties;

    public AuthController(AuthService authService, PasswordService passwordService, IdentityProperties properties) {
        this.authService = authService;
        this.passwordService = passwordService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return withSession(HttpStatus.CREATED, authService.register(request, ClientInfo.from(http)));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return withSession(HttpStatus.OK, authService.login(request, ClientInfo.from(http)));
    }

    /**
     * Cookie-authenticated, so it needs CSRF protection: SameSite=Strict on the cookie, CORS at the
     * gateway, and a custom header that a cross-site form cannot send.
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = "${grocery.identity.refresh-cookie.name:grocery_rt}", required = false) String refreshToken,
                                                @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith,
                                                HttpServletRequest http) {
        requireCsrfHeader(requestedWith);
        return withSession(HttpStatus.OK, authService.refresh(refreshToken, ClientInfo.from(http)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "${grocery.identity.refresh-cookie.name:grocery_rt}", required = false) String refreshToken,
                                       @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith,
                                       HttpServletRequest http) {
        requireCsrfHeader(requestedWith);
        authService.logout(refreshToken, ClientInfo.from(http));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    public UserView me(CurrentUser user) {
        return authService.currentUser(user.userId());
    }

    /**
     * Signed in (Bearer token, so no CSRF header is needed). The refresh cookie, sent along because its path is
     * /api/auth, only tells which session stays signed in; every other session of the account is signed out.
     */
    @PostMapping("/password/change")
    public ResponseEntity<Void> changePassword(CurrentUser user,
                                               @CookieValue(name = "${grocery.identity.refresh-cookie.name:grocery_rt}", required = false) String refreshToken,
                                               @Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletRequest http) {
        passwordService.change(user.userId(), refreshToken, request, ClientInfo.from(http));
        return ResponseEntity.noContent().build();
    }

    /** Public. Always 202, whether or not the address has an account (no account enumeration). */
    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest http) {
        passwordService.requestReset(request.email(), ClientInfo.from(http));
        return ResponseEntity.accepted().build();
    }

    /** Public: the token from the emailed link. Signs nobody in. */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest http) {
        passwordService.reset(request, ClientInfo.from(http));
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<AuthResponse> withSession(HttpStatus status, Session session) {
        AuthResponse body = new AuthResponse(session.accessToken().value(), "Bearer",
                session.accessToken().expiresInSeconds(), session.user());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.rawRefreshToken(), properties.refreshTokenTtl()).toString())
                .body(body);
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        IdentityProperties.RefreshCookie cookie = properties.refreshCookie();
        return ResponseCookie.from(cookie.name(), value)
                .httpOnly(true)
                .secure(cookie.secure())
                .sameSite(cookie.sameSite())
                .path(cookie.path())
                .maxAge(maxAge)
                .build();
    }

    private static void requireCsrfHeader(String requestedWith) {
        if (!CSRF_VALUE.equalsIgnoreCase(requestedWith)) {
            throw IdentityExceptions.csrfHeaderMissing();
        }
    }
}
