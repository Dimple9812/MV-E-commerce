package com.marketflow.payment.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * HMAC-SHA256 webhook signatures in the widely used {@code t=<unix seconds>,v1=<hex>} form, computed over
 * {@code "<t>.<raw body>"}. The timestamp is part of the signed content, so an old captured request cannot be
 * replayed after the tolerance window; inside the window, replays are stopped by the provider event ID table.
 */
public final class WebhookSignature {

    public static final String HEADER = "MarketFlow-Signature";

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    public enum Verdict { VALID, MALFORMED, BAD_SIGNATURE, EXPIRED }

    public static Verdict verify(String secret, String header, String body, Instant now, Duration tolerance) {
        if (header == null || body == null) {
            return Verdict.MALFORMED;
        }
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if (kv[0].equals("t")) {
                try {
                    t = Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return Verdict.MALFORMED;
                }
            } else if (kv[0].equals("v1")) {
                v1 = kv[1];
            }
        }
        if (t < 0 || v1 == null) {
            return Verdict.MALFORMED;
        }
        String expected = hmac(secret, t + "." + body);
        // constant-time comparison: no timing side channel on the signature
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), v1.getBytes(StandardCharsets.US_ASCII))) {
            return Verdict.BAD_SIGNATURE;
        }
        if (Math.abs(now.getEpochSecond() - t) > tolerance.toSeconds()) {
            return Verdict.EXPIRED;
        }
        return Verdict.VALID;
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
