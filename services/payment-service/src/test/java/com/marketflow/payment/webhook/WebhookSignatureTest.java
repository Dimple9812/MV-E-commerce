package com.marketflow.payment.webhook;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_123";
    private static final Duration TOL = Duration.ofMinutes(5);
    private final Instant now = Instant.parse("2026-05-01T12:00:00Z");

    @Test
    void validSignatureVerifies() {
        String body = "{\"id\":\"evt_1\"}";
        String header = WebhookSignature.sign(SECRET, now.getEpochSecond(), body);
        assertThat(WebhookSignature.verify(SECRET, header, body, now, TOL)).isEqualTo(WebhookSignature.Verdict.VALID);
    }

    @Test
    void tamperedBodyOrWrongSecretFails() {
        String header = WebhookSignature.sign(SECRET, now.getEpochSecond(), "{\"amount\":1}");
        assertThat(WebhookSignature.verify(SECRET, header, "{\"amount\":1000}", now, TOL))
                .isEqualTo(WebhookSignature.Verdict.BAD_SIGNATURE);
        assertThat(WebhookSignature.verify("other", header, "{\"amount\":1}", now, TOL))
                .isEqualTo(WebhookSignature.Verdict.BAD_SIGNATURE);
    }

    @Test
    void oldTimestampIsRejectedEvenWithValidSignature() {
        long old = now.minus(Duration.ofMinutes(6)).getEpochSecond();
        String header = WebhookSignature.sign(SECRET, old, "{}");
        assertThat(WebhookSignature.verify(SECRET, header, "{}", now, TOL)).isEqualTo(WebhookSignature.Verdict.EXPIRED);
    }

    @Test
    void timestampCannotBeSwappedWithoutBreakingSignature() {
        String header = WebhookSignature.sign(SECRET, now.minus(Duration.ofHours(1)).getEpochSecond(), "{}");
        String forged = header.replaceFirst("t=\\d+", "t=" + now.getEpochSecond());
        assertThat(WebhookSignature.verify(SECRET, forged, "{}", now, TOL))
                .isEqualTo(WebhookSignature.Verdict.BAD_SIGNATURE);
    }

    @Test
    void malformedHeaders() {
        assertThat(WebhookSignature.verify(SECRET, null, "{}", now, TOL)).isEqualTo(WebhookSignature.Verdict.MALFORMED);
        assertThat(WebhookSignature.verify(SECRET, "garbage", "{}", now, TOL))
                .isEqualTo(WebhookSignature.Verdict.MALFORMED);
        assertThat(WebhookSignature.verify(SECRET, "t=abc,v1=00", "{}", now, TOL))
                .isEqualTo(WebhookSignature.Verdict.MALFORMED);
    }
}
