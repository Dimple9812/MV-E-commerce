package com.marketflow.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.payment.webhook.WebhookSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic local payment provider for development and tests - no network, no credentials.
 *
 * <table>
 *   <tr><th>token</th><th>behaviour</th></tr>
 *   <tr><td>tok_visa, tok_mastercard, tok_success</td><td>authorise + capture succeed</td></tr>
 *   <tr><td>tok_decline, tok_insufficient_funds, tok_expired_card</td><td>authorisation declined</td></tr>
 *   <tr><td>tok_capture_fail</td><td>authorised, capture fails</td></tr>
 *   <tr><td>tok_refund_fail</td><td>authorise + capture succeed, refunds fail</td></tr>
 *   <tr><td>tok_timeout</td><td>provider AUTHORISES but the response is lost (timeout) → must be reconciled</td></tr>
 *   <tr><td>tok_timeout_decline</td><td>provider DECLINES but the response is lost → must be reconciled</td></tr>
 *   <tr><td>tok_slow</td><td>authorises after 5 s → our 3 s time limiter fires → reconciled later</td></tr>
 *   <tr><td>tok_async</td><td>returns PENDING; a signed {@code payment.authorized} webhook follows ~2 s later</td></tr>
 * </table>
 *
 * Provider-side state lives in {@code sandbox_*} tables to simulate an external system that remembers what
 * really happened. Idempotency keys are honoured exactly like a real provider: same key → same response.
 */
