package com.marketflow.payment;

import com.marketflow.payment.application.PaymentEvents;
import com.marketflow.payment.application.PaymentService;
import com.marketflow.payment.webhook.WebhookSignature;
import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MarketflowIntegrationTest
@TestPropertySource(properties = {
        "marketflow.payment.webhook-secret=whsec_integration_test",
        // webhooks are posted by the test through MockMvc; disable the background HTTP dispatcher + reconciler
        "marketflow.payment.sandbox.webhook-poll=1h",
        "marketflow.payment.reconciliation-interval=1h",
        "marketflow.payment.sandbox.webhook-url=http://127.0.0.1:1/unused"})
class PaymentIT {

    static final String SECRET = "whsec_integration_test";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PaymentService payments;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    final UUID customer = UUID.randomUUID();

    private UUID authorize(String token) {
        UUID order = UUID.randomUUID();
        payments.authorize(new PaymentEvents.AuthorizeCommand(order, customer, new BigDecimal("42.50"), "USD", token),
                null);
        return order;
    }

    private String paymentStatus(UUID order) {
        return jdbc.queryForObject("select status from payment where order_id = ?", String.class, order);
    }

    private int events(UUID order, String type) {
        return jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and event_type = ?",
                Integer.class, order.toString(), type);
    }

    private int attempts(UUID order, String op) {
        return jdbc.queryForObject("select count(*) from payment_attempt a join payment p on p.id = a.payment_id "
                + "where p.order_id = ? and a.operation = ?", Integer.class, order, op);
    }

    @Test
    void authorizationCommandOverKafkaIsIdempotent() {
        UUID order = UUID.randomUUID();
        String cmd = KafkaTestClient.envelope(UUID.randomUUID(), "PaymentAuthorizationRequested", "Order",
                order.toString(), Map.of("orderId", order.toString(), "customerId", customer.toString(),
                        "amount", "19.99", "currency", "USD", "paymentToken", "tok_visa"));
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("payment.commands", order.toString(), cmd);
            kafka.send("payment.commands", order.toString(), cmd); // duplicate delivery
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(events(order, "PaymentAuthorized"))
                .isEqualTo(2)); // outcome re-published for the duplicate ...
        assertThat(attempts(order, "AUTHORIZE")).isEqualTo(1); // ... but the provider was called once
        assertThat(jdbc.queryForObject("select count(*) from sandbox_provider_payment where merchant_reference = ?",
                Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void declinedCardPublishesPaymentFailedWithCode() throws Exception {
        UUID order = authorize("tok_insufficient_funds");
        assertThat(paymentStatus(order)).isEqualTo("DECLINED");
        assertThat(events(order, "PaymentFailed")).isEqualTo(1);
        String payload = jdbc.queryForObject("select payload::text from outbox_event where aggregate_id = ? "
                + "and event_type = 'PaymentFailed'", String.class, order.toString());
        assertThat(payload).contains("insufficient_funds").doesNotContain("tok_");
    }

    @Test
    void concurrentDuplicateCapturesCaptureOnce() throws Exception {
        UUID order = authorize("tok_visa");
        var pool = Executors.newFixedThreadPool(6);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> payments.capture(order, null)));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        assertThat(paymentStatus(order)).isEqualTo("CAPTURED");
        assertThat(attempts(order, "CAPTURE")).isEqualTo(1);
        payments.capture(order, null); // later duplicate: re-publish, no provider call
        assertThat(attempts(order, "CAPTURE")).isEqualTo(1);
        assertThat(events(order, "PaymentCaptured")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void lostProviderResponseIsReconciledInsteadOfAssumedFailed() {
        UUID order = authorize("tok_timeout");
        assertThat(paymentStatus(order)).isEqualTo("UNKNOWN");
        assertThat(events(order, "PaymentOutcomeUnknown")).isEqualTo(1);
        assertThat(events(order, "PaymentFailed")).isZero();
        jdbc.update("update payment set updated_at = now() - interval '1 minute' where order_id = ?", order);
        payments.reconcileDue();
        assertThat(paymentStatus(order)).isEqualTo("AUTHORIZED");
        assertThat(events(order, "PaymentAuthorized")).isEqualTo(1);

        UUID declined = authorize("tok_timeout_decline");
        jdbc.update("update payment set updated_at = now() - interval '1 minute' where order_id = ?", declined);
        payments.reconcileDue();
        assertThat(paymentStatus(declined)).isEqualTo("DECLINED");
        assertThat(events(declined, "PaymentFailed")).isEqualTo(1);
    }

    @Test
    void slowProviderIsCutByTimeLimiterAndLaterReconciled() {
        long t0 = System.currentTimeMillis();
        UUID order = authorize("tok_slow");
        assertThat(System.currentTimeMillis() - t0).isLessThan(4_500); // 3 s limiter, not the 5 s provider delay
        assertThat(paymentStatus(order)).isEqualTo("UNKNOWN");
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            jdbc.update("update payment set updated_at = now() - interval '1 minute' where order_id = ?", order);
            payments.reconcileDue();
            assertThat(paymentStatus(order)).isEqualTo("AUTHORIZED");
        });
        assertThat(jdbc.queryForObject("select count(*) from sandbox_provider_payment where merchant_reference = ?",
                Integer.class, order.toString())).isEqualTo(1);
    }

    @Test
    void captureFailureThenVoid() {
        UUID order = authorize("tok_capture_fail");
        payments.capture(order, null);
        assertThat(paymentStatus(order)).isEqualTo("CAPTURE_FAILED");
        assertThat(events(order, "PaymentCaptureFailed")).isEqualTo(1);
        payments.voidPayment(order, "CAPTURE_FAILED", null);
        payments.voidPayment(order, "CAPTURE_FAILED", null);
        assertThat(paymentStatus(order)).isEqualTo("VOIDED");
        assertThat(attempts(order, "VOID")).isEqualTo(1);
    }

    @Test
    void refundsAreIdempotentAndBounded() {
        UUID order = authorize("tok_visa");
        payments.capture(order, null);
        payments.refund(order, null, "CUSTOMER_CANCELLED", "order:" + order + ":CUSTOMER_CANCELLED", null, null);
        payments.refund(order, null, "CUSTOMER_CANCELLED", "order:" + order + ":CUSTOMER_CANCELLED", null, null);
        assertThat(paymentStatus(order)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from refund r join payment p on p.id = r.payment_id "
                + "where p.order_id = ?", Integer.class, order)).isEqualTo(1);
        assertThat(attempts(order, "REFUND")).isEqualTo(1);
        assertThat(events(order, "RefundCompleted")).isEqualTo(2); // re-published for the duplicate request
        // nothing left to refund
        payments.refund(order, null, "again", "order:" + order + ":again", null, null);
        assertThat(events(order, "RefundFailed")).isEqualTo(1);

        UUID failing = authorize("tok_refund_fail");
        payments.capture(failing, null);
        payments.refund(failing, null, "x", "order:" + failing + ":x", null, null);
        assertThat(events(failing, "RefundFailed")).isEqualTo(1);
        assertThat(paymentStatus(failing)).isEqualTo("CAPTURED");
    }

    private String webhookBody(String eventId, String type, String providerPaymentId) {
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"created\":" + Instant.now().getEpochSecond()
                + ",\"data\":{\"paymentId\":\"" + providerPaymentId + "\"}}";
    }

    @Test
    void asyncAuthorisationCompletesOnlyThroughASignedWebhookAndReplaysAreIgnored() throws Exception {
        UUID order = authorize("tok_async");
        assertThat(paymentStatus(order)).isEqualTo("PENDING");
        String providerId = jdbc.queryForObject("select provider_payment_id from payment where order_id = ?",
                String.class, order);
        String body = webhookBody("evt_" + UUID.randomUUID(), "payment.authorized", providerId);

        // forged signature
        mvc.perform(post("/api/v1/payments/webhooks/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, WebhookSignature.sign("wrong-secret",
                                Instant.now().getEpochSecond(), body)).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_signature"));
        // no signature at all
        mvc.perform(post("/api/v1/payments/webhooks/sandbox").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        // stale but correctly signed (captured long ago)
        mvc.perform(post("/api/v1/payments/webhooks/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, WebhookSignature.sign(SECRET,
                                Instant.now().minusSeconds(3600).getEpochSecond(), body)).content(body))
                .andExpect(status().isBadRequest());
        assertThat(paymentStatus(order)).isEqualTo("PENDING");

        String sig = WebhookSignature.sign(SECRET, Instant.now().getEpochSecond(), body);
        mvc.perform(post("/api/v1/payments/webhooks/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, sig).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("processed"));
        assertThat(paymentStatus(order)).isEqualTo("AUTHORIZED");
        // exact replay of the valid request
        mvc.perform(post("/api/v1/payments/webhooks/sandbox").contentType(MediaType.APPLICATION_JSON)
                        .header(WebhookSignature.HEADER, sig).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));
        assertThat(events(order, "PaymentAuthorized")).isEqualTo(1);
    }

    @Test
    void customersSeeOnlyTheirOwnPaymentsAndNoTokens() throws Exception {
        UUID order = authorize("tok_visa");
        String paymentId = jdbc.queryForObject("select id::text from payment where order_id = ?", String.class, order);
        mvc.perform(get("/api/v1/payments/" + paymentId).with(TestJwt.customer(customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.paymentToken").doesNotExist());
        mvc.perform(get("/api/v1/payments/" + paymentId).with(TestJwt.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/payments/by-order/" + order).with(TestJwt.admin(UUID.randomUUID())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/payments").with(TestJwt.customer(customer))).andExpect(status().isForbidden());
    }
}
