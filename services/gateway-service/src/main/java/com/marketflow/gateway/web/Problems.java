package com.marketflow.gateway.web;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** Writes RFC 9457 problem JSON consistent with the downstream services' format. */
public final class Problems {

    private Problems() {
    }

    public static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.getHeaders().set("WWW-Authenticate", "Bearer");
        }
        String correlationId = response.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        if (correlationId == null) {
            correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        }
        String body = "{\"type\":\"https://marketflow.dev/problems/" + code + "\",\"title\":\""
                + status.getReasonPhrase() + "\",\"status\":" + status.value() + ",\"detail\":\"" + escape(detail)
                + "\",\"code\":\"" + code + "\",\"correlationId\":\"" + escape(String.valueOf(correlationId))
                + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
