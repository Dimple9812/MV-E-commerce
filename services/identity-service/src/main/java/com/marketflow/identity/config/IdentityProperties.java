package com.marketflow.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "marketflow.identity")
public record IdentityProperties(
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        /* PKCS#8 PEM. When blank an ephemeral key is generated (development only; tokens die on restart). */
        String jwtPrivateKeyPem,
        int bcryptStrength,
        int maxFailedLogins,
        Duration lockDuration,
        int loginAttemptsPerIpPerMinute,
        Duration passwordResetTtl,
        String passwordResetUrl,
        Cookie cookie,
        BootstrapAdmin bootstrapAdmin) {

    public record Cookie(String name, boolean secure, String path) {
    }

    public record BootstrapAdmin(String email, String password, String fullName) {
    }
}
