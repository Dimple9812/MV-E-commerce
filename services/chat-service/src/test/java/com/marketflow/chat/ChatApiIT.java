package com.marketflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.chat.ChatFixtures.Sale;
import com.marketflow.chat.ChatFixtures.Shop;
import com.marketflow.test.MarketflowIntegrationTest;
import com.marketflow.test.PostgresInitializer;
import com.marketflow.test.RedisInitializer;
import com.marketflow.test.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MarketflowIntegrationTest
@ContextConfiguration(initializers = {PostgresInitializer.class, RedisInitializer.class})
@TestPropertySource(properties = {"marketflow.chat.rate-limit.messages=8", "marketflow.chat.stats-initial-delay=1h"})
class ChatApiIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    ChatFixtures fx;
    Shop shop;
    UUID product;
    UUID buyer;

    @BeforeEach
    void setUp() {
        fx = new ChatFixtures(brokers, jdbc);
        shop = fx.shop("Aurora Sound " + UUID.randomUUID().toString().substring(0, 6), "APPROVED");
        product = fx.product(shop, "Aurora Studio Headphones");
        buyer = UUID.randomUUID();
    }

    // ---------- helpers ----------

    RequestPostProcessor asBuyer() {
        return TestJwt.customer(buyer);
    }

    RequestPostProcessor asSeller() {
        return TestJwt.vendor(shop.ownerId());
    }

    ResultActions start(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post("/api/v1/chat/conversations").with(who).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    String startAboutProduct() throws Exception {
        return read(start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\",\"productId\":\"" + product + "\"}")
                .andExpect(status().isCreated())).get("id").asText();
    }

    ResultActions sendText(RequestPostProcessor who, String conversation, UUID clientId, String text) throws Exception {
        return mvc.perform(post("/api/v1/chat/conversations/" + conversation + "/messages").with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("clientMessageId", clientId.toString(),
                        "type", "TEXT", "body", text))));
    }

    JsonNode read(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    // ---------- conversations ----------

    @Test
    void oneConversationPerBuyerShopAndContextEvenWhenStartedConcurrently() throws Exception {
        String body = "{\"vendorId\":\"" + shop.vendorId() + "\",\"productId\":\"" + product + "\"}";
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Callable<String> call = () -> read(start(asBuyer(), body)).get("id").asText();
            java.util.List<Future<String>> results = pool.invokeAll(java.util.Collections.nCopies(6, call));
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Future<String> f : results) {
                ids.add(f.get());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        start(asBuyer(), body).andExpect(status().isOk()).andExpect(jsonPath("$.context.kind").value("PRODUCT"))
                .andExpect(jsonPath("$.context.productName").value("Aurora Studio Headphones"))
                .andExpect(jsonPath("$.context.price").value(139))
                .andExpect(jsonPath("$.counterpart.kind").value("SHOP"))
                .andExpect(jsonPath("$.side").value("BUYER"));
        // a general question to the same shop is a different context
        start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.context.kind").value("GENERAL"));
        start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\"}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from conversation where buyer_id = ?", Integer.class, buyer))
                .isEqualTo(2);
    }

    @Test
    void startingIsValidatedAgainstProjections() throws Exception {
        Shop other = fx.shop("Other Shop", "APPROVED");
        Shop suspended = fx.shop("Paused Shop", "SUSPENDED");
        UUID otherProduct = fx.product(other, "Kettle");
        start(asBuyer(), "{\"vendorId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        start(asBuyer(), "{\"vendorId\":\"" + suspended.vendorId() + "\"}").andExpect(status().isNotFound());
        start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\",\"productId\":\"" + otherProduct + "\"}")
                .andExpect(status().isNotFound());
        start(asSeller(), "{\"vendorId\":\"" + shop.vendorId() + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("own_shop"));
        Sale someoneElses = fx.order(UUID.randomUUID(), shop, product, "OrderConfirmed");
        start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\",\"suborderId\":\"" + someoneElses.suborderId() + "\"}")
                .andExpect(status().isNotFound());
        start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\",\"productId\":\"" + product + "\",\"suborderId\":\""
                + someoneElses.suborderId() + "\"}").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"vendorId\":\"" + shop.vendorId() + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheBuyerAndTheShopTakePartAdminsOnlyRead() throws Exception {
        String id = startAboutProduct();
        sendText(asBuyer(), id, UUID.randomUUID(), "Does it come with a case?").andExpect(status().isCreated());
        Shop other = fx.shop("Nosy Shop", "APPROVED");
        RequestPostProcessor stranger = TestJwt.customer(UUID.randomUUID());
        RequestPostProcessor otherSeller = TestJwt.vendor(other.ownerId());
        for (RequestPostProcessor outsider : java.util.List.of(stranger, otherSeller)) {
            mvc.perform(get("/api/v1/chat/conversations/" + id).with(outsider)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages").with(outsider))
                    .andExpect(status().isNotFound());
            sendText(outsider, id, UUID.randomUUID(), "hi").andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/chat/conversations/" + id).with(asSeller())).andExpect(status().isOk())
                .andExpect(jsonPath("$.side").value("VENDOR")).andExpect(jsonPath("$.counterpart.kind").value("BUYER"));
        RequestPostProcessor admin = TestJwt.admin(UUID.randomUUID());
        mvc.perform(get("/api/v1/admin/chat/conversations/" + id + "/messages").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        sendText(admin, id, UUID.randomUUID(), "admin here").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/chat/reports").with(asSeller())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/vendor/chat/conversations").with(asBuyer())).andExpect(status().isForbidden());
    }

    @Test
    void sendingIsIdempotentOnTheClientMessageId() throws Exception {
        String id = startAboutProduct();
        UUID clientId = UUID.randomUUID();
        long first = read(sendText(asBuyer(), id, clientId, "Is it in stock?").andExpect(status().isCreated()))
                .get("id").asLong();
        long retry = read(sendText(asBuyer(), id, clientId, "Is it in stock?").andExpect(status().isOk()))
                .get("id").asLong();
        assertThat(retry).isEqualTo(first);
        // concurrent retries of one new message
        UUID racing = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            Callable<Long> call = () -> read(sendText(asBuyer(), id, racing, "Ships to Canada?")).get("id").asLong();
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Future<Long> f : pool.invokeAll(java.util.Collections.nCopies(5, call))) {
                ids.add(f.get());
            }
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from message where conversation_id = ?::uuid", Integer.class,
                id)).isEqualTo(2);
        // one ChatMessageSent per stored message, addressed to the shop, without the text
        JsonNode event = json.readTree(jdbc.queryForObject("select payload::text from outbox_event where event_type ="
                + " 'ChatMessageSent' and aggregate_id = ? order by id limit 1", String.class, id));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type = 'ChatMessageSent'"
                + " and aggregate_id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(event.path("payload").path("recipientUserIds").get(0).asText()).isEqualTo(shop.ownerId().toString());
        assertThat(event.path("payload").path("recipientSide").asText()).isEqualTo("VENDOR");
        assertThat(event.toString()).doesNotContain("Is it in stock?");
    }

    @Test
    void contactDetailsAreRefusedAndCountedForAdmins() throws Exception {
        String id = startAboutProduct();
        sendText(asBuyer(), id, UUID.randomUUID(), "text me on +1 415 555 0199")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("contact_info_not_allowed"));
        sendText(asSeller(), id, UUID.randomUUID(), "pay me at paypal.me/aurora")
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from message where conversation_id = ?::uuid", Integer.class,
                id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from content_violation where conversation_id = ?::uuid",
                Integer.class, id)).isEqualTo(2);
        mvc.perform(get("/api/v1/admin/chat/conversations/" + id).with(TestJwt.admin(UUID.randomUUID())))
                .andExpect(jsonPath("$.violations").value(2));
    }

    @Test
    void messageShapeIsValidated() throws Exception {
        String id = startAboutProduct();
        sendText(asBuyer(), id, UUID.randomUUID(), "   ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_message"));
        sendText(asBuyer(), id, UUID.randomUUID(), "x".repeat(2001)).andExpect(status().isBadRequest());
        String markup = "<script>alert(1)</script>";
        sendText(asBuyer(), id, UUID.randomUUID(), markup).andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value(markup)); // stored as plain text, rendered as text
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/messages").with(asBuyer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"type\":\"SYSTEM\",\"body\":\"fake\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void messagesAreRateLimitedPerUser() throws Exception {
        String id = startAboutProduct();
        int created = 0;
        int limited = 0;
        for (int i = 0; i < 10; i++) {
            int code = sendText(asBuyer(), id, UUID.randomUUID(), "message " + i).andReturn().getResponse().getStatus();
            if (code == 201) {
                created++;
            } else if (code == 429) {
                limited++;
            }
        }
        assertThat(created).isEqualTo(8);
        assertThat(limited).isEqualTo(2);
        // the shop is not affected by the buyer's limit
        sendText(asSeller(), id, UUID.randomUUID(), "Hello!").andExpect(status().isCreated());
    }

    @Test
    void editAndDeleteOnlyYourOwnMessages() throws Exception {
        String id = startAboutProduct();
        long msg = read(sendText(asBuyer(), id, UUID.randomUUID(), "Is it waterproff?")).get("id").asLong();
        mvc.perform(patch("/api/v1/chat/messages/" + msg).with(asSeller()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"hacked\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/chat/messages/" + msg).with(asBuyer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Is it waterproof?\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Is it waterproof?")).andExpect(jsonPath("$.editedAt").exists());
        mvc.perform(delete("/api/v1/chat/messages/" + msg).with(asBuyer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true)).andExpect(jsonPath("$.body").doesNotExist());
        mvc.perform(delete("/api/v1/chat/messages/" + msg).with(asBuyer())).andExpect(status().isConflict());
        // participants see a tombstone; an administrator reviewing a dispute still sees the text
        mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages").with(asSeller()))
                .andExpect(jsonPath("$.items[0].deleted").value(true)).andExpect(jsonPath("$.items[0].body").doesNotExist());
        mvc.perform(get("/api/v1/admin/chat/conversations/" + id + "/messages").with(TestJwt.admin(UUID.randomUUID())))
                .andExpect(jsonPath("$.items[0].body").value("Is it waterproof?"));
        // past the edit window
        long old = read(sendText(asBuyer(), id, UUID.randomUUID(), "old message")).get("id").asLong();
        jdbc.update("update message set created_at = now() - interval '11 minutes' where id = ?", old);
        mvc.perform(delete("/api/v1/chat/messages/" + old).with(asBuyer())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("edit_window_passed"));
    }

    @Test
    void timelineIsCursorPaginatedBothWays() throws Exception {
        String id = startAboutProduct();
        long[] ids = new long[7];
        for (int i = 0; i < 7; i++) {
            ids[i] = read(sendText(i % 2 == 0 ? asBuyer() : asSeller(), id, UUID.randomUUID(), "m" + i)).get("id").asLong();
        }
        JsonNode latest = read(mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages?limit=3").with(asBuyer())));
        assertThat(latest.get("items").findValuesAsText("body")).containsExactly("m4", "m5", "m6");
        assertThat(latest.get("hasMore").asBoolean()).isTrue();
        JsonNode older = read(mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages?limit=3&before="
                + ids[4]).with(asBuyer())));
        assertThat(older.get("items").findValuesAsText("body")).containsExactly("m1", "m2", "m3");
        JsonNode oldest = read(mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages?limit=3&before="
                + ids[1]).with(asBuyer())));
        assertThat(oldest.get("items").findValuesAsText("body")).containsExactly("m0");
        assertThat(oldest.get("hasMore").asBoolean()).isFalse();
        JsonNode resync = read(mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages?after=" + ids[4])
                .with(asBuyer())));
        assertThat(resync.get("items").findValuesAsText("body")).containsExactly("m5", "m6");
    }

    @Test
    void readMarkersDriveUnreadCountsAndSeenTicks() throws Exception {
        String id = startAboutProduct();
        long[] ids = new long[4];
        for (int i = 0; i < 4; i++) {
            ids[i] = read(sendText(asBuyer(), id, UUID.randomUUID(), "q" + i)).get("id").asLong();
        }
        mvc.perform(get("/api/v1/chat/unread-count").with(asSeller())).andExpect(jsonPath("$.asVendor").value(4));
        mvc.perform(get("/api/v1/vendor/chat/conversations").with(asSeller()))
                .andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].unread").value(4))
                .andExpect(jsonPath("$.items[0].lastMessage.text").value("q3"))
                .andExpect(jsonPath("$.items[0].counterpart.name").value("Customer"));
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/read").with(asSeller())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadMessageId\":" + ids[1] + "}"))
                .andExpect(jsonPath("$.lastReadMessageId").value(ids[1]));
        mvc.perform(get("/api/v1/chat/unread-count").with(asSeller())).andExpect(jsonPath("$.asVendor").value(2));
        mvc.perform(get("/api/v1/chat/conversations/" + id).with(asBuyer()))
                .andExpect(jsonPath("$.theirLastReadMessageId").value(ids[1]));
        // markers never move backwards, and never past the newest message
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/read").with(asSeller())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadMessageId\":1}"))
                .andExpect(jsonPath("$.lastReadMessageId").value(ids[1]));
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/read").with(asSeller())
                .contentType(MediaType.APPLICATION_JSON).content("{\"lastReadMessageId\":" + Long.MAX_VALUE / 2 + "}"))
                .andExpect(jsonPath("$.lastReadMessageId").value(ids[3]));
        // answering implies having read the conversation
        long reply = read(sendText(asSeller(), id, UUID.randomUUID(), "Yes, all four are in stock")).get("id").asLong();
        mvc.perform(get("/api/v1/chat/unread-count").with(asBuyer())).andExpect(jsonPath("$.asBuyer").value(1));
        mvc.perform(get("/api/v1/chat/conversations?limit=1").with(asBuyer()))
                .andExpect(jsonPath("$.items[0].unread").value(1));
        mvc.perform(put("/api/v1/chat/conversations/" + id + "/mute").with(asBuyer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"muted\":true}")).andExpect(jsonPath("$.muted").value(true));
        mvc.perform(get("/api/v1/chat/unread-count").with(asBuyer())).andExpect(jsonPath("$.asBuyer").value(0));
        assertThat(reply).isGreaterThan(ids[3]);
    }

    @Test
    void inboxIsKeysetPaginated() throws Exception {
        for (int i = 0; i < 3; i++) {
            Shop s = fx.shop("Shop " + i + " " + UUID.randomUUID().toString().substring(0, 4), "APPROVED");
            String c = read(start(asBuyer(), "{\"vendorId\":\"" + s.vendorId() + "\"}")).get("id").asText();
            sendText(asBuyer(), c, UUID.randomUUID(), "hello shop " + i).andExpect(status().isCreated());
        }
        JsonNode first = read(mvc.perform(get("/api/v1/chat/conversations?limit=2").with(asBuyer())));
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("items").get(0).path("lastMessage").path("text").asText()).isEqualTo("hello shop 2");
        JsonNode second = read(mvc.perform(get("/api/v1/chat/conversations?limit=2&cursor=" + first.get("nextCursor")
                .asText()).with(asBuyer())));
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.path("nextCursor").isMissingNode() || second.path("nextCursor").isNull()).isTrue();
    }

    @Test
    void productAndOrderCardsAreBuiltFromProjectionsNotFromTheClient() throws Exception {
        Sale sale = fx.order(buyer, shop, product, "OrderConfirmed");
        String id = read(start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\",\"suborderId\":\""
                + sale.suborderId() + "\"}").andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/messages").with(asBuyer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"ORDER_CARD\",\"suborderId\":\"" + sale.suborderId() + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.metadata.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.metadata.itemsSummary").value("Headphones ×1"));
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/messages").with(asBuyer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"PRODUCT_CARD\",\"productId\":\"" + product + "\",\"body\":\"this one\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.metadata.name").value("Aurora Studio Headphones"))
                .andExpect(jsonPath("$.metadata.price").value(139));
        Shop other = fx.shop("Elsewhere", "APPROVED");
        UUID foreign = fx.product(other, "Foreign");
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/messages").with(asBuyer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\"" + UUID.randomUUID()
                        + "\",\"type\":\"PRODUCT_CARD\",\"productId\":\"" + foreign + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void orderEventsPostSystemMessagesOnceIntoTheRelatedConversation() throws Exception {
        Sale sale = fx.order(buyer, shop, product, "OrderCreated");
        String id = read(start(asBuyer(), "{\"vendorId\":\"" + shop.vendorId() + "\",\"suborderId\":\""
                + sale.suborderId() + "\"}")).get("id").asText();
        UUID shipEvent = UUID.randomUUID();
        fx.shipment(shipEvent, sale, buyer, shop);
        fx.shipment(shipEvent, sale, buyer, shop); // redelivery of the same event
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from message where conversation_id = ?::uuid and type = 'SYSTEM'", Integer.class, id) == 1);
        Thread.sleep(1500);
        assertThat(jdbc.queryForObject("select count(*) from message where conversation_id = ?::uuid and type = 'SYSTEM'",
                Integer.class, id)).isEqualTo(1);
        mvc.perform(get("/api/v1/chat/conversations/" + id + "/messages").with(asBuyer()))
                .andExpect(jsonPath("$.items[0].senderRole").value("SYSTEM"))
                .andExpect(jsonPath("$.items[0].body").value("Shipped with DHL. Tracking number: JD014600006281"))
                .andExpect(jsonPath("$.items[0].metadata.event").value("SHIPPED"));
        mvc.perform(get("/api/v1/chat/conversations/" + id).with(asBuyer()))
                .andExpect(jsonPath("$.context.orderStatus").value("SHIPPED"));
        fx.orderEvent(UUID.randomUUID(), "OrderCancelled", sale.orderId(), buyer, shop, sale.suborderId(), product,
                java.util.Map.of("reason", "CUSTOMER_REQUEST", "wasConfirmed", true));
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
                "select count(*) from message where conversation_id = ?::uuid and type = 'SYSTEM'", Integer.class, id) == 2);
        assertThat(jdbc.queryForObject("select body from message where conversation_id = ?::uuid order by id desc limit 1",
                String.class, id)).isEqualTo("Order cancelled at the buyer's request. Any payment is being refunded.");
        // SYSTEM messages do not trigger chat notifications
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type = 'ChatMessageSent' and"
                + " aggregate_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void reportsAreReviewedByAdminsWhoCanCloseTheConversation() throws Exception {
        String id = startAboutProduct();
        sendText(asSeller(), id, UUID.randomUUID(), "Hello").andExpect(status().isCreated());
        String report = read(mvc.perform(post("/api/v1/chat/conversations/" + id + "/report").with(asBuyer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SCAM\",\"details\":\"asked me to pay outside\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/report").with(asBuyer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_reported"));
        RequestPostProcessor admin = TestJwt.admin(UUID.randomUUID());
        mvc.perform(get("/api/v1/admin/chat/conversations?reported=true").with(admin))
                .andExpect(jsonPath("$.items[?(@.id=='" + id + "')].openReports").value(1));
        mvc.perform(post("/api/v1/admin/chat/reports/" + report + "/resolve").with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"BLOCK_CONVERSATION\",\"note\":\"confirmed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("CONVERSATION_BLOCKED"));
        mvc.perform(post("/api/v1/admin/chat/reports/" + report + "/resolve").with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"DISMISS\"}")).andExpect(status().isConflict());
        sendText(asSeller(), id, UUID.randomUUID(), "still there?").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conversation_closed"));
        mvc.perform(get("/api/v1/chat/conversations/" + id).with(asBuyer()))
                .andExpect(jsonPath("$.status").value("BLOCKED")).andExpect(jsonPath("$.canWrite").value(false));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type = 'AuditRecorded' and"
                + " payload::text like '%chat.report_resolved%' and aggregate_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void imagesAreCheckedByContentAndOnlyVisibleToTheConversation() throws Exception {
        String id = startAboutProduct();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
        JsonNode uploaded = read(mvc.perform(multipart("/api/v1/chat/attachments").file(new MockMultipartFile("file",
                        "photo.png", "image/png", png)).param("conversationId", id).with(asBuyer()))
                .andExpect(status().isCreated()));
        mvc.perform(multipart("/api/v1/chat/attachments").file(new MockMultipartFile("file", "evil.png", "image/png",
                        "<svg onload=alert(1)>".getBytes())).param("conversationId", id).with(asBuyer()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unsupported_image"));
        mvc.perform(multipart("/api/v1/chat/attachments").file(new MockMultipartFile("file", "p.png", "image/png", png))
                .param("conversationId", id).with(TestJwt.customer(UUID.randomUUID()))).andExpect(status().isNotFound());
        String url = uploaded.get("url").asText();
        mvc.perform(get(url).with(asSeller())).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", "image/png"));
        mvc.perform(get(url).with(TestJwt.customer(UUID.randomUUID()))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/messages").with(asBuyer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"IMAGE\",\"attachmentId\":\"" + uploaded.get("id").asText() + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.attachmentUrl").value(url));
        // the shop cannot send the buyer's upload as its own
        mvc.perform(post("/api/v1/chat/conversations/" + id + "/messages").with(asSeller())
                .contentType(MediaType.APPLICATION_JSON).content("{\"clientMessageId\":\"" + UUID.randomUUID()
                        + "\",\"type\":\"IMAGE\",\"attachmentId\":\"" + uploaded.get("id").asText() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void quickRepliesBelongToTheShop() throws Exception {
        String created = read(mvc.perform(post("/api/v1/vendor/chat/quick-replies").with(asSeller())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Shipping\",\"body\":\"We ship within 2 days.\"}"))
                .andExpect(status().isCreated())).get("id").asText();
        mvc.perform(get("/api/v1/vendor/chat/quick-replies").with(asSeller()))
                .andExpect(jsonPath("$[0].title").value("Shipping"));
        Shop other = fx.shop("Rival", "APPROVED");
        mvc.perform(delete("/api/v1/vendor/chat/quick-replies/" + created).with(TestJwt.vendor(other.ownerId())))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/vendor/chat/quick-replies/" + created).with(asSeller()))
                .andExpect(status().isNoContent());
    }

    @Test
    void responseTimeStatisticsNeedThreeAnsweredQuestions() throws Exception {
        String id = startAboutProduct();
        for (int i = 0; i < 3; i++) {
            sendText(asBuyer(), id, UUID.randomUUID(), "question " + i);
            sendText(asSeller(), id, UUID.randomUUID(), "answer " + i);
        }
        // pretend each answer took two hours
        jdbc.update("update message set created_at = created_at + interval '2 hours'"
                + " where conversation_id = ?::uuid and sender_role = 'VENDOR'", id);
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(s -> new com.marketflow.chat.persistence.SupportRepository(jdbc)
                        .recomputeResponseStats());
        Integer median = jdbc.queryForObject("select median_first_reply_seconds from vendor_response_stats where vendor_id = ?",
                Integer.class, shop.vendorId());
        assertThat(median).isBetween(7190, 7210);
        mvc.perform(get("/api/v1/chat/conversations/" + id).with(asBuyer()))
                .andExpect(jsonPath("$.counterpart.typicalReplySeconds").value(median));
    }
}
