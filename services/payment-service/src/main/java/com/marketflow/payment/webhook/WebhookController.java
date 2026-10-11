package com.marketflow.payment.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.payment.application.PaymentService;
import com.marketflow.platform.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Provider webhooks. Defences, in order:
 * <ol>
 *   <li>HMAC signature over timestamp + raw body (constant-time compare) → 400 if invalid,</li>
 *   <li>timestamp within ±5 minutes → old captured requests are rejected,</li>
 *   <li>provider event ID recorded once ({@code webhook_event} PK) → replays inside the window are acknowledged
 *       with 200 but have no effect,</li>
 *   <li>the payment state machine only accepts legal transitions.</li>
 * </ol>
 * Webhooks are the only way an asynchronous authorisation becomes AUTHORIZED; a browser redirect never is.
 */
@RestController
@Tag(name = "Payment webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private final PaymentService payments;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final String secret;

    public WebhookController(PaymentService payments, JdbcTemplate jdbc, ObjectMapper mapper, MeterRegistry meters,
                             @Value("${marketflow.payment.webhook-secret}") String secret) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.meters = meters;
        this.secret = secret;
    }

    @PostMapping(value = "/api/v1/payments/webhooks/sandbox", consumes = "application/json")
    @Operation(summary = "Sandbox provider webhook (signature required)")
    public ResponseEntity<Map<String, String>> sandbox(
            @RequestHeader(name = WebhookSignature.HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        WebhookSignature.Verdict verdict = WebhookSignature.verify(secret, signature, rawBody, Instant.now(), TOLERANCE);
        if (verdict != WebhookSignature.Verdict.VALID) {
            meters.counter("payment.webhooks", "result", verdict.name().toLowerCase()).increment();
            log.warn("Rejected webhook: {}", verdict);
            throw ApiException.badRequest("invalid_signature", "Webhook signature verification failed");
        }
        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (Exception e) {
            throw ApiException.badRequest("invalid_payload", "Malformed webhook payload");
        }
        String eventId = event.path("id").asText(null);
        String type = event.path("type").asText(null);
        String providerPaymentId = event.at("/data/paymentId").asText(null);
        if (eventId == null || type == null || providerPaymentId == null || eventId.length() > 100) {
            throw ApiException.badRequest("invalid_payload", "Webhook payload is missing required fields");
        }
        int fresh = jdbc.update("INSERT INTO webhook_event (provider_event_id, event_type, payload_sha256) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", eventId, type, sha256(rawBody));
        if (fresh == 0) {
            meters.counter("payment.webhooks", "result", "duplicate").increment();
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }
        payments.applyWebhook(type, providerPaymentId);
        meters.counter("payment.webhooks", "result", "processed").increment();
        return ResponseEntity.ok(Map.of("status", "processed"));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
