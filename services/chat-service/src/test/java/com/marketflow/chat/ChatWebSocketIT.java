package com.marketflow.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.chat.ChatFixtures.Shop;
import com.marketflow.chat.api.ChatDtos.StartConversationRequest;
import com.marketflow.chat.application.ConversationService;
import com.marketflow.chat.domain.ChatActor;
import com.marketflow.platform.security.Role;
import com.marketflow.test.PostgresInitializer;
import com.marketflow.test.RedisInitializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Two chat-service instances (this test's context and a second application on another port) share PostgreSQL,
 * Redis and Kafka. The buyer is connected to instance A, the seller to instance B: everything the buyer sends
 * must reach the seller through the Redis fan-out, and back.
 */
@SpringBootTest(classes = {ChatApplication.class, TestTokens.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(kraft = true, partitions = 1)
@ContextConfiguration(initializers = {PostgresInitializer.class, RedisInitializer.class})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "logging.structured.format.console=",
        "marketflow.outbox.poll-interval=100ms",
        "management.tracing.enabled=false",
        "marketflow.chat.stats-initial-delay=1h",
        "marketflow.chat.fanout-channel=chat:fanout:ws-it"
})
class ChatWebSocketIT {

    static ConfigurableApplicationContext instanceB;
    static int portB;

    @LocalServerPort
    int portA;
    @Autowired
    Environment env;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ConversationService conversations;
    @Autowired
    SimpUserRegistry usersA;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    ChatFixtures fx;
    Shop shop;
    UUID buyer;
    UUID conversationId;

    @BeforeEach
    void setUp() {
        if (instanceB == null) {
            // command-line arguments: they take precedence over application.yml (defaults would not)
            Map<String, String> props = Map.of(
                    "server.port", "0",
                    "spring.datasource.url", env.getRequiredProperty("spring.datasource.url"),
                    "spring.datasource.username", env.getRequiredProperty("spring.datasource.username"),
                    "spring.datasource.password", env.getRequiredProperty("spring.datasource.password"),
                    "spring.data.redis.host", env.getRequiredProperty("spring.data.redis.host"),
                    "spring.data.redis.port", env.getRequiredProperty("spring.data.redis.port"),
                    "spring.kafka.bootstrap-servers", brokers,
                    "logging.structured.format.console", "",
                    "marketflow.chat.stats-initial-delay", "1h",
                    "marketflow.chat.fanout-channel", "chat:fanout:ws-it");
            instanceB = new SpringApplicationBuilder(ChatApplication.class, TestTokens.class)
                    .run(props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                            .toArray(String[]::new));
            portB = Integer.parseInt(instanceB.getEnvironment().getRequiredProperty("local.server.port"));
        }
        fx = new ChatFixtures(brokers, jdbc);
        shop = fx.shop("Aurora " + UUID.randomUUID().toString().substring(0, 5), "APPROVED");
        UUID product = fx.product(shop, "Turntable");
        buyer = UUID.randomUUID();
        conversationId = conversations.start(new ChatActor(buyer, Set.of(Role.CUSTOMER), "Ada Moreno"),
                new StartConversationRequest(shop.vendorId(), product, null)).conversation().id();
    }

    @AfterAll
    static void stopInstanceB() {
        if (instanceB != null) {
            instanceB.close();
        }
    }

    // ---------- STOMP client helpers ----------

    record Client(StompSession session, Map<String, BlockingQueue<JsonNode>> queues) {
        JsonNode next(String queue) throws InterruptedException {
            JsonNode n = queues.get(queue).poll(10, TimeUnit.SECONDS);
            assertThat(n).as("frame on " + queue).isNotNull();
            return n;
        }

        JsonNode nextMatching(String queue, java.util.function.Predicate<JsonNode> p) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode n = queues.get(queue).poll(500, TimeUnit.MILLISECONDS);
                if (n != null && p.test(n)) {
                    return n;
                }
            }
            throw new AssertionError("no matching frame on " + queue);
        }
    }

    static WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(new ObjectMapper().findAndRegisterModules());
        client.setMessageConverter(converter);
        return client;
    }

    Client connect(int port, String token, SimpUserRegistry registry, UUID user) throws Exception {
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + token);
        StompSession session = stompClient().connectAsync("ws://localhost:" + port + "/ws/chat",
                new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
        Map<String, BlockingQueue<JsonNode>> queues = new java.util.HashMap<>();
        for (String q : new String[] {"messages", "typing", "receipts", "errors", "presence"}) {
            BlockingQueue<JsonNode> queue = new LinkedBlockingQueue<>();
            queues.put(q, queue);
            session.subscribe("/user/queue/" + q, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    queue.add((JsonNode) payload);
                }
            });
        }
        // subscriptions are asynchronous: wait until the server has registered all five
        await().atMost(Duration.ofSeconds(10)).until(() -> registry.getUser(user.toString()) != null
                && registry.getUser(user.toString()).getSessions().stream()
                .anyMatch(s -> s.getSubscriptions().size() == 5));
        return new Client(session, queues);
    }

    SimpUserRegistry usersB() {
        return instanceB.getBean(SimpUserRegistry.class);
    }

    Map<String, Object> textFrame(UUID clientId, String body) {
        return Map.of("conversationId", conversationId.toString(), "clientMessageId", clientId.toString(),
                "type", "TEXT", "body", body);
    }

    // ---------- tests ----------

    @Test
    void messagesTypingAndReceiptsCrossInstances() throws Exception {
        Client buyerOnA = connect(portA, TestTokens.token(buyer, "Ada Moreno", Duration.ofMinutes(15), "CUSTOMER"),
                usersA, buyer);
        Client sellerOnB = connect(portB, TestTokens.token(shop.ownerId(), "Aurora Owner", Duration.ofMinutes(15),
                "CUSTOMER", "VENDOR"), usersB(), shop.ownerId());
        assertThat(usersA.getUser(shop.ownerId().toString())).as("seller is not on instance A").isNull();
        assertThat(usersB().getUser(buyer.toString())).as("buyer is not on instance B").isNull();

        UUID clientId = UUID.randomUUID();
        buyerOnA.session().send("/app/chat.send", textFrame(clientId, "Is the turntable still available?"));
        JsonNode atSeller = sellerOnB.next("messages");
        assertThat(atSeller.path("kind").asText()).isEqualTo("MESSAGE_CREATED");
        assertThat(atSeller.path("message").path("body").asText()).isEqualTo("Is the turntable still available?");
        assertThat(atSeller.path("message").path("senderRole").asText()).isEqualTo("BUYER");
        assertThat(atSeller.path("message").path("senderId").asText()).isEqualTo(buyer.toString());
        // the sender's own delivery is the acknowledgement, matched by clientMessageId
        JsonNode ack = buyerOnA.next("messages");
        assertThat(ack.path("message").path("clientMessageId").asText()).isEqualTo(clientId.toString());
        long messageId = ack.path("message").path("id").asLong();

        sellerOnB.session().send("/app/chat.typing", Map.of("conversationId", conversationId.toString(), "typing", true));
        JsonNode typing = buyerOnA.next("typing");
        assertThat(typing.path("side").asText()).isEqualTo("VENDOR");
        assertThat(typing.path("typing").asBoolean()).isTrue();

        sellerOnB.session().send("/app/chat.read", Map.of("conversationId", conversationId.toString(),
                "lastReadMessageId", messageId));
        JsonNode receipt = buyerOnA.nextMatching("receipts", r -> "VENDOR".equals(r.path("side").asText()));
        assertThat(receipt.path("lastReadMessageId").asLong()).isEqualTo(messageId);

        sellerOnB.session().send("/app/chat.send", textFrame(UUID.randomUUID(), "Yes, one left!"));
        JsonNode reply = buyerOnA.nextMatching("messages", m -> "VENDOR".equals(m.path("message").path("senderRole").asText()));
        assertThat(reply.path("message").path("body").asText()).isEqualTo("Yes, one left!");

        // a retried frame does not create a second message
        buyerOnA.session().send("/app/chat.send", textFrame(clientId, "Is the turntable still available?"));
        buyerOnA.nextMatching("messages", m -> clientId.toString().equals(m.path("message").path("clientMessageId").asText()));
        assertThat(jdbc.queryForObject("select count(*) from message where conversation_id = ?", Integer.class,
                conversationId)).isEqualTo(2);
        buyerOnA.session().disconnect();
        sellerOnB.session().disconnect();
    }

    @Test
    void refusedFramesAnswerOnlyTheSendingSession() throws Exception {
        Client buyerOnA = connect(portA, TestTokens.token(buyer, "Ada Moreno", Duration.ofMinutes(15), "CUSTOMER"),
                usersA, buyer);
        Client sellerOnB = connect(portB, TestTokens.token(shop.ownerId(), "Aurora Owner", Duration.ofMinutes(15),
                "CUSTOMER", "VENDOR"), usersB(), shop.ownerId());
        UUID clientId = UUID.randomUUID();
        buyerOnA.session().send("/app/chat.send", textFrame(clientId, "call me 415 555 0199"));
        JsonNode error = buyerOnA.next("errors");
        assertThat(error.path("code").asText()).isEqualTo("contact_info_not_allowed");
        assertThat(error.path("clientMessageId").asText()).isEqualTo(clientId.toString());
        assertThat(sellerOnB.queues().get("messages").poll(1, TimeUnit.SECONDS)).isNull();

        // a stranger's frame for this conversation is answered with not_found, nothing is delivered
        UUID stranger = UUID.randomUUID();
        Client strangerOnB = connect(portB, TestTokens.token(stranger, "Eve", Duration.ofMinutes(15), "CUSTOMER"),
                usersB(), stranger);
        strangerOnB.session().send("/app/chat.send", textFrame(UUID.randomUUID(), "hello"));
        assertThat(strangerOnB.next("errors").path("code").asText()).isEqualTo("not_found");
        assertThat(buyerOnA.queues().get("messages").poll(1, TimeUnit.SECONDS)).isNull();
        buyerOnA.session().disconnect();
        sellerOnB.session().disconnect();
        strangerOnB.session().disconnect();
    }

    @Test
    void connectWithoutAValidTokenIsRefused() {
        for (String token : new String[] {"not-a-jwt", TestTokens.token(buyer, "Ada", Duration.ofMinutes(-5), "CUSTOMER")}) {
            StompHeaders connect = new StompHeaders();
            connect.add("Authorization", "Bearer " + token);
            assertThatThrownBy(() -> stompClient().connectAsync("ws://localhost:" + portA + "/ws/chat",
                    new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS))
                    .isInstanceOfAny(java.util.concurrent.ExecutionException.class,
                            java.util.concurrent.TimeoutException.class);
        }
        assertThatThrownBy(() -> stompClient().connectAsync("ws://localhost:" + portA + "/ws/chat",
                new WebSocketHttpHeaders(), new StompHeaders(), new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS))
                .isInstanceOfAny(java.util.concurrent.ExecutionException.class, java.util.concurrent.TimeoutException.class);
    }

    @Test
    void subscribingToSomeoneElsesQueueIsRefused() throws Exception {
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.add(String.valueOf(headers.getFirst("message")));
            }
        };
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + TestTokens.token(buyer, "Ada", Duration.ofMinutes(15), "CUSTOMER"));
        StompSession session = stompClient().connectAsync("ws://localhost:" + portA + "/ws/chat",
                new WebSocketHttpHeaders(), connect, handler).get(10, TimeUnit.SECONDS);
        BlockingQueue<Object> stolen = new LinkedBlockingQueue<>();
        // the simple broker's raw per-session destination of another user
        session.subscribe("/queue/messages-user" + shop.ownerId(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                stolen.add(payload);
            }
        });
        // the server answers with an ERROR frame (message "forbidden_destination") and closes the socket; the
        // client may observe the close before it hands the ERROR frame to the handler, so assert on the close
        await().atMost(Duration.ofSeconds(10)).until(() -> !session.isConnected());
        assertThat(usersA.findSubscriptions(sub -> sub.getDestination().startsWith("/queue/messages-user"))).isEmpty();
        // sending to the broker directly (bypassing /app) is refused the same way
        StompSession second = stompClient().connectAsync("ws://localhost:" + portA + "/ws/chat",
                new WebSocketHttpHeaders(), connect, handler).get(10, TimeUnit.SECONDS);
        second.send("/queue/messages-user" + shop.ownerId(), Map.of("fake", true));
        await().atMost(Duration.ofSeconds(10)).until(() -> !second.isConnected());
        assertThat(errors).allMatch("forbidden_destination"::equals);
        assertThat(stolen).isEmpty();
    }

    @Test
    void presenceIsSharedAcrossInstances() throws Exception {
        ChatActor buyerActor = new ChatActor(buyer, Set.of(Role.CUSTOMER), "Ada Moreno");
        assertThat(conversations.get(buyerActor, conversationId).counterpart().online()).isFalse();
        Client sellerOnB = connect(portB, TestTokens.token(shop.ownerId(), "Aurora Owner", Duration.ofMinutes(15),
                "CUSTOMER", "VENDOR"), usersB(), shop.ownerId());
        await().atMost(Duration.ofSeconds(10)).until(() ->
                conversations.get(buyerActor, conversationId).counterpart().online());
        sellerOnB.session().disconnect();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            var c = conversations.get(buyerActor, conversationId).counterpart();
            return !c.online() && c.lastSeenAt() != null;
        });
    }
}
