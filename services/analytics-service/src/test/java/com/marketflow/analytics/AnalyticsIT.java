package com.marketflow.analytics;

import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MarketflowIntegrationTest
class AnalyticsIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    @Test
    void readModelsAreBuiltFromEventsWithoutDoubleCountingRedeliveries() throws Exception {
        UUID vendor = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        Map<String, Object> payload = Map.of("orderId", order.toString(), "customerId", UUID.randomUUID().toString(),
                "currency", "USD", "totalAmount", "80.00", "suborders", List.of(Map.of(
                        "suborderId", UUID.randomUUID().toString(), "vendorId", vendor.toString(), "vendorName", "V1",
                        "total", "80.00", "commissionAmount", "8.00", "vendorNetAmount", "72.00",
                        "items", List.of(Map.of("variantId", UUID.randomUUID().toString(), "productId",
                                product.toString(), "productName", "Lamp", "sku", "L-1", "quantity", 2,
                                "lineTotal", "80.00")))));
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("vendor.events", vendor.toString(), KafkaTestClient.envelope(UUID.randomUUID(), "VendorApproved",
                    "Vendor", vendor.toString(), Map.of("vendorId", vendor.toString(), "ownerUserId", owner.toString(),
                            "businessName", "V1", "status", "APPROVED", "vendorVersion", 1)));
            kafka.send("order.events", order.toString(), KafkaTestClient.envelope(UUID.randomUUID(), "OrderCreated",
                    "Order", order.toString(), payload));
            String confirmed = KafkaTestClient.envelope(UUID.randomUUID(), "OrderConfirmed", "Order", order.toString(),
                    payload);
            kafka.send("order.events", order.toString(), confirmed);
            kafka.send("order.events", order.toString(), confirmed); // redelivery
            kafka.send("platform.audit.events", "x", KafkaTestClient.envelope(UUID.randomUUID(), "AuditRecorded",
                    "Vendor", vendor.toString(), Map.of("service", "vendor-service", "actorId", owner.toString(),
                            "actorRole", "ADMIN", "action", "vendor.approved", "resourceType", "Vendor",
                            "resourceId", vendor.toString(), "outcome", "SUCCESS", "details", Map.of("to", "APPROVED"))));
            kafka.send("order.events.dlt", "k", "{broken");
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("select status from order_fact where order_id = ?", String.class, order))
                    .isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject("select count(*) from audit_log where resource_id = ?", Integer.class,
                    vendor.toString())).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("select sum(quantity) from item_fact where product_id = ?", Integer.class,
                product)).isEqualTo(2);
        mvc.perform(get("/api/v1/admin/analytics/overview").with(TestJwt.admin(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventuallyConsistent").value(true))
                .andExpect(jsonPath("$.totals.confirmed").isNumber())
                .andExpect(jsonPath("$.ordersOverTime.length()").value(30));
        mvc.perform(get("/api/v1/vendor/analytics/overview").with(TestJwt.vendor(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.net").value(new BigDecimal("72.00").doubleValue()))
                .andExpect(jsonPath("$.bestSellers[0].units").value(2));
        // another vendor sees nothing of V1
        mvc.perform(get("/api/v1/vendor/analytics/overview").with(TestJwt.vendor(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/analytics/overview").with(TestJwt.vendor(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").param("action", "vendor.").with(TestJwt.admin(UUID.randomUUID())))
                .andExpect(jsonPath("$.items[0].action").value("vendor.approved"));
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from dead_letter where topic = 'order.events.dlt'", Integer.class)).isPositive());
    }
}
