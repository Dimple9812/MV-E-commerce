package com.marketflow.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketflow.inventory.application.InventoryEvents.Line;
import com.marketflow.inventory.application.ReservationService;
import com.marketflow.inventory.application.StockService;
import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MarketflowIntegrationTest
class InventoryIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReservationService reservations;
    @Autowired
    StockService stock;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    UUID ownerA;
    UUID vendorA;
    UUID ownerB;
    UUID productId;
    UUID variant1;
    UUID variant2;

    @BeforeEach
    void setUp() {
        ownerA = UUID.randomUUID();
        vendorA = UUID.randomUUID();
        ownerB = UUID.randomUUID();
        productId = UUID.randomUUID();
        variant1 = UUID.randomUUID();
        variant2 = UUID.randomUUID();
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("vendor.events", vendorA.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                    "VendorApproved", "Vendor", vendorA.toString(), Map.of("vendorId", vendorA.toString(),
                            "ownerUserId", ownerA.toString(), "status", "APPROVED", "vendorVersion", 1)));
            UUID vendorB = UUID.randomUUID();
            kafka.send("vendor.events", vendorB.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                    "VendorApproved", "Vendor", vendorB.toString(), Map.of("vendorId", vendorB.toString(),
                            "ownerUserId", ownerB.toString(), "status", "APPROVED", "vendorVersion", 1)));
            kafka.send("catalog.product.events", productId.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                    "ProductCreated", "Product", productId.toString(), Map.of("productId", productId.toString(),
                            "vendorId", vendorA.toString(), "name", "Limited Lamp", "status", "DRAFT",
                            "productVersion", 0, "variants", List.of(
                                    Map.of("variantId", variant1.toString(), "sku", "LAMP-1", "name", "Brass",
                                            "active", true),
                                    Map.of("variantId", variant2.toString(), "sku", "LAMP-2", "name", "Steel",
                                            "active", true)))));
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from inventory_item where product_id = ?", Integer.class, productId) == 2
                && jdbc.queryForObject("select count(*) from vendor_projection where owner_user_id in (?, ?)",
                Integer.class, ownerA, ownerB) == 2);
    }

    private void restock(UUID variant, int qty) throws Exception {
        mvc.perform(post("/api/v1/vendor/inventory/adjustments").with(TestJwt.vendor(ownerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant + "\",\"delta\":" + qty + ",\"reason\":\"goods received\"}"))
                .andExpect(status().isOk());
    }

    private int qty(String column, UUID variant) {
        return jdbc.queryForObject("select " + column + " from inventory_item where variant_id = ?", Integer.class,
                variant);
    }

    private String reservationStatus(UUID order) {
        return jdbc.queryForObject("select status from reservation where order_id = ?", String.class, order);
    }

    @Test
    void vendorsCanOnlyAdjustTheirOwnStockAndNeverBelowZero() throws Exception {
        restock(variant1, 5);
        mvc.perform(post("/api/v1/vendor/inventory/adjustments").with(TestJwt.vendor(ownerB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant1 + "\",\"delta\":100,\"reason\":\"steal\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/vendor/inventory/adjustments").with(TestJwt.vendor(ownerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant1 + "\",\"delta\":-6,\"reason\":\"write-off\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/vendor/inventory").with(TestJwt.vendor(ownerB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/vendor/inventory").with(TestJwt.vendor(ownerA)))
                .andExpect(jsonPath("$.totalItems").value(2));
        assertThat(qty("available", variant1)).isEqualTo(5);
    }

    @Test
    void concurrentCheckoutsCannotOversellTheLastUnit() throws Exception {
        restock(variant1, 1);
        int buyers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(buyers);
        CountDownLatch start = new CountDownLatch(1);
        List<UUID> orders = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            UUID order = UUID.randomUUID();
            orders.add(order);
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 1)), null);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        long reserved = orders.stream().filter(o -> "RESERVED".equals(reservationStatus(o))).count();
        long failed = orders.stream().filter(o -> "FAILED".equals(reservationStatus(o))).count();
        assertThat(reserved).isEqualTo(1);
        assertThat(failed).isEqualTo(buyers - 1);
        assertThat(qty("available", variant1)).isZero();
        assertThat(qty("reserved", variant1)).isEqualTo(1);
        assertThat(stock.verifyLedger()).isEmpty();
    }

    @Test
    void repeatedAndConcurrentDuplicateReserveCommandsReserveOnce() throws Exception {
        restock(variant1, 10);
        UUID order = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fs.add(pool.submit(() -> reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 3)), null)));
        }
        for (Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 3)), null);
        assertThat(qty("available", variant1)).isEqualTo(7);
        assertThat(qty("reserved", variant1)).isEqualTo(3);
    }

    @Test
    void duplicateReleaseDoesNotReleaseTwice() throws Exception {
        restock(variant1, 4);
        UUID order = UUID.randomUUID();
        reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 2)), null);
        reservations.release(order, "PAYMENT_DECLINED", null);
        reservations.release(order, "PAYMENT_DECLINED", null);
        assertThat(qty("available", variant1)).isEqualTo(4);
        assertThat(qty("reserved", variant1)).isZero();
        assertThat(stock.verifyLedger()).isEmpty();
    }

    @Test
    void multiLineReservationIsAllOrNothing() throws Exception {
        restock(variant1, 5);
        restock(variant2, 1);
        UUID order = UUID.randomUUID();
        reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 2), new Line(variant2, "LAMP-2", 3)), null);
        assertThat(reservationStatus(order)).isEqualTo("FAILED");
        assertThat(qty("available", variant1)).isEqualTo(5);
        assertThat(qty("available", variant2)).isEqualTo(1);
        assertThat(qty("reserved", variant1)).isZero();
    }

    @Test
    void releaseBeforeReserveLeavesTombstoneSoLateReserveIsNoOp() throws Exception {
        restock(variant1, 3);
        UUID order = UUID.randomUUID();
        reservations.release(order, "SAGA_TIMEOUT", null);
        reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 1)), null);
        assertThat(reservationStatus(order)).isEqualTo("RELEASED");
        assertThat(qty("available", variant1)).isEqualTo(3);
    }

    @Test
    void confirmMovesReservedToSoldIdempotentlyAndRestockReturnsIt() throws Exception {
        restock(variant1, 5);
        UUID order = UUID.randomUUID();
        reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 2)), null);
        reservations.confirm(order, null);
        reservations.confirm(order, null);
        assertThat(qty("reserved", variant1)).isZero();
        assertThat(qty("sold", variant1)).isEqualTo(2);
        assertThat(qty("available", variant1)).isEqualTo(3);
        reservations.restock(order, "CUSTOMER_CANCELLED", null);
        reservations.restock(order, "CUSTOMER_CANCELLED", null);
        assertThat(qty("sold", variant1)).isZero();
        assertThat(qty("available", variant1)).isEqualTo(5);
        assertThat(reservationStatus(order)).isEqualTo("RETURNED");
        assertThat(stock.verifyLedger()).isEmpty();
    }

    @Test
    void expiredReservationIsReleasedAndLateConfirmationDependsOnRemainingStock() throws Exception {
        restock(variant1, 1);
        UUID order = UUID.randomUUID();
        reservations.reserve(order, List.of(new Line(variant1, "LAMP-1", 1)), null);
        jdbc.update("update reservation set expires_at = now() - interval '1 minute' where order_id = ?", order);
        reservations.expireReservations();
        assertThat(reservationStatus(order)).isEqualTo("EXPIRED");
        assertThat(qty("available", variant1)).isEqualTo(1);

        // someone else buys the unit meanwhile
        UUID other = UUID.randomUUID();
        reservations.reserve(other, List.of(new Line(variant1, "LAMP-1", 1)), null);
        reservations.confirm(order, null);
        assertThat(reservationStatus(order)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and "
                + "event_type = 'InventoryConfirmationFailed'", Integer.class, order.toString())).isEqualTo(1);

        // with stock back, a late confirmation succeeds
        reservations.release(other, "x", null);
        reservations.confirm(order, null);
        assertThat(reservationStatus(order)).isEqualTo("CONFIRMED");
        assertThat(qty("sold", variant1)).isEqualTo(1);
        assertThat(stock.verifyLedger()).isEmpty();
    }

    @Test
    void reserveCommandOverKafkaProducesReservedEventAndLowStockAlert() throws Exception {
        restock(variant2, 6);
        UUID order = UUID.randomUUID();
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("inventory.commands", order.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                    "InventoryReservationRequested", "Order", order.toString(), Map.of("orderId", order.toString(),
                            "lines", List.of(Map.of("variantId", variant2.toString(), "sku", "LAMP-2", "quantity", 2)))));
            List<JsonNode> events = kafka.readUntil("inventory.events", Duration.ofSeconds(20), l -> l.stream()
                    .anyMatch(e -> order.toString().equals(e.path("aggregateId").asText())));
            JsonNode reserved = events.stream().filter(e -> order.toString().equals(e.path("aggregateId").asText()))
                    .findFirst().orElseThrow();
            assertThat(reserved.path("eventType").asText()).isEqualTo("InventoryReserved");
            assertThat(reserved.at("/payload/lines/0/quantity").asInt()).isEqualTo(2);
            assertThat(reserved.path("causationId").asText()).isNotBlank();
        }
        // 6 -> 4 crosses the default threshold of 5
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and "
                + "event_type = 'LowStockDetected'", Integer.class, variant2.toString())).isEqualTo(1);
    }

    @Test
    void publicAvailabilityEndpoint() throws Exception {
        restock(variant1, 9);
        mvc.perform(get("/api/v1/inventory/availability").param("variantIds", variant1.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].available").value(9));
    }
}
