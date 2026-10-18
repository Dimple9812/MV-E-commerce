package com.marketflow.gateway.config;

import com.marketflow.gateway.web.Problems;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Edge authentication. The gateway rejects missing/invalid/expired JWTs and enforces coarse role prefixes
 * ({@code /api/v1/admin/**} → ADMIN, {@code /api/v1/vendor/**} → VENDOR) as defence in depth. Downstream
 * services re-validate the token and perform all fine-grained and ownership checks themselves.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final Set<String> KNOWN_ROLES = Set.of("CUSTOMER", "VENDOR", "ADMIN");

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // bearer tokens; refresh endpoint has its own guard
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(c -> { })
                .headers(h -> h
                        .frameOptions(f -> f.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .permissionsPolicy(p -> p.policy("camera=(), microphone=(), geolocation=()")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((exchange, ex) -> Problems.write(exchange, HttpStatus.UNAUTHORIZED,
                                "unauthorized", "Authentication required"))
                        .accessDeniedHandler((exchange, ex) -> Problems.write(exchange, HttpStatus.FORBIDDEN,
                                "forbidden", "You do not have permission to perform this action")))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(
                                new ReactiveJwtAuthenticationConverterAdapter(new RolesConverter())))
                        .authenticationEntryPoint((exchange, ex) -> Problems.write(exchange, HttpStatus.UNAUTHORIZED,
                                "unauthorized", "Invalid or expired access token")))
                .authorizeExchange(a -> a
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/__fallback/**").permitAll()
                        // Actuator lives on the separate management port (MANAGEMENT_PORT, never exposed by the
                        // ingress), so Prometheus can scrape it; on the public port no such endpoint exists.
                        .pathMatchers("/actuator/prometheus").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/products",
                                "/api/v1/categories/**", "/api/v1/categories", "/api/v1/vendors/*",
                                "/api/v1/vendors/*/products", "/api/v1/inventory/availability").permitAll()
                        // WebSocket handshakes cannot carry a bearer header in browsers; chat-service authenticates
                        // every STOMP session on CONNECT with the same JWT rules and checks the Origin.
                        .pathMatchers(HttpMethod.GET, "/ws/chat", "/ws/chat/**").permitAll()
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/vendor/**").hasRole("VENDOR")
                        .pathMatchers("/actuator/**").denyAll()
                        .anyExchange().authenticated())
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Requested-With",
                "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Location", "X-RateLimit-Remaining", "Retry-After"));
        cors.setAllowCredentials(true); // needed for the HttpOnly refresh cookie
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    /** Rate-limit key: the authenticated subject when present, otherwise the client IP. */
    @Bean
    KeyResolver principalOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    var addr = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (addr == null ? "unknown" : addr.getAddress().getHostAddress());
                }));
    }

    static final class RolesConverter implements Converter<Jwt, AbstractAuthenticationToken> {
        @Override
        public AbstractAuthenticationToken convert(Jwt jwt) {
            Object raw = jwt.getClaims().get("roles");
            Collection<?> roles = raw instanceof Collection<?> c ? c : List.of();
            List<GrantedAuthority> authorities = roles.stream().map(Objects::toString)
                    .filter(KNOWN_ROLES::contains)
                    .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
        }
    }
}