@Component
public class SandboxPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SandboxPaymentProvider.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final String webhookSecret;
    private final String webhookUrl;
    private final RestClient http;

    public SandboxPaymentProvider(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
                                  @Value("${marketflow.payment.webhook-secret}") String webhookSecret,
                                  @Value("${marketflow.payment.sandbox.webhook-url}") String webhookUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.webhookSecret = webhookSecret;
        this.webhookUrl = webhookUrl;
        this.http = RestClient.builder().build();
    }

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public Result authorize(String ref, BigDecimal amount, String currency, String token, String key) {
        Optional<Result> previous = idempotent(key);
        if (previous.isPresent()) {
            return previous.get();
        }
        String id = "sbx_pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Status status;
        String code = null;
        boolean loseResponse = false;
        switch (token) {
            case "tok_visa", "tok_mastercard", "tok_success", "tok_capture_fail", "tok_refund_fail" ->
                    status = Status.AUTHORIZED;
            case "tok_decline" -> {
                status = Status.DECLINED;
                code = "card_declined";
            }
            case "tok_insufficient_funds" -> {
                status = Status.DECLINED;
                code = "insufficient_funds";
            }
            case "tok_expired_card" -> {
                status = Status.DECLINED;
                code = "expired_card";
            }
            case "tok_timeout" -> {
                status = Status.AUTHORIZED;
                loseResponse = true;
            }
            case "tok_timeout_decline" -> {
                status = Status.DECLINED;
                code = "card_declined";
                loseResponse = true;
            }
            case "tok_slow" -> {
                sleep(5_000);
                status = Status.AUTHORIZED;
            }
            case "tok_async" -> status = Status.PENDING;
            default -> {
                status = Status.DECLINED;
                code = "invalid_token";
            }
        }
        Result result = new Result(status, id, code);
        Status stored = status;
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO sandbox_provider_payment (provider_payment_id, merchant_reference, token, amount, "
                    + "status) VALUES (?, ?, ?, ?, ?) ON CONFLICT (merchant_reference) DO NOTHING", id, ref, token,
                    amount, stored.name());
            remember(key, result);
            if (stored == Status.PENDING) {
                scheduleWebhook("payment.authorized", id, ref, 2);
            }
        });
        if (loseResponse) {
            throw new ProviderTimeoutException("sandbox: simulated lost response");
        }
        return result;
    }

    @Override
    public Result capture(String providerPaymentId, BigDecimal amount, String key) {
        return mutate(key, providerPaymentId, current -> switch (current.status) {
            case "AUTHORIZED" -> "tok_capture_fail".equals(current.token)
                    ? new Change("CAPTURE_FAILED", Status.CAPTURE_FAILED, "capture_declined")
                    : new Change("CAPTURED", Status.CAPTURED, null);
            case "CAPTURED" -> new Change("CAPTURED", Status.CAPTURED, null);
            default -> new Change(current.status, Status.CAPTURE_FAILED, "not_capturable");
        });
    }

    @Override
    public Result voidAuthorization(String providerPaymentId, String key) {
        return mutate(key, providerPaymentId, current -> switch (current.status) {
            case "AUTHORIZED", "CAPTURE_FAILED", "PENDING", "VOIDED" -> new Change("VOIDED", Status.VOIDED, null);
            default -> new Change(current.status, Status.FAILED, "not_voidable");
        });
    }

    @Override
    public Result refund(String providerPaymentId, BigDecimal amount, String key) {
        Optional<Result> previous = idempotent(key);
        if (previous.isPresent()) {
            return previous.get();
        }
        return tx.execute(s -> {
            Row row = row(providerPaymentId).orElse(null);
            Result r;
            if (row == null || !(row.status.equals("CAPTURED") || row.status.equals("PARTIALLY_REFUNDED"))) {
                r = new Result(Status.FAILED, null, "not_refundable");
            } else if ("tok_refund_fail".equals(row.token)) {
                r = new Result(Status.FAILED, null, "refund_declined");
            } else {
                BigDecimal total = row.refunded.add(amount);
                String st = total.compareTo(row.amount) >= 0 ? "REFUNDED" : "PARTIALLY_REFUNDED";
                jdbc.update("UPDATE sandbox_provider_payment SET refunded = ?, status = ?, updated_at = now() "
                        + "WHERE provider_payment_id = ?", total, st, providerPaymentId);
                r = new Result(Status.REFUNDED, "sbx_re_" + UUID.randomUUID().toString().substring(0, 18), null);
            }
            remember(key, r);
            return r;
        });
    }

    @Override
    public Optional<Result> query(String merchantReference) {
        List<Result> rows = jdbc.query("SELECT provider_payment_id, status FROM sandbox_provider_payment "
                        + "WHERE merchant_reference = ?",
                (rs, i) -> new Result(Status.valueOf(rs.getString("status")), rs.getString("provider_payment_id"), null),
                merchantReference);
        return rows.stream().findFirst();
    }

    // ---------------------------------------------------------------- simulated asynchronous webhooks

    private void scheduleWebhook(String type, String providerPaymentId, String ref, int delaySeconds) {
        String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
        try {
            String body = mapper.writeValueAsString(Map.of("id", eventId, "type", type, "created",
                    Instant.now().getEpochSecond(), "data", Map.of("paymentId", providerPaymentId,
                            "merchantReference", ref)));
            jdbc.update("INSERT INTO sandbox_pending_webhook (event_id, event_type, provider_payment_id, body, deliver_at) "
                    + "VALUES (?, ?, ?, ?, ?)", eventId, type, providerPaymentId, body,
                    java.sql.Timestamp.from(Instant.now().plusSeconds(delaySeconds)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Delivers due webhooks over real HTTP to our own webhook endpoint, signed like a real provider would. */
    @Scheduled(fixedDelayString = "${marketflow.payment.sandbox.webhook-poll:1s}")
    public void deliverWebhooks() {
        List<Map<String, Object>> due = jdbc.queryForList("SELECT id, event_type, provider_payment_id, body "
                + "FROM sandbox_pending_webhook "
                + "WHERE NOT delivered AND deliver_at <= now() AND attempts < 10 ORDER BY id LIMIT 20");
        for (Map<String, Object> w : due) {
            long id = ((Number) w.get("id")).longValue();
            String body = (String) w.get("body");
            // the provider-side state changes when the async authorisation completes
            if ("payment.authorized".equals(w.get("event_type"))) {
                jdbc.update("UPDATE sandbox_provider_payment SET status = 'AUTHORIZED', updated_at = now() "
                        + "WHERE status = 'PENDING' AND provider_payment_id = ?", w.get("provider_payment_id"));
            }
            try {
                http.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, WebhookSignature.sign(webhookSecret,
                                Instant.now().getEpochSecond(), body))
                        .body(body).retrieve().toBodilessEntity();
                jdbc.update("UPDATE sandbox_pending_webhook SET delivered = true, attempts = attempts + 1 WHERE id = ?",
                        id);
            } catch (Exception e) {
                jdbc.update("UPDATE sandbox_pending_webhook SET attempts = attempts + 1, "
                        + "deliver_at = now() + interval '2 seconds' WHERE id = ?", id);
                log.warn("Sandbox webhook delivery failed ({}), will retry", e.getClass().getSimpleName());
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private record Row(String id, String token, String status, BigDecimal amount, BigDecimal refunded) {
    }

    private record Change(String newProviderStatus, Status result, String code) {
    }

    private Result mutate(String key, String providerPaymentId, java.util.function.Function<Row, Change> decide) {
        Optional<Result> previous = idempotent(key);
        if (previous.isPresent()) {
            return previous.get();
        }
        return tx.execute(s -> {
            Row row = row(providerPaymentId).orElse(null);
            Result r;
            if (row == null) {
                r = new Result(Status.NOT_FOUND, providerPaymentId, "unknown_payment");
            } else {
                Change c = decide.apply(row);
                jdbc.update("UPDATE sandbox_provider_payment SET status = ?, updated_at = now() "
                        + "WHERE provider_payment_id = ?", c.newProviderStatus(), providerPaymentId);
                r = new Result(c.result(), providerPaymentId, c.code());
            }
            remember(key, r);
            return r;
        });
    }

    private Optional<Row> row(String providerPaymentId) {
        return jdbc.query("SELECT provider_payment_id, token, status, amount, refunded FROM sandbox_provider_payment "
                        + "WHERE provider_payment_id = ? FOR UPDATE",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5)), providerPaymentId).stream().findFirst();
    }

    private Optional<Result> idempotent(String key) {
        List<String> stored = jdbc.queryForList("SELECT response FROM sandbox_idempotency WHERE idempotency_key = ?",
                String.class, key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(stored.get(0), Result.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void remember(String key, Result r) {
        try {
            jdbc.update("INSERT INTO sandbox_idempotency (idempotency_key, response) VALUES (?, ?) "
                    + "ON CONFLICT DO NOTHING", key, mapper.writeValueAsString(r));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
