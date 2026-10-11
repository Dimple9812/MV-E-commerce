package com.marketflow.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.catalog.cache.CacheKeys;
import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.RedisInitializer;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MarketflowIntegrationTest
@ContextConfiguration(initializers = RedisInitializer.class)
@TestPropertySource(properties = "marketflow.catalog.image-dir=target/test-images")
class CatalogApiIT {

    static final String AUDIO = "10000000-0000-0000-0000-000000000011";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    UUID userA;
    UUID vendorA;
    UUID userB;
    UUID vendorB;
    UUID pendingUser;
    final UUID admin = UUID.randomUUID();

    @BeforeEach
    void vendors() {
        userA = UUID.randomUUID();
        vendorA = UUID.randomUUID();
        userB = UUID.randomUUID();
        vendorB = UUID.randomUUID();
        pendingUser = UUID.randomUUID();
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            vendorEvent(kafka, vendorA, userA, "APPROVED", "Sound Lab");
            vendorEvent(kafka, vendorB, userB, "APPROVED", "Other Shop");
            vendorEvent(kafka, UUID.randomUUID(), pendingUser, "PENDING", "Waiting Co");
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from vendor_projection where owner_user_id in (?,?,?)", Integer.class,
                userA, userB, pendingUser) == 3);
    }

    private void vendorEvent(KafkaTestClient kafka, UUID vendorId, UUID owner, String status, String name) {
        kafka.send("vendor.events", vendorId.toString(), KafkaTestClient.envelope(UUID.randomUUID(),
                "APPROVED".equals(status) ? "VendorApproved" : "VendorApplicationSubmitted", "Vendor",
                vendorId.toString(), Map.of("vendorId", vendorId.toString(), "ownerUserId", owner.toString(),
                        "businessName", name, "slug", name.toLowerCase().replace(' ', '-'), "status", status,
                        "vendorVersion", 1)));
    }

    private String createProduct(UUID user, String name, String price) throws Exception {
        String body = mvc.perform(post("/api/v1/vendor/products").with(TestJwt.vendor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("categoryId", AUDIO, "name", name,
                                "description", "Wireless noise cancelling over-ear headphones", "brand", "Acoustica",
                                "variants", List.of(Map.of("sku", "SKU-" + UUID.randomUUID().toString().substring(0, 8),
                                        "name", "Black", "price", price))))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private void publish(UUID user, String productId) throws Exception {
        mvc.perform(post("/api/v1/vendor/products/" + productId + "/publish").with(TestJwt.vendor(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void publishedProductIsPublicAndDraftIsNot() throws Exception {
        String id = createProduct(userA, "Studio Headphones", "199.00");
        mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isNotFound());
        publish(userA, id);
        mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.vendor.name").value("Sound Lab"))
                .andExpect(jsonPath("$.variants[0].price").value(199.00));
    }

    @Test
    void vendorCannotReadOrModifyAnotherVendorsProduct() throws Exception {
        String id = createProduct(userA, "Private Draft", "50.00");
        mvc.perform(get("/api/v1/vendor/products/" + id).with(TestJwt.vendor(userB))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/vendor/products/" + id).with(TestJwt.vendor(userB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + AUDIO + "\",\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/vendor/products/" + id + "/publish").with(TestJwt.vendor(userB)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/vendor/products/" + id).with(TestJwt.vendor(userB)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/vendor/products/" + id).with(TestJwt.vendor(userA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Private Draft"));
    }

    @Test
    void unapprovedVendorCannotCreateOrPublishProducts() throws Exception {
        // the JWT even carries VENDOR, but the vendor is still PENDING in the projection
        mvc.perform(post("/api/v1/vendor/products").with(TestJwt.vendor(pendingUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + AUDIO + "\",\"name\":\"Sneaky Item\"}"))
                .andExpect(status().isForbidden());
        // a customer without the VENDOR role is stopped by role checks
        mvc.perform(post("/api/v1/vendor/products").with(TestJwt.customer(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + AUDIO + "\",\"name\":\"Sneaky Item\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidPricesAndQuantitiesAreRejected() throws Exception {
        for (String price : List.of("0", "-5", "12.345", "abc")) {
            mvc.perform(post("/api/v1/vendor/products").with(TestJwt.vendor(userA))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":\"" + AUDIO + "\",\"name\":\"Bad Price\",\"variants\":[{\"sku\":\"BP-1\","
                                    + "\"name\":\"x\",\"price\":\"" + price + "\"}]}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/vendor/products").with(TestJwt.vendor(userA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + AUDIO + "\",\"name\":\"Bad Limit\",\"maxPerOrder\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullTextSearchFiltersAndSorts() throws Exception {
        String tag = "zq" + UUID.randomUUID().toString().substring(0, 6);
        String cheap = createProduct(userA, "Budget Earbuds " + tag, "19.99");
        String pricey = createProduct(userB, "Premium Speaker " + tag, "349.00");
        publish(userA, cheap);
        publish(userB, pricey);
        mvc.perform(get("/api/v1/products").param("q", tag).param("sort", "price_desc"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(pricey));
        mvc.perform(get("/api/v1/products").param("q", tag).param("maxPrice", "50"))
                .andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].id").value(cheap));
        mvc.perform(get("/api/v1/products").param("q", tag).param("vendorId", vendorB.toString()))
                .andExpect(jsonPath("$.items[0].vendorName").value("Other Shop"));
        mvc.perform(get("/api/v1/products").param("q", tag).param("category", "electronics"))
                .andExpect(jsonPath("$.totalItems").value(2)); // parent category includes children
        // injection attempt is just a search term
        mvc.perform(get("/api/v1/products").param("q", "'; DROP TABLE product; --").param("sort", "id;drop"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from product", Integer.class)).isPositive();
    }

    @Test
    void productDetailIsCachedAndInvalidatedOnPriceChange() throws Exception {
        String id = createProduct(userA, "Cached Headphones", "100.00");
        publish(userA, id);
        mvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.variants[0].price").value(100.00));
        assertThat(redis.hasKey(CacheKeys.product(UUID.fromString(id)))).isTrue();

        String variantId = json.readTree(mvc.perform(get("/api/v1/vendor/products/" + id).with(TestJwt.vendor(userA)))
                .andReturn().getResponse().getContentAsString()).at("/variants/0/id").asText();
        mvc.perform(patch("/api/v1/vendor/products/" + id + "/variants/" + variantId).with(TestJwt.vendor(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Black\",\"price\":\"89.00\",\"active\":true}"))
                .andExpect(status().isOk());
        assertThat(redis.hasKey(CacheKeys.product(UUID.fromString(id)))).isFalse();
        mvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.variants[0].price").value(89.00));

        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ? and "
                + "event_type = 'ProductPriceChanged'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void publishEmitsSnapshotEventWithVersion() throws Exception {
        String id = createProduct(userA, "Evented Speaker", "75.00");
        publish(userA, id);
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            List<JsonNode> events = kafka.readUntil("catalog.product.events", Duration.ofSeconds(20),
                    l -> l.stream().anyMatch(e -> id.equals(e.path("aggregateId").asText())
                            && "ProductPublished".equals(e.path("eventType").asText())));
            JsonNode pub = events.stream().filter(e -> id.equals(e.path("aggregateId").asText())
                    && "ProductPublished".equals(e.path("eventType").asText())).findFirst().orElseThrow();
            assertThat(pub.at("/payload/status").asText()).isEqualTo("PUBLISHED");
            assertThat(pub.at("/payload/vendorId").asText()).isEqualTo(vendorA.toString());
            assertThat(pub.at("/payload/variants/0/price").decimalValue()).isEqualByComparingTo("75.00");
            assertThat(pub.at("/payload/productVersion").asLong()).isPositive();
        }
    }

    @Test
    void stockEventsUpdateAvailabilityAndStaleOnesAreIgnored() throws Exception {
        String id = createProduct(userA, "Stocked Speaker", "60.00");
        publish(userA, id);
        String variantId = jdbc.queryForObject("select id::text from product_variant where product_id = ?::uuid",
                String.class, id);
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("inventory.events", variantId, KafkaTestClient.envelope(UUID.randomUUID(), "StockLevelChanged",
                    "InventoryItem", variantId, Map.of("variantId", variantId, "productId", id, "available", 7,
                            "stockVersion", 5)));
            kafka.send("inventory.events", variantId, KafkaTestClient.envelope(UUID.randomUUID(), "StockLevelChanged",
                    "InventoryItem", variantId, Map.of("variantId", variantId, "productId", id, "available", 99,
                            "stockVersion", 3))); // older version arrives late
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from processed_event where consumer = 'catalog-service.stock'", Integer.class))
                .isGreaterThanOrEqualTo(2));
        assertThat(jdbc.queryForObject("select available_qty from product_variant where id = ?::uuid", Integer.class,
                variantId)).isEqualTo(7);
        mvc.perform(get("/api/v1/products/" + id)).andExpect(jsonPath("$.inStock").value(true));
    }

    @Test
    void blockedProductDisappearsFromStorefront() throws Exception {
        String id = createProduct(userA, "Questionable Gadget", "15.00");
        publish(userA, id);
        mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/products/" + id + "/block").with(TestJwt.vendor(userA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/products/" + id + "/block").with(TestJwt.admin(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Counterfeit report\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));
        mvc.perform(get("/api/v1/products/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void imageUploadAcceptsRealImagesOnly() throws Exception {
        String id = createProduct(userA, "Pictured Item", "30.00");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        mvc.perform(multipart("/api/v1/vendor/products/" + id + "/images/upload")
                        .file(new MockMultipartFile("file", "a.png", "image/png", png)).with(TestJwt.vendor(userA)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.images[0].url")
                        .value(org.hamcrest.Matchers.startsWith("/api/v1/products/images/")));
        mvc.perform(multipart("/api/v1/vendor/products/" + id + "/images/upload")
                        .file(new MockMultipartFile("file", "evil.png", "image/png", "<script>".getBytes()))
                        .with(TestJwt.vendor(userA)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/products/images/..%2F..%2Fetc%2Fpasswd")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/products/images/passwd.png")).andExpect(status().isNotFound());
    }
}
