package com.marketflow.identity.api;

import com.marketflow.identity.api.Dtos.LoginRequest;
import com.marketflow.identity.api.Dtos.PasswordResetConfirm;
import com.marketflow.identity.api.Dtos.PasswordResetRequest;
import com.marketflow.identity.api.Dtos.RegisterRequest;
import com.marketflow.identity.api.Dtos.TokenResponse;
import com.marketflow.identity.api.Dtos.UserView;
import com.marketflow.identity.application.AuthService;
import com.marketflow.identity.application.LoginThrottle;
import com.marketflow.identity.config.IdentityProperties;
import com.marketflow.platform.web.ApiException;
import com.marketflow.platform.web.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Authentication endpoints. The refresh token is only ever delivered as an HttpOnly, SameSite=Strict cookie
 * scoped to {@code /api/v1/auth}, so page JavaScript (and therefore XSS) cannot read it. Cookie-authenticated
 * endpoints additionally require the {@code X-Requested-With} header, which a cross-site form cannot set.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    static final String CSRF_HEADER = "X-Requested-With";

    private final AuthService auth;
    private final LoginThrottle throttle;
    private final IdentityProperties props;

    public AuthController(AuthService auth, LoginThrottle throttle, IdentityProperties props) {
        this.auth = auth;
        this.throttle = throttle;
        this.props = props;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a customer account (role CUSTOMER)")
    public ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        throttle.check("register", http.getRemoteAddr());
        AuthService.Session s = auth.register(req.email(), req.password(), req.fullName());
        return sessionResponse(HttpStatus.CREATED, s);
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        throttle.check("login", http.getRemoteAddr());
        return sessionResponse(HttpStatus.OK, auth.login(req.email(), req.password()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh-token cookie and obtain a new access token")
    public ResponseEntity<?> refresh(
            @CookieValue(name = "${marketflow.identity.cookie.name}", required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith) {
        requireCsrfHeader(requestedWith);
        try {
            return sessionResponse(HttpStatus.OK, auth.refresh(refreshToken));
        } catch (ApiException e) {
            // clear a dead cookie so the browser stops presenting it
            return ResponseEntity.status(e.status()).header(HttpHeaders.SET_COOKIE, clearCookie().toString())
                    .body(GlobalExceptionHandler.problem(e.status(), e.code(), e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "${marketflow.identity.cookie.name}", required = false) String refreshToken,
            @RequestHeader(name = CSRF_HEADER, required = false) String requestedWith) {
        requireCsrfHeader(requestedWith);
        auth.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearCookie().toString()).build();
    }

    @PostMapping("/password-reset/request")
    @Operation(summary = "Request a password-reset e-mail. Always returns 202 to prevent account discovery.")
    public ResponseEntity<Void> requestReset(@Valid @RequestBody PasswordResetRequest req, HttpServletRequest http) {
        throttle.check("reset", http.getRemoteAddr());
        auth.requestPasswordReset(req.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> confirmReset(@Valid @RequestBody PasswordResetConfirm req) {
        auth.confirmPasswordReset(req.token(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    private void requireCsrfHeader(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.forbidden("Missing " + CSRF_HEADER + " header");
        }
    }

    private ResponseEntity<TokenResponse> sessionResponse(HttpStatus status, AuthService.Session s) {
        ResponseCookie cookie = ResponseCookie.from(props.cookie().name(), s.refreshToken())
                .httpOnly(true)
                .secure(props.cookie().secure())
                .sameSite("Strict")
                .path(props.cookie().path())
                .maxAge(props.refreshTokenTtl())
                .build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(s.accessToken().value(), "Bearer", s.accessToken().expiresInSeconds(),
                        UserView.of(s.user())));
    }

    private ResponseCookie clearCookie() {
        return ResponseCookie.from(props.cookie().name(), "").httpOnly(true).secure(props.cookie().secure())
                .sameSite("Strict").path(props.cookie().path()).maxAge(Duration.ZERO).build();
    }
}
