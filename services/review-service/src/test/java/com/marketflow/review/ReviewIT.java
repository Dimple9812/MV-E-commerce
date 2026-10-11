package com.marketflow.review;

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
class ReviewIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    static final String REVIEW = "{\"rating\":5,\"title\":\"Excellent\",\"body\":\"Works exactly as described.\"}";

    private UUID purchase(UUID customer, UUID product, String type) {
        UUID order = UUID.randomUUID();
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("order.events", order.toString(), KafkaTestClient.envelope(UUID.randomUUID(), type, "Order",
                    order.toString(), Map.of("orderId", order.toString(), "customerId", customer.toString(),
                            "suborders", List.of(Map.of("items", List.of(Map.of("productId", product.toString())))))));
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from purchase where order_id = ?", Integer.class, order) == 1);
        return order;
    }

    @Test
    void customerWhoNeverBoughtTheProductCannotReviewIt() throws Exception {
        UUID product = UUID.randomUUID();
        mvc.perform(post("/api/v1/products/" + product + "/reviews").with(TestJwt.customer(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/products/" + product + "/reviews").contentType(MediaType.APPLICATION_JSON)
                .content(REVIEW)).andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedBuyerReviewsOnceAndRatingIsAggregatedAndPublished() throws Exception {
        UUID product = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        purchase(a, product, "OrderConfirmed");
        purchase(b, product, "OrderConfirmed");
        mvc.perform(get("/api/v1/products/" + product + "/reviews/eligibility").with(TestJwt.customer(a)))
                .andExpect(jsonPath("$.eligible").value(true));
        mvc.perform(post("/api/v1/products/" + product + "/reviews").with(TestJwt.customer(a))
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.verifiedPurchase").value(true));
        mvc.perform(post("/api/v1/products/" + product + "/reviews").with(TestJwt.customer(a))
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/products/" + product + "/reviews").with(TestJwt.customer(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":2,\"title\":\"Meh\",\"body\":\"Stopped working after a week.\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/products/" + product + "/rating"))
                .andExpect(jsonPath("$.reviewCount").value(2)).andExpect(jsonPath("$.averageRating").value(3.50));
        mvc.perform(get("/api/v1/products/" + product + "/reviews")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].customerId").doesNotExist());
        String last = jdbc.queryForObject("select payload::text from outbox_event where event_type = "
                + "'ProductRatingUpdated' and aggregate_id = ? order by id desc limit 1", String.class,
                product.toString());
        assertThat(last).contains("\"reviewCount\": 2").contains("\"averageRating\": 3.5");
    }

    @Test
    void cancelledPurchaseIsNotEligible() throws Exception {
        UUID product = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID order = purchase(customer, product, "OrderConfirmed");
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("order.events", order.toString(), KafkaTestClient.envelope(UUID.randomUUID(), "OrderCancelled",
                    "Order", order.toString(), Map.of("orderId", order.toString(), "customerId", customer.toString())));
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select cancelled from purchase where order_id = ?", Boolean.class, order));
        mvc.perform(post("/api/v1/products/" + product + "/reviews").with(TestJwt.customer(customer))
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isForbidden());
    }

    @Test
    void moderationHidesReviewAndUpdatesRatingAndOthersCannotEdit() throws Exception {
        UUID product = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        purchase(customer, product, "OrderConfirmed");
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(
                        post("/api/v1/products/" + product + "/reviews").with(TestJwt.customer(customer))
                                .contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/reviews/" + id)
                        .with(TestJwt.customer(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(REVIEW))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/reviews/" + id + "/hide").with(TestJwt.customer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reviews/" + id + "/hide").with(TestJwt.admin(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"spam\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        mvc.perform(get("/api/v1/products/" + product + "/rating")).andExpect(jsonPath("$.reviewCount").value(0));
        mvc.perform(get("/api/v1/products/" + product + "/reviews")).andExpect(jsonPath("$.length()").value(0));
    }
}
