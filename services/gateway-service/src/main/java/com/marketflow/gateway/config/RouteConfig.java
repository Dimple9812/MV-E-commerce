package com.marketflow.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.route.builder.UriSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;


/**
 * The complete public API surface. More specific paths are declared before broader ones that would also match.
 *
 * <p>Every route gets: request-size limit, Redis rate limit, circuit breaker with a problem-JSON fallback.
 * Only GET requests are retried (idempotent); POSTs are never retried by the gateway — idempotency of writes
 * is handled end-to-end with Idempotency-Key headers instead.
 */
@Configuration
public class RouteConfig {

    private final GatewayProperties props;

    public RouteConfig(GatewayProperties props) {
        this.props = props;
    }

    @Bean
    @Primary
    RedisRateLimiter defaultRateLimiter() {
        return new RedisRateLimiter(props.defaultRateLimit().replenishRate(), props.defaultRateLimit().burstCapacity());
    }

    @Bean
    RedisRateLimiter authRateLimiter() {
        return new RedisRateLimiter(props.authRateLimit().replenishRate(), props.authRateLimit().burstCapacity());
    }

    @Bean
    RouteLocator marketflowRoutes(RouteLocatorBuilder builder, KeyResolver principalOrIpKeyResolver) {
        return builder.routes()
                // ---- identity ----
                .route("identity-auth", r -> r.path("/api/v1/auth/**")
                        .filters(f -> common(f, "identity", principalOrIpKeyResolver, authRateLimiter()))
                        .uri(props.uri("identity")))
                .route("identity", r -> r.path("/api/v1/users/**", "/api/v1/admin/users/**")
                        .filters(f -> common(f, "identity", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("identity")))
                // ---- reviews (before catalog: /products/{id}/reviews) ----
                .route("reviews", r -> r.path("/api/v1/products/*/reviews/**", "/api/v1/products/*/reviews",
                                "/api/v1/products/*/rating", "/api/v1/reviews/**", "/api/v1/admin/reviews/**")
                        .filters(f -> common(f, "review", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("review")))
                // ---- catalog (vendor storefront products before generic vendor routes) ----
                .route("catalog", r -> r.path("/api/v1/products/**", "/api/v1/products",
                                "/api/v1/categories/**", "/api/v1/categories", "/api/v1/vendors/*/products",
                                "/api/v1/vendor/products/**", "/api/v1/vendor/products",
                                "/api/v1/admin/products/**", "/api/v1/admin/categories/**",
                                "/api/v1/admin/categories")
                        .filters(f -> common(f, "catalog", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("catalog")))
                // ---- inventory ----
                .route("inventory", r -> r.path("/api/v1/vendor/inventory/**", "/api/v1/vendor/inventory",
                                "/api/v1/inventory/**", "/api/v1/admin/inventory/**")
                        .filters(f -> common(f, "inventory", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("inventory")))
                // ---- vendor ----
                .route("vendor", r -> r.path("/api/v1/vendors/**", "/api/v1/vendor/profile",
                                "/api/v1/vendor/earnings/**", "/api/v1/vendor/earnings", "/api/v1/vendor/dashboard",
                                "/api/v1/admin/vendors/**", "/api/v1/admin/vendors", "/api/v1/admin/payouts/**")
                        .filters(f -> common(f, "vendor", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("vendor")))
                // ---- orders ----
                .route("orders", r -> r.path("/api/v1/orders/**", "/api/v1/orders", "/api/v1/vendor/suborders/**",
                                "/api/v1/vendor/suborders", "/api/v1/admin/orders/**", "/api/v1/admin/orders",
                                "/api/v1/admin/sagas/**")
                        .filters(f -> common(f, "order", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("order")))
                // ---- payments (webhooks are public but signature-verified by payment-service) ----
                .route("payments", r -> r.path("/api/v1/payments/**", "/api/v1/admin/payments/**",
                                "/api/v1/admin/payments")
                        .filters(f -> common(f, "payment", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("payment")))
                // ---- notifications ----
                .route("notifications", r -> r.path("/api/v1/notifications/**", "/api/v1/notifications",
                                "/api/v1/admin/notifications/**")
                        .filters(f -> common(f, "notification", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("notification")))
                // ---- analytics / audit ----
                .route("analytics", r -> r.path("/api/v1/admin/analytics/**", "/api/v1/vendor/analytics/**",
                                "/api/v1/admin/audit/**", "/api/v1/admin/audit")
                        .filters(f -> common(f, "analytics", principalOrIpKeyResolver, defaultRateLimiter()))
                        .uri(props.uri("analytics")))
                .build();
    }

    private UriSpec common(GatewayFilterSpec f, String service, KeyResolver keys, RedisRateLimiter limiter) {
        return f.setRequestSize(props.maxRequestSize())
                .requestRateLimiter(c -> c.setRateLimiter(limiter).setKeyResolver(keys)
                        .setDenyEmptyKey(false))
                .circuitBreaker(c -> c.setName(service).setFallbackUri("forward:/__fallback/" + service)
                        .setStatusCodes(java.util.Set.of("502", "503", "504")))
                .retry(rc -> rc.setRetries(2).setMethods(HttpMethod.GET)
                        .setStatuses(org.springframework.http.HttpStatus.BAD_GATEWAY,
                                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                        .setBackoff(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(800), 2, true));
    }
}
