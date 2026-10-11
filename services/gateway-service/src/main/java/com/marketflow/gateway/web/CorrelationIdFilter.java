package com.marketflow.gateway.web;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ensures every request carries a safe correlation ID: an inbound value is accepted only if it matches a strict
 * pattern, otherwise a new UUID is generated. Runs as a WebFilter (before security, so even 401s carry the ID)
 * and the value is forwarded downstream and echoed to the client.
 */
@Component
public class CorrelationIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9\\-_.]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String inbound = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = inbound != null && SAFE.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(h -> h.set(HEADER, id)))
                .build();
        mutated.getResponse().getHeaders().set(HEADER, id);
        return chain.filter(mutated).contextWrite(ctx -> ctx.put(HEADER, id));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** Strips hop-by-hop / spoofable identity headers a client might send. */
    @Component
    public static class StripInternalHeaders implements GlobalFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            return chain.filter(exchange.mutate().request(r -> r.headers(h -> {
                h.remove("X-User-Id");
                h.remove("X-User-Roles");
                h.remove("X-Internal-Call");
            })).build());
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 10;
        }
    }
}
