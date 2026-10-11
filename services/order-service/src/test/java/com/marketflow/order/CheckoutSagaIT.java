package com.marketflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.order.application.SagaTimeoutScanner;
import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the checkout saga through every branch. The test plays inventory-service and payment-service by
 * publishing their reply events to Kafka, which lets each failure be injected deterministically.
 * Saga timeouts are long here; timeout behaviour is tested by moving the persisted deadline into the past and
 * running the scanner - exactly what happens after a restart.
 */
@MarketflowIntegrationTest
@TestPropertySource(properties = {
        "marketflow.order.saga.inventory-timeout=1h", "marketflow.order.saga.payment-timeout=1h",
        "marketflow.order.saga.capture-timeout=1h", "marketflow.order.saga.confirm-timeout=1h",
        "marketflow.order.saga.compensation-timeout=1h", "marketflow.order.saga.reconciliation-interval=1h",
        "marketflow.order.saga.scan-interval=1h"})
class CheckoutSagaIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SagaTimeoutScanner scanner;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    KafkaTestClient kafka;
    final UUID customer = UUID.randomUUID();
    final Map<String, UUID> vendorOwners = new java.util.HashMap<>();
    final Map<String, UUID> vendors = new java.util.HashMap<>();
    final Map<String, UUID> variants = new java.util.HashMap<>();

    @BeforeEach
    void seed() {
        kafka = new KafkaTestClient(brokers);
        // three approved vendors + one suspended, each with a product
        vendor("alpha", "APPROVED", "0.1000");
        vendor("beta", "APPROVED", "0.1500");
        vendor("gamma", "APPROVED", "0.0500");
        vendor("rogue", "SUSPENDED", "0.1000");
        product("alpha", "ALPHA-1", "30.00", "PUBLISHED", 5);
        product("beta", "BETA-1", "15.00", "PUBLISHED", 5);
        product("gamma", "GAMMA-1", "9.99", "PUBLISHED", 5);
        product("alpha", "ALPHA-DRAFT", "10.00", "DRAFT", 5);
        product("rogue", "ROGUE-1", "5.00", "PUBLISHED", 5);
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from variant_projection v join product_projection p using (product_id) "
                        + "where v.variant_id = any(?::uuid[])", Integer.class,
                (Object) variants.values().stream().map(UUID::toString).toArray(String[]::new)) == 5
                && jdbc.queryForObject("select count(*) from vendor_projection where vendor_id = any(?::uuid[])",
                Integer.class, (Object) vendors.values().stream().map(UUID::toString).toArray(String[]::new)) == 4);
    }

    @AfterEach
    void close() {
        kafka.close();
    }

    private void vendor(String name, String status, String commission) {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        vendors.put(name, id);
        vendorOwners.put(name, owner);
        kafka.send("vendor.events", id.toString(), KafkaTestClient.envelope(UUID.randomUUID(), "VendorApproved",
                "Vendor", id.toString(), Map.of("vendorId", id.toString(), "ownerUserId", owner.toString(),
                        "businessName", name + " shop", "status", status, "commissionRate", commission,
                        "vendorVersion", 1)));
    }

    private void product(String vendor, String sku, String price, String status, int maxPerOrder) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        variants.put(sku, variantId);
        kafka.send("catalog.product.events", productId.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                "ProductPublished", "Product", productId.toString(), Map.of("productId", productId.toString(),
                        "vendorId", vendors.get(vendor).toString(), "name", "Product " + sku, "status", status,
                        "currency", "USD", "maxPerOrder", maxPerOrder, "productVersion", 1,
                        "variants", List.of(Map.of("variantId", variantId.toString(), "sku", sku, "name", "Default",
                                "price", price, "active", true)))));
    }

    private String body(String token, Object... skuQty) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            items.add(Map.of("variantId", variants.get((String) skuQty[i]).toString(), "quantity", skuQty[i + 1],
                    "unitPrice", "0.01")); // a client-supplied price must be ignored
        }
        return json.writeValueAsString(Map.of("items", items, "paymentToken", token, "shippingAddress",
                Map.of("recipient", "Ada Moreno", "line1", "1 Analytical St", "city", "London", "postalCode",
                        "N1 1AA", "country", "GB")));
    }

    private MvcResult checkout(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/orders/checkout").with(TestJwt.customer(customer))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn();
    }

    private UUID start(String token, Object... skuQty) throws Exception {
        MvcResult r = checkout("key-" + UUID.randomUUID(), body(token, skuQty));
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private void reply(String topic, String type, UUID order, Map<String, Object> extra) {
        reply(topic, type, order, extra, UUID.randomUUID());
    }

    private void reply(String topic, String type, UUID order, Map<String, Object> extra, UUID eventId) {
        Map<String, Object> payload = new java.util.HashMap<>(extra);
        payload.put("orderId", order.toString());
        kafka.send(topic, order.toString(), KafkaTestClient.envelope(eventId, type, "Order", order.toString(),
                payload));
    }

    private String sagaState(UUID order) {
        return jdbc.queryForObject("select state from checkout_saga where order_id = ?", String.class, order);
    }

    private String orderStatus(UUID order) {
        return jdbc.queryForObject("select status from customer_order where id = ?", String.class, order);
    }

    private void awaitState(UUID order, String state) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(sagaState(order)).isEqualTo(state));
    }

    private int commands(UUID order, String type) {
        return jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and event_type = ?",
                Integer.class, order.toString(), type);
    }

    private JsonNode lastPayload(UUID order, String type) throws Exception {
        String p = jdbc.queryForObject("select payload::text from outbox_event where aggregate_id = ? and "
                + "event_type = ? order by id desc limit 1", String.class, order.toString(), type);
        return json.readTree(p).get("payload");
    }

    private void toPaymentPending(UUID order) {
        reply("inventory.events", "InventoryReserved", order, Map.of());
        awaitState(order, "PAYMENT_PENDING");
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void multiVendorCheckoutCreatesParentOrderAndOneSuborderPerVendorWithServerPrices() throws Exception {
        UUID order = start("tok_visa", "ALPHA-1", 2, "BETA-1", 1, "GAMMA-1", 3);
        mvc.perform(get("/api/v1/orders/" + order).with(TestJwt.customer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.checkoutState").value("INVENTORY_PENDING"))
                .andExpect(jsonPath("$.suborders.length()").value(3))
                .andExpect(jsonPath("$.itemsSubtotal").value(104.97))   // 60 + 15 + 29.97, never 0.01 * n
                .andExpect(jsonPath("$.shippingTotal").value(9.98))     // alpha >= 50 ships free
                .andExpect(jsonPath("$.taxTotal").value(8.40))
                .andExpect(jsonPath("$.grandTotal").value(123.35))
                .andExpect(jsonPath("$.suborders[0].commissionAmount").doesNotExist());
        BigDecimal sum = jdbc.queryForObject("select sum(total) from suborder where order_id = ?", BigDecimal.class,
                order);
        assertThat(sum).isEqualByComparingTo("123.35");
        JsonNode cmd = lastPayload(order, "InventoryReservationRequested");
        assertThat(cmd.get("lines")).hasSize(3);
        assertThat(commands(order, "OrderCreated")).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyNeverCreatesASecondOrder() throws Exception {
        String key = "checkout-" + UUID.randomUUID();
        String body = body("tok_visa", "BETA-1", 1);
        String first = json.readTree(checkout(key, body).getResponse().getContentAsString()).get("id").asText();
        MvcResult again = checkout(key, body);
        assertThat(json.readTree(again.getResponse().getContentAsString()).get("id").asText()).isEqualTo(first);
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(jdbc.queryForObject("select count(*) from customer_order where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(commands(UUID.fromString(first), "InventoryReservationRequested")).isEqualTo(1);
        // same key, different body
        mvc.perform(post("/api/v1/orders/checkout").with(TestJwt.customer(customer)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tok_visa", "BETA-1", 2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneOrder() throws Exception {
        String key = "race-" + UUID.randomUUID();
        String body = body("tok_visa", "GAMMA-1", 1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> json.readTree(checkout(key, body).getResponse().getContentAsString())
                    .get("id").asText()));
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var f : fs) {
            ids.add(f.get());
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from customer_order where idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        String base = "/api/v1/orders/checkout";
        mvc.perform(post(base).header("Idempotency-Key", "k-12345678").contentType(MediaType.APPLICATION_JSON)
                .content(body("tok_visa", "BETA-1", 1))).andExpect(status().isUnauthorized());
        for (int qty : new int[]{0, -1, 101}) {
            mvc.perform(post(base).with(TestJwt.customer(customer)).header("Idempotency-Key", "k-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(body("tok_visa", "BETA-1", qty)))
                    .andExpect(status().isBadRequest());
        }
        // per-product purchase limit (5)
        mvc.perform(post(base).with(TestJwt.customer(customer)).header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body("tok_visa", "BETA-1", 6)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("purchase_limit"));
        // draft product and suspended vendor
        mvc.perform(post(base).with(TestJwt.customer(customer)).header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body("tok_visa", "ALPHA-DRAFT", 1)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("item_unavailable"));
        mvc.perform(post(base).with(TestJwt.customer(customer)).header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body("tok_visa", "ROGUE-1", 1)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("vendor_unavailable"));
        // missing idempotency key
        mvc.perform(post(base).with(TestJwt.customer(customer)).contentType(MediaType.APPLICATION_JSON)
                .content(body("tok_visa", "BETA-1", 1))).andExpect(status().isBadRequest());
    }

    @Test
    void customersCannotReadEachOthersOrders() throws Exception {
        UUID order = start("tok_visa", "BETA-1", 1);
        mvc.perform(get("/api/v1/orders/" + order).with(TestJwt.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + order)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/orders/" + order).with(TestJwt.customer(customer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void happyPathConfirmsOrderAndDuplicateRepliesDoNotRepeatCommands() throws Exception {
        UUID order = start("tok_visa", "ALPHA-1", 1, "BETA-1", 1);
        toPaymentPending(order);
        JsonNode auth = lastPayload(order, "PaymentAuthorizationRequested");
        assertThat(auth.get("paymentToken").asText()).isEqualTo("tok_visa");
        assertThat(auth.get("amount").decimalValue()).isEqualByComparingTo(
                jdbc.queryForObject("select grand_total from customer_order where id = ?", BigDecimal.class, order));

        UUID authorizedEvent = UUID.randomUUID();
        reply("payment.events", "PaymentAuthorized", order, Map.of(), authorizedEvent);
        reply("payment.events", "PaymentAuthorized", order, Map.of(), authorizedEvent); // exact redelivery
        reply("payment.events", "PaymentAuthorized", order, Map.of());                  // duplicate publication
        awaitState(order, "PAYMENT_AUTHORIZED");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from saga_transition where order_id = ? and trigger like 'Ignored:PaymentAuthorized'",
                Integer.class, order)).isEqualTo(1));
        assertThat(commands(order, "PaymentCaptureRequested")).isEqualTo(1);

        reply("payment.events", "PaymentCaptured", order, Map.of());
        awaitState(order, "INVENTORY_CONFIRM_PENDING");
        reply("inventory.events", "InventoryReservationConfirmed", order, Map.of());
        awaitState(order, "CONFIRMED");
        assertThat(orderStatus(order)).isEqualTo("CONFIRMED");
        JsonNode confirmed = lastPayload(order, "OrderConfirmed");
        assertThat(confirmed.get("suborders")).hasSize(2);
        assertThat(confirmed.at("/suborders/0/commissionAmount").isMissingNode()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from suborder where order_id = ? and status = 'CONFIRMED'",
                Integer.class, order)).isEqualTo(2);
    }

    @Test
    void failedInventoryReservationCancelsWithoutTouchingPayment() throws Exception {
        UUID order = start("tok_visa", "BETA-1", 2);
        reply("inventory.events", "InventoryReservationFailed", order, Map.of("reason", "INSUFFICIENT_STOCK"));
        awaitState(order, "CANCELLED");
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
        assertThat(commands(order, "PaymentAuthorizationRequested")).isZero();
        assertThat(commands(order, "OrderCancelled")).isEqualTo(1);
    }

    @Test
    void declinedPaymentReleasesInventoryThenCancels() throws Exception {
        UUID order = start("tok_decline", "BETA-1", 1);
        toPaymentPending(order);
        reply("payment.events", "PaymentFailed", order, Map.of("reason", "card_declined"));
        awaitState(order, "COMPENSATING");
        assertThat(commands(order, "InventoryReleaseRequested")).isEqualTo(1);
        assertThat(orderStatus(order)).isEqualTo("PENDING");
        reply("inventory.events", "InventoryReservationReleased", order, Map.of("status", "RELEASED"));
        awaitState(order, "CANCELLED");
        assertThat(jdbc.queryForObject("select cancel_reason from customer_order where id = ?", String.class, order))
                .isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void captureFailureVoidsAuthorisationAndReleasesStockBeforeCancelling() throws Exception {
        UUID order = start("tok_capture_fail", "GAMMA-1", 1);
        toPaymentPending(order);
        reply("payment.events", "PaymentAuthorized", order, Map.of());
        awaitState(order, "PAYMENT_AUTHORIZED");
        reply("payment.events", "PaymentCaptureFailed", order, Map.of());
        awaitState(order, "COMPENSATING");
        assertThat(commands(order, "PaymentVoidRequested")).isEqualTo(1);
        assertThat(commands(order, "InventoryReleaseRequested")).isEqualTo(1);
        reply("payment.events", "PaymentVoided", order, Map.of());
        Thread.sleep(500); // one of two compensations acknowledged: must still be compensating
        assertThat(sagaState(order)).isEqualTo("COMPENSATING");
        reply("inventory.events", "InventoryReservationReleased", order, Map.of());
        awaitState(order, "CANCELLED");
    }

    @Test
    void stockLostAfterPaymentTriggersRefund() throws Exception {
        UUID order = start("tok_visa", "ALPHA-1", 1);
        toPaymentPending(order);
        reply("payment.events", "PaymentAuthorized", order, Map.of());
        awaitState(order, "PAYMENT_AUTHORIZED");
        reply("payment.events", "PaymentCaptured", order, Map.of());
        awaitState(order, "INVENTORY_CONFIRM_PENDING");
        reply("inventory.events", "InventoryConfirmationFailed", order, Map.of("reason", "STOCK_GONE_AFTER_EXPIRY"));
        awaitState(order, "COMPENSATING");
        assertThat(commands(order, "RefundRequested")).isEqualTo(1);
        reply("payment.events", "RefundCompleted", order, Map.of());
        awaitState(order, "CANCELLED");
    }

    @Test
    void unknownPaymentOutcomeWaitsForReconciliationInsteadOfCancelling() throws Exception {
        UUID order = start("tok_timeout", "BETA-1", 1);
        toPaymentPending(order);
        reply("payment.events", "PaymentOutcomeUnknown", order, Map.of());
        awaitState(order, "RECONCILIATION_REQUIRED");
        assertThat(commands(order, "InventoryReleaseRequested")).isZero();
        // reconciliation later finds the payment authorised
        reply("payment.events", "PaymentAuthorized", order, Map.of());
        awaitState(order, "PAYMENT_AUTHORIZED");
        assertThat(commands(order, "PaymentCaptureRequested")).isEqualTo(1);
    }

    @Test
    void persistedSagaResumesFromTheDatabaseAfterATimeout() throws Exception {
        UUID order = start("tok_visa", "BETA-1", 1);
        // simulate: the service crashed and the reply never came; on restart the deadline has long passed
        jdbc.update("update checkout_saga set deadline_at = now() - interval '1 minute' where order_id = ?", order);
        scanner.scan();
        assertThat(commands(order, "InventoryReservationRequested")).isEqualTo(2);  // re-sent (idempotent)
        assertThat(jdbc.queryForObject("select attempt from checkout_saga where order_id = ?", Integer.class, order))
                .isEqualTo(1);
        // after max attempts the saga compensates instead of retrying forever
        for (int i = 0; i < 3; i++) {
            jdbc.update("update checkout_saga set deadline_at = now() - interval '1 minute' where order_id = ?", order);
            scanner.scan();
        }
        assertThat(sagaState(order)).isEqualTo("COMPENSATING");
        assertThat(commands(order, "InventoryReleaseRequested")).isEqualTo(1);
        // a late InventoryReserved for the abandoned attempt must not leak stock
        reply("inventory.events", "InventoryReservationReleased", order, Map.of());
        awaitState(order, "CANCELLED");
        reply("inventory.events", "InventoryReserved", order, Map.of());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(commands(order, "InventoryReleaseRequested")).isEqualTo(2));
    }

    @Test
    void paymentTimeoutMovesToReconciliationNotCancellation() throws Exception {
        UUID order = start("tok_visa", "GAMMA-1", 1);
        toPaymentPending(order);
        jdbc.update("update checkout_saga set deadline_at = now() - interval '1 minute' where order_id = ?", order);
        scanner.scan();
        assertThat(sagaState(order)).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(commands(order, "PaymentAuthorizationRequested")).isEqualTo(2);
        assertThat(commands(order, "InventoryReleaseRequested")).isZero();
    }

    @Test
    void vendorSeesAndShipsOnlyItsOwnSuborders() throws Exception {
        UUID order = start("tok_visa", "ALPHA-1", 1, "BETA-1", 1);
        toPaymentPending(order);
        reply("payment.events", "PaymentAuthorized", order, Map.of());
        awaitState(order, "PAYMENT_AUTHORIZED");
        reply("payment.events", "PaymentCaptured", order, Map.of());
        awaitState(order, "INVENTORY_CONFIRM_PENDING");
        reply("inventory.events", "InventoryReservationConfirmed", order, Map.of());
        awaitState(order, "CONFIRMED");

        UUID alphaOwner = vendorOwners.get("alpha");
        UUID betaOwner = vendorOwners.get("beta");
        String body = mvc.perform(get("/api/v1/vendor/suborders").with(TestJwt.vendor(alphaOwner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode items = json.readTree(body).get("items");
        assertThat(items).allMatch(n -> !n.get("items").toString().contains("BETA-1"));
        String alphaSub = jdbc.queryForObject("select id::text from suborder where order_id = ? and vendor_id = ?",
                String.class, order, vendors.get("alpha"));
        mvc.perform(post("/api/v1/vendor/suborders/" + alphaSub + "/ship").with(TestJwt.vendor(betaOwner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"UPS\",\"trackingNumber\":\"1Z999\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/vendor/suborders/" + alphaSub + "/ship").with(TestJwt.vendor(alphaOwner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"carrier\":\"UPS\",\"trackingNumber\":\"1Z999\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        assertThat(orderStatus(order)).isEqualTo("PARTIALLY_SHIPPED");
        // once something shipped the customer can no longer cancel
        mvc.perform(post("/api/v1/orders/" + order + "/cancel").with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellationAfterConfirmationRefundsAndRestocks() throws Exception {
        UUID order = start("tok_visa", "BETA-1", 2);
        toPaymentPending(order);
        reply("payment.events", "PaymentAuthorized", order, Map.of());
        awaitState(order, "PAYMENT_AUTHORIZED");
        reply("payment.events", "PaymentCaptured", order, Map.of());
        awaitState(order, "INVENTORY_CONFIRM_PENDING");
        reply("inventory.events", "InventoryReservationConfirmed", order, Map.of());
        awaitState(order, "CONFIRMED");

        mvc.perform(post("/api/v1/orders/" + order + "/cancel").with(TestJwt.customer(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not mine\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + order + "/cancel").with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("CANCELLATION_REQUESTED"));
        assertThat(commands(order, "RefundRequested")).isEqualTo(2); // payment command + order event
        assertThat(commands(order, "InventoryRestockRequested")).isEqualTo(1);
        reply("payment.events", "RefundCompleted", order, Map.of());
        reply("inventory.events", "InventoryRestocked", order, Map.of());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(orderStatus(order)).isEqualTo("CANCELLED"));
        JsonNode cancelled = lastPayload(order, "OrderCancelled");
        assertThat(cancelled.get("wasConfirmed").asBoolean()).isTrue();
    }

    @Test
    void adminCanInspectSagaHistory() throws Exception {
        UUID order = start("tok_visa", "BETA-1", 1);
        toPaymentPending(order);
        mvc.perform(get("/api/v1/admin/orders/" + order + "/saga").with(TestJwt.admin(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.transitions[0].to").value("INVENTORY_PENDING"))
                .andExpect(jsonPath("$.transitions[1].to").value("INVENTORY_RESERVED"));
        mvc.perform(get("/api/v1/admin/sagas/summary").with(TestJwt.admin(UUID.randomUUID())))
                .andExpect(status().isOk()).andExpect(header().exists("X-Correlation-Id"));
    }
}
