package com.marketflow.notification;

import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MarketflowIntegrationTest
@TestPropertySource(properties = {"marketflow.notification.initial-backoff-ms=200",
        "marketflow.notification.max-backoff-ms=500", "marketflow.notification.max-attempts=4"})
class NotificationIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    private UUID registerUser(KafkaTestClient kafka, String email) {
        UUID user = UUID.randomUUID();
        kafka.send("identity.user.events", user.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                "UserRegistered", "User", user.toString(), Map.of("userId", user.toString(), "email", email,
                        "fullName", "Test Person")));
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from user_contact where user_id = ?", Integer.class, user) == 1);
        return user;
    }

    private UUID approvedVendor(KafkaTestClient kafka) {
        UUID vendor = UUID.randomUUID();
        kafka.send("vendor.events", vendor.toString(), KafkaTestClient.envelope(UUID.randomUUID(), "VendorApproved",
                "Vendor", vendor.toString(), Map.of("vendorId", vendor.toString(), "ownerUserId",
                        UUID.randomUUID().toString(), "businessName", "Test Vendor", "contactEmail",
                        "vendor@example.test", "status", "APPROVED", "vendorVersion", 2)));
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from vendor_contact where vendor_id = ?", Integer.class, vendor) == 1);
        return vendor;
    }

    private String orderConfirmed(UUID eventId, UUID customer, UUID vendor) {
        UUID order = UUID.randomUUID();
        return KafkaTestClient.envelope(eventId, "OrderConfirmed", "Order", order.toString(), Map.of(
                "orderId", order.toString(), "customerId", customer.toString(), "currency", "USD",
                "totalAmount", "99.00", "suborders", List.of(Map.of("suborderId", UUID.randomUUID().toString(),
                        "vendorId", vendor.toString(), "total", "99.00", "items", List.of(Map.of("sku", "X-1"))))));
    }

    private List<Map<String, Object>> notificationsFor(UUID eventId) {
        return jdbc.queryForList("select channel, status, template, attempts from notification where "
                + "source_event_id = ? order by template, channel", eventId);
    }

    @Test
    void orderConfirmedNotifiesCustomerAndVendorExactlyOnce() {
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            UUID customer = registerUser(kafka, "buyer@example.test");
            UUID vendor = approvedVendor(kafka);
            UUID eventId = UUID.randomUUID();
            String event = orderConfirmed(eventId, customer, vendor);
            kafka.send("order.events", "k", event);
            kafka.send("order.events", "k", event); // redelivery
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(notificationsFor(eventId))
                    .hasSize(4).allMatch(n -> "SENT".equals(n.get("status"))));
            Thread.sleep(1000);
            assertThat(notificationsFor(eventId)).hasSize(4);
            assertThat(jdbc.queryForObject("select count(*) from delivery_attempt d join notification n on "
                    + "n.id = d.notification_id where n.source_event_id = ?", Integer.class, eventId)).isEqualTo(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void transientSmtpFailuresAreRetriedThroughRetryTopicsUntilDelivered() {
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            UUID customer = registerUser(kafka, "buyer+flaky@example.test");
            UUID vendor = approvedVendor(kafka);
            UUID eventId = UUID.randomUUID();
            kafka.send("order.events", "k", orderConfirmed(eventId, customer, vendor));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "select status from notification where source_event_id = ? and channel = 'EMAIL' "
                            + "and template = 'order_confirmed'", String.class, eventId)).isEqualTo("SENT"));
            Integer attempts = jdbc.queryForObject("select attempts from notification where source_event_id = ? "
                    + "and channel = 'EMAIL' and template = 'order_confirmed'", Integer.class, eventId);
            assertThat(attempts).isEqualTo(3); // fail, fail, succeed
            // the vendor e-mail of the same event was sent once, not once per retry
            assertThat(jdbc.queryForObject("select attempts from notification where source_event_id = ? "
                    + "and channel = 'EMAIL' and template = 'new_suborder'", Integer.class, eventId)).isEqualTo(1);
        }
    }

    @Test
    void permanentFailureGoesStraightToDeadLetterTopic() {
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            UUID customer = registerUser(kafka, "nobody+bounce@example.test");
            UUID vendor = approvedVendor(kafka);
            UUID eventId = UUID.randomUUID();
            kafka.send("order.events", "k", orderConfirmed(eventId, customer, vendor));
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                    "select status from notification where source_event_id = ? and channel = 'EMAIL' "
                            + "and template = 'order_confirmed'", String.class, eventId)).isEqualTo("FAILED"));
            var dlt = kafka.readUntil("order.events-notification-dlt", Duration.ofSeconds(20),
                    l -> l.stream().anyMatch(n -> eventId.toString().equals(n.path("eventId").asText())));
            assertThat(dlt).anyMatch(n -> eventId.toString().equals(n.path("eventId").asText()));
            assertThat(jdbc.queryForObject("select attempts from notification where source_event_id = ? and "
                    + "channel = 'EMAIL' and template = 'order_confirmed'", Integer.class, eventId)).isEqualTo(1);
        }
    }

    @Test
    void inAppInboxIsPrivate() throws Exception {
        UUID customer;
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            customer = registerUser(kafka, "inbox@example.test");
            UUID vendor = approvedVendor(kafka);
            UUID eventId = UUID.randomUUID();
            kafka.send("order.events", "k", orderConfirmed(eventId, customer, vendor));
            await().atMost(Duration.ofSeconds(20)).until(() -> notificationsFor(eventId).size() == 4);
        }
        String body = mvc.perform(get("/api/v1/notifications").with(TestJwt.customer(customer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].template").value("order_confirmed"))
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1");
        mvc.perform(post("/api/v1/notifications/" + id + "/read").with(TestJwt.customer(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/" + id + "/read").with(TestJwt.customer(customer)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications/unread-count").with(TestJwt.customer(customer)))
                .andExpect(jsonPath("$.unread").value(0));
    }
}
