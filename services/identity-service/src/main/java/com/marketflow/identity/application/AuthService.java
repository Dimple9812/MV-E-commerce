package com.marketflow.identity.application;

import com.marketflow.identity.config.IdentityProperties;
import com.marketflow.identity.domain.PasswordResetToken;
import com.marketflow.identity.domain.User;
import com.marketflow.identity.persistence.PasswordResetTokenRepository;
import com.marketflow.identity.persistence.UserRepository;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final RefreshTokenService refreshTokens;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;
    private final MailGateway mail;
    private final IdentityProperties props;
    private final MeterRegistry meters;
    /** Used to equalise timing between "unknown e-mail" and "wrong password" (user enumeration). */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordResetTokenRepository resetTokens,
                       PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens,
                       RefreshTokenService refreshTokens, OutboxWriter outbox, AuditRecorder audit,
                       MailGateway mail, IdentityProperties props, MeterRegistry meters) {
        this.users = users;
        this.resetTokens = resetTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.outbox = outbox;
        this.audit = audit;
        this.mail = mail;
        this.props = props;
        this.meters = meters;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-" + UUID.randomUUID());
    }

    public record Session(User user, AccessTokenIssuer.IssuedToken accessToken, String refreshToken) {
    }

    @Transactional
    public Session register(String email, String password, String fullName) {
        PasswordPolicy.validate(password);
        String normalized = User.normalizeEmail(email);
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw ApiException.conflict("email_taken", "An account with this e-mail already exists");
        }
        User user = users.save(User.register(normalized, passwordEncoder.encode(password), fullName));
        outbox.append(IdentityEvents.USER_TOPIC, "UserRegistered", 1, IdentityEvents.AGGREGATE,
                user.getId().toString(),
                new IdentityEvents.UserRegistered(user.getId(), user.getEmail(), user.getFullName(),
                        user.getCreatedAt()));
        audit.record(user.getId(), "CUSTOMER", "user.registered", "User", user.getId().toString(), Map.of());
        meters.counter("identity.registrations").increment();
        return new Session(user, accessTokens.issue(user), refreshTokens.issueNewFamily(user.getId()));
    }

    /**
     * Failed attempts are committed (noRollbackFor) so the lockout counter and audit event survive the 401.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session login(String email, String password) {
        Instant now = Instant.now();
        User user = users.findByEmail(User.normalizeEmail(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            meters.counter("identity.logins", "outcome", "failure").increment();
            throw invalidCredentials();
        }
        if (user.isLocked(now)) {
            audit.record(user.getId(), null, "auth.login", "User", user.getId().toString(), "LOCKED", Map.of());
            meters.counter("identity.logins", "outcome", "locked").increment();
            throw new ApiException(HttpStatus.LOCKED, "account_locked",
                    "Too many failed attempts. The account is temporarily locked.");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(props.maxFailedLogins(), props.lockDuration(), now);
            audit.record(user.getId(), null, "auth.login", "User", user.getId().toString(), "FAILURE", Map.of());
            meters.counter("identity.logins", "outcome", "failure").increment();
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            throw ApiException.forbidden("This account has been deactivated");
        }
        user.recordSuccessfulLogin();
        audit.record(user.getId(), null, "auth.login", "User", user.getId().toString(), "SUCCESS", Map.of());
        meters.counter("identity.logins", "outcome", "success").increment();
        return new Session(user, accessTokens.issue(user), refreshTokens.issueNewFamily(user.getId()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
        User user = users.findById(rotation.userId())
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (!user.isActive()) {
            refreshTokens.revokeAll(user.getId());
            throw ApiException.unauthorized("Account deactivated");
        }
        // roles are re-read from the DB, so a newly granted VENDOR role appears on the next refresh
        return new Session(user, accessTokens.issue(user), rotation.newRawToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokens.revokeFamilyOf(rawRefreshToken);
    }

    /** Always succeeds from the caller's point of view so the endpoint cannot be used to discover accounts. */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmail(User.normalizeEmail(email)).filter(User::isActive).ifPresent(user -> {
            String raw = SecureTokens.newToken();
            resetTokens.save(PasswordResetToken.create(user.getId(), SecureTokens.sha256(raw),
                    Instant.now().plus(props.passwordResetTtl())));
            audit.record(user.getId(), null, "auth.password_reset_requested", "User", user.getId().toString(),
                    Map.of());
            String link = props.passwordResetUrl() + "?token=" + raw;
            String to = user.getEmail();
            // send only after commit: never e-mail a token that does not exist
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mail.send(to, "Reset your MarketFlow password",
                            "Use this link within " + props.passwordResetTtl().toMinutes()
                                    + " minutes to choose a new password:\n\n" + link
                                    + "\n\nIf you did not request this, ignore this e-mail.");
                }
            });
        });
    }

    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        PasswordPolicy.validate(newPassword);
        Instant now = Instant.now();
        PasswordResetToken token = resetTokens.findByHashForUpdate(SecureTokens.sha256(rawToken))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> ApiException.badRequest("invalid_token", "The reset link is invalid or expired"));
        token.markUsed(now);
        User user = users.findById(token.getUserId()).orElseThrow(() -> ApiException.notFound("User"));
        user.changePassword(passwordEncoder.encode(newPassword));
        user.clearLockout();
        refreshTokens.revokeAll(user.getId());
        outbox.append(IdentityEvents.USER_TOPIC, "PasswordChanged", 1, IdentityEvents.AGGREGATE,
                user.getId().toString(), new IdentityEvents.PasswordChanged(user.getId(), now));
        audit.record(user.getId(), null, "auth.password_reset_completed", "User", user.getId().toString(), Map.of());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("invalid_password", "Current password is incorrect");
        }
        PasswordPolicy.validate(newPassword);
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAll(userId);
        outbox.append(IdentityEvents.USER_TOPIC, "PasswordChanged", 1, IdentityEvents.AGGREGATE,
                userId.toString(), new IdentityEvents.PasswordChanged(userId, Instant.now()));
        audit.record(userId, null, "auth.password_changed", "User", userId.toString(), Map.of());
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("Invalid e-mail or password");
    }
}
