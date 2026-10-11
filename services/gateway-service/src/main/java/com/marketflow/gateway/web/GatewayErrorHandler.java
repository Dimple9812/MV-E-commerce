package com.marketflow.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Converts any error escaping the filter chain into problem JSON without leaking internals. */
@Component
@Order(-2) // before Boot's DefaultErrorWebExceptionHandler
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        String code = "bad_gateway";
        String detail = "Upstream service error";
        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode sc = rse.getStatusCode();
            status = HttpStatus.resolve(sc.value()) == null ? HttpStatus.INTERNAL_SERVER_ERROR
                    : HttpStatus.valueOf(sc.value());
            code = switch (status) {
                case NOT_FOUND -> "not_found";
                case PAYLOAD_TOO_LARGE -> "payload_too_large";
                case TOO_MANY_REQUESTS -> "too_many_requests";
                case GATEWAY_TIMEOUT -> "gateway_timeout";
                case SERVICE_UNAVAILABLE -> "service_unavailable";
                default -> status.is4xxClientError() ? "bad_request" : "gateway_error";
            };
            detail = status == HttpStatus.NOT_FOUND ? "No API route matches this path" : status.getReasonPhrase();
        } else {
            log.warn("Gateway error path={} cause={}", exchange.getRequest().getPath(), ex.getClass().getSimpleName());
        }
        return Problems.write(exchange, status, code, detail);
    }
}
