package com.marketflow.platform.web;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation ID helpers. The value travels as HTTP header, MDC entry and event envelope field. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9\\-_.]{8,64}$");

    private CorrelationId() {
    }

    public static boolean isSafe(String value) {
        return value != null && SAFE.matcher(value).matches();
    }

    public static String currentOrNew() {
        String current = MDC.get(MDC_KEY);
        return isSafe(current) ? current : UUID.randomUUID().toString();
    }

    public static void set(String value) {
        MDC.put(MDC_KEY, isSafe(value) ? value : UUID.randomUUID().toString());
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
