package com.marketflow.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Accepts a well-formed inbound correlation ID (set by the gateway) or creates one, exposes it in the MDC for
 * structured logs and echoes it back to the caller. Untrusted values that do not match the safe pattern are
 * replaced rather than logged (log-injection protection).
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId.set(request.getHeader(CorrelationId.HEADER));
        try {
            response.setHeader(CorrelationId.HEADER, CorrelationId.currentOrNew());
            chain.doFilter(request, response);
        } finally {
            CorrelationId.clear();
        }
    }
}
