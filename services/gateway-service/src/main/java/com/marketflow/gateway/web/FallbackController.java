package com.marketflow.gateway.web;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Circuit-breaker fallback: a clear 503 instead of a hanging request or a raw connection error. */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/__fallback/{service}")
    public Mono<Void> fallback(@PathVariable String service, ServerWebExchange exchange) {
        String safe = service.replaceAll("[^a-z]", "");
        Throwable cause = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        // An open breaker rejects calls without trying; anything else is the downstream failure that counted
        // against the breaker, which operators need to see (the path is logged, never query strings or bodies).
        if (cause != null && !(cause instanceof CallNotPermittedException)) {
            log.warn("Upstream {} failed for {} {}: {}", safe, exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(), cause.toString());
        }
        exchange.getResponse().getHeaders().set("Retry-After", "5");
        return Problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable",
                "The " + safe + " service is temporarily unavailable. Please retry shortly.");
    }
}
