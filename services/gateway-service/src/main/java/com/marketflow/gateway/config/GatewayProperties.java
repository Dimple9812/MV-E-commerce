package com.marketflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * @param services      logical service name → base URI (e.g. identity → http://identity-service:8081)
 * @param allowedOrigins browser origins permitted by CORS (exact match, no wildcards with credentials)
 */
@ConfigurationProperties(prefix = "marketflow.gateway")
public record GatewayProperties(
        Map<String, String> services,
        List<String> allowedOrigins,
        DataSize maxRequestSize,
        DataSize chatMaxRequestSize,
        Duration downstreamTimeout,
        RateLimit defaultRateLimit,
        RateLimit authRateLimit) {

    public record RateLimit(int replenishRate, int burstCapacity) {
    }

    public DataSize chatMaxRequestSize() {
        return chatMaxRequestSize == null ? DataSize.ofMegabytes(6) : chatMaxRequestSize;
    }

    /** The service's base URI with the WebSocket scheme (http → ws, https → wss) for upgrade routing. */
    public String websocketUri(String service) {
        String uri = uri(service);
        if (uri.startsWith("https://")) {
            return "wss://" + uri.substring("https://".length());
        }
        if (uri.startsWith("http://")) {
            return "ws://" + uri.substring("http://".length());
        }
        return uri;
    }

    public String uri(String service) {
        String uri = services.get(service);
        if (uri == null) {
            throw new IllegalStateException("No URI configured for service " + service);
        }
        return uri;
    }
}
