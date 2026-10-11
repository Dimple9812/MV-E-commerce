package com.marketflow.identity.application;

import com.marketflow.identity.config.IdentityProperties;
import com.marketflow.identity.domain.RefreshToken;
import com.marketflow.identity.persistence.RefreshTokenRepository;
import com.marketflow.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh-token rotation with reuse detection.
 *
 * <p>Every refresh revokes the presented token and issues a successor in the same family. If a token that was
 * already rotated is presented again, someone holds a copy (e.g. stolen), so the entire family is revoked and
 * the user must log in again.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository tokens;
    private final IdentityProperties props;

    public RefreshTokenService(RefreshTokenRepository tokens, IdentityProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    public record Rotation(UUID userId, String newRawToken) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String issueNewFamily(UUID userId) {
        return issue(userId, UUID.randomUUID()).raw;
    }

    /**
     * Uses {@code noRollbackFor} so that the family revocation performed on reuse detection is committed even
     * though the caller receives an error.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("Refresh token missing");
        }
        Instant now = Instant.now();
        RefreshToken current = tokens.findByHashForUpdate(SecureTokens.sha256(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected; revoked family userId={} tokens={}", current.getUserId(), revoked);
            throw ApiException.unauthorized("Refresh token is no longer valid");
        }
        if (!current.isUsable(now)) {
            throw ApiException.unauthorized("Refresh token expired");
        }
        Issued next = issue(current.getUserId(), current.getFamilyId());
        current.rotateTo(next.entity.getId(), now);
        return new Rotation(current.getUserId(), next.raw);
    }

    @Transactional
    public void revokeFamilyOf(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByHashForUpdate(SecureTokens.sha256(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(UUID userId) {
        tokens.revokeAllForUser(userId, Instant.now());
    }

    @Scheduled(cron = "0 41 * * * *")
    @Transactional
    public void purgeExpired() {
        tokens.deleteExpired(Instant.now().minus(Duration.ofDays(1)));
    }

    private record Issued(RefreshToken entity, String raw) {
    }

    private Issued issue(UUID userId, UUID family) {
        String raw = SecureTokens.newToken();
        RefreshToken entity = RefreshToken.issue(userId, SecureTokens.sha256(raw), family,
                Instant.now().plus(props.refreshTokenTtl()));
        tokens.save(entity);
        return new Issued(entity, raw);
    }
}
