package com.marketflow.vendor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
class VendorApiIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    private final UUID admin = UUID.randomUUID();

    private String apply(UUID user, String name) throws Exception {
        String body = mvc.perform(post("/api/v1/vendors/applications").with(TestJwt.customer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("businessName", name, "contactEmail",
                                "shop@example.test", "country", "US", "description", "Fine goods"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private void approve(String vendorId) throws Exception {
        mvc.perform(post("/api/v1/admin/vendors/" + vendorId + "/approve").with(TestJwt.admin(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void applicationRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/vendors/applications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsCanApprove() throws Exception {
        UUID user = UUID.randomUUID();
        String vendorId = apply(user, "Nordic Knits");
        mvc.perform(post("/api/v1/admin/vendors/" + vendorId + "/approve").with(TestJwt.customer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/vendors/" + vendorId + "/approve").with(TestJwt.vendor(user)))
                .andExpect(status().isForbidden());
        approve(vendorId);
    }

    @Test
    void approvalPublishesVersionedVendorApprovedEvent() throws Exception {
        UUID user = UUID.randomUUID();
        String vendorId = apply(user, "Lumen Lighting");
        approve(vendorId);
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            List<JsonNode> events = kafka.readUntil("vendor.events", Duration.ofSeconds(20), l -> l.stream()
                    .anyMatch(e -> vendorId.equals(e.path("aggregateId").asText())
                            && "VendorApproved".equals(e.path("eventType").asText())));
            JsonNode approved = events.stream().filter(e -> vendorId.equals(e.path("aggregateId").asText())
                    && "VendorApproved".equals(e.path("eventType").asText())).findFirst().orElseThrow();
            assertThat(approved.at("/payload/ownerUserId").asText()).isEqualTo(user.toString());
            assertThat(approved.at("/payload/status").asText()).isEqualTo("APPROVED");
            long submittedVersion = events.stream().filter(e -> vendorId.equals(e.path("aggregateId").asText())
                            && "VendorApplicationSubmitted".equals(e.path("eventType").asText()))
                    .findFirst().orElseThrow().at("/payload/vendorVersion").asLong();
            assertThat(approved.at("/payload/vendorVersion").asLong()).isGreaterThan(submittedVersion);
        }
    }

    @Test
    void duplicateApplicationConflictsButRejectedVendorMayReapply() throws Exception {
        UUID user = UUID.randomUUID();
        String vendorId = apply(user, "Second Try");
        mvc.perform(post("/api/v1/vendors/applications").with(TestJwt.customer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"Again\",\"contactEmail\":\"a@b.co\",\"country\":\"US\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/vendors/" + vendorId + "/reject").with(TestJwt.admin(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Missing documents\"}"))
                .andExpect(status().isOk());
        apply(user, "Second Try Ltd");
        mvc.perform(get("/api/v1/admin/vendors/" + vendorId + "/history").with(TestJwt.admin(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void illegalTransitionIsRejected() throws Exception {
        String vendorId = apply(UUID.randomUUID(), "Not Yet");
        mvc.perform(post("/api/v1/admin/vendors/" + vendorId + "/reinstate").with(TestJwt.admin(admin)))
                .andExpect(status().isConflict());
    }

    @Test
    void pendingVendorCannotUseVendorPortalEvenWithVendorRoleClaim() throws Exception {
        UUID user = UUID.randomUUID();
        apply(user, "Pending Shop");
        mvc.perform(get("/api/v1/vendor/dashboard").with(TestJwt.vendor(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vendors/me").with(TestJwt.customer(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void publicProfileHidesUnapprovedVendorsAndPrivateFields() throws Exception {
        UUID user = UUID.randomUUID();
        String vendorId = apply(user, "Quiet Shop");
        mvc.perform(get("/api/v1/vendors/" + vendorId)).andExpect(status().isNotFound());
        approve(vendorId);
        mvc.perform(get("/api/v1/vendors/" + vendorId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Quiet Shop"))
                .andExpect(jsonPath("$.contactEmail").doesNotExist())
                .andExpect(jsonPath("$.commissionRate").doesNotExist());
    }

    @Test
    void earningsLedgerIsBuiltOnceFromOrderEventsAndIsolatedPerVendor() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String vendorA = apply(userA, "Vendor A");
        String vendorB = apply(userB, "Vendor B");
        approve(vendorA);
        approve(vendorB);
        UUID orderId = UUID.randomUUID();
        UUID subA = UUID.randomUUID();
        UUID subB = UUID.randomUUID();
        Map<String, Object> payload = Map.of("orderId", orderId.toString(), "currency", "USD",
                "wasConfirmed", true, "suborders", List.of(
                        Map.of("suborderId", subA.toString(), "vendorId", vendorA, "itemsSubtotal", "100.00",
                                "discountAmount", "0.00", "shippingAmount", "5.00", "commissionAmount", "10.00",
                                "vendorNetAmount", "95.00"),
                        Map.of("suborderId", subB.toString(), "vendorId", vendorB, "itemsSubtotal", "40.00",
                                "discountAmount", "0.00", "shippingAmount", "0.00", "commissionAmount", "4.00",
                                "vendorNetAmount", "36.00")));
        String confirmed = KafkaTestClient.envelope(UUID.randomUUID(), "OrderConfirmed", "Order", orderId.toString(),
                payload);
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("order.events", orderId.toString(), confirmed);
            kafka.send("order.events", orderId.toString(), confirmed); // redelivery
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from earnings_entry where order_id = ?", Integer.class, orderId)).isEqualTo(2));

        mvc.perform(get("/api/v1/vendor/earnings").with(TestJwt.vendor(userA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].suborderId").value(subA.toString()))
                .andExpect(jsonPath("$.items[0].netAmount").value(95.00));
        mvc.perform(get("/api/v1/vendor/dashboard").with(TestJwt.vendor(userB))).andExpect(status().isOk())
                .andExpect(jsonPath("$.earnings.netEarnings").value(36.00))
                .andExpect(jsonPath("$.vendor.id").value(vendorB));

        // payout cannot exceed balance
        mvc.perform(post("/api/v1/admin/payouts").with(TestJwt.admin(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":\"" + vendorB + "\",\"amount\":50.00,\"reference\":\"PO-1\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/admin/payouts").with(TestJwt.admin(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":\"" + vendorB + "\",\"amount\":30.00,\"reference\":\"PO-1\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/vendor/dashboard").with(TestJwt.vendor(userB)))
                .andExpect(jsonPath("$.earnings.balance").value(6.00));
    }
}
