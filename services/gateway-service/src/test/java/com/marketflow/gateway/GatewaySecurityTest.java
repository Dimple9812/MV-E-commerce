package com.marketflow.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Edge-security behaviour. A stub decoder accepts the literal tokens "customer-token"/"vendor-token"/"admin-token";
 * everything else is rejected as a bad JWT. Downstream URIs point at a closed port so allowed requests reach the
 * circuit-breaker fallback (503) rather than a real service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "marketflow.gateway.services.identity=http://127.0.0.1:1",
        "marketflow.gateway.services.catalog=http://127.0.0.1:1",
        "marketflow.gateway.services.order=http://127.0.0.1:1",
        "marketflow.gateway.services.vendor=http://127.0.0.1:1",
        "management.server.port=0",
        "logging.structured.format.console="
})
class GatewaySecurityTest {

    @TestConfiguration
    static class StubDecoder {
        @Bean
        @Primary
        ReactiveJwtDecoder stubJwtDecoder() {
            return token -> switch (token) {
                case "customer-token" -> Mono.just(jwt("CUSTOMER"));
                case "vendor-token" -> Mono.just(jwt("CUSTOMER", "VENDOR"));
                case "admin-token" -> Mono.just(jwt("CUSTOMER", "ADMIN"));
                default -> Mono.error(new BadJwtException("bad token"));
            };
        }

        private static Jwt jwt(String... roles) {
            return Jwt.withTokenValue("t").header("alg", "RS256").subject("11111111-1111-1111-1111-111111111111")
                    .claim("roles", List.of(roles)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                    .build();
        }
    }

    @Autowired
    WebTestClient web;

    @Test
    void protectedRouteWithoutTokenIs401ProblemJson() {
        web.get().uri("/api/v1/orders").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.code").isEqualTo("unauthorized");
    }

    @Test
    void invalidTokenIs401() {
        web.get().uri("/api/v1/orders").header("Authorization", "Bearer forged").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void customerCannotReachAdminOrVendorRoutes() {
        web.get().uri("/api/v1/admin/users").header("Authorization", "Bearer customer-token").exchange()
                .expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("forbidden");
        web.get().uri("/api/v1/vendor/products").header("Authorization", "Bearer customer-token").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void vendorCannotReachAdminRoutes() {
        web.get().uri("/api/v1/admin/vendors").header("Authorization", "Bearer vendor-token").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void authorisedRequestToUnavailableServiceGetsFallback503() {
        web.get().uri("/api/v1/admin/users").header("Authorization", "Bearer admin-token").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("service_unavailable");
    }

    @Test
    void publicCatalogReadsNeedNoToken() {
        web.get().uri("/api/v1/products").exchange().expectStatus().isEqualTo(503); // routed, not 401
    }

    @Test
    void unsafeCorrelationIdIsReplacedAndSafeOneIsEchoed() {
        web.get().uri("/api/v1/orders").header("X-Correlation-Id", "bad value <script>injected").exchange()
                .expectHeader().value("X-Correlation-Id", v -> org.assertj.core.api.Assertions.assertThat(v)
                        .doesNotContain("injected").hasSize(36));
        web.get().uri("/api/v1/orders").header("X-Correlation-Id", "trace-abc-12345").exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "trace-abc-12345");
    }

    @Test
    void corsAllowsConfiguredOriginOnly() {
        web.options().uri("/api/v1/auth/login").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST").exchange()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
        web.options().uri("/api/v1/auth/login").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void securityHeadersArePresent() {
        web.get().uri("/api/v1/orders").exchange()
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().exists("Content-Security-Policy");
    }

    @Test
    void unknownRouteIs404ProblemWithCorrelationId() {
        web.get().uri("/api/v1/does-not-exist").header("Authorization", "Bearer customer-token").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("not_found")
                .jsonPath("$.correlationId").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .isNotEqualTo("null"));
    }

    @Test
    void oversizedBodyIsRejected() {
        byte[] big = new byte[3 * 1024 * 1024];
        web.post().uri("/api/v1/auth/login").header("Content-Type", "application/json")
                .header("Content-Length", String.valueOf(big.length)).bodyValue(big).exchange()
                .expectStatus().isEqualTo(413);
    }
}
