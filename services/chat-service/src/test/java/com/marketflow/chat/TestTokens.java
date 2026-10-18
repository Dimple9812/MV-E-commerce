package com.marketflow.chat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Real RS256 tokens for WebSocket tests: a key pair generated per test run replaces identity-service's JWKS.
 * Same claim layout as AccessTokenIssuer (sub, roles, name, email, iss, aud, exp).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

    static final RSAKey KEY;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    JwtDecoder jwtDecoder() throws Exception {
        return NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
    }

    public static String token(UUID userId, String name, Duration ttl, String... roles) {
        Instant exp = Instant.now().plus(ttl);
        Instant issued = ttl.isNegative() ? exp.minusSeconds(900) : Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("https://marketflow.dev/identity")
                .audience(List.of("marketflow-api")).subject(userId.toString()).issuedAt(issued).notBefore(issued)
                .expiresAt(exp).claim("roles", List.of(roles)).claim("name", name)
                .claim("email", "u-" + userId.toString().substring(0, 8) + "@example.test").build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(), claims))
                .getTokenValue();
    }
}
