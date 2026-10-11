package com.marketflow.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.identity.application.MailGateway;
import com.marketflow.test.KafkaTestClient;
import com.marketflow.test.MarketflowIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MarketflowIntegrationTest
@TestPropertySource(properties = {"marketflow.identity.bcrypt-strength=4",
        "marketflow.identity.login-attempts-per-ip-per-minute=1000"})
class IdentityApiIT {

    @TestConfiguration
    static class CapturingMail {
        static final List<String[]> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        MailGateway capturingMailGateway() {
            return (to, subject, body) -> SENT.add(new String[]{to, subject, body});
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${spring.embedded.kafka.brokers}")
    String brokers;

    private String email() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    private MvcResult register(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "GoodPassword1",
                                "fullName", "Test User", "roles", List.of("ADMIN")))))
                .andExpect(status().isCreated()).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void registrationGrantsOnlyCustomerEvenIfClientAsksForAdmin() throws Exception {
        JsonNode b = body(register(email()));
        assertThat(b.at("/user/roles").toString()).isEqualTo("[\"CUSTOMER\"]");
        String token = b.get("accessToken").asText();

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
        // customer must not reach admin endpoints
        mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        String e = email();
        register(e);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", e.toUpperCase(), "password",
                                "GoodPassword1", "fullName", "Dup"))))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidInputReturnsProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"x\",\"fullName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void accountLocksAfterFiveFailedLogins() throws Exception {
        String e = email();
        register(e);
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", e, "password", "WrongPassword9"))))
                    .andExpect(status().isUnauthorized());
        }
        // even the correct password is refused while locked
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", e, "password", "GoodPassword1"))))
                .andExpect(status().isLocked());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeFamily() throws Exception {
        MvcResult reg = register(email());
        Cookie first = reg.getResponse().getCookie("mf_refresh");
        assertThat(first).isNotNull();
        assertThat(first.isHttpOnly()).isTrue();

        // CSRF guard: cookie alone is not enough
        mvc.perform(post("/api/v1/auth/refresh").cookie(first)).andExpect(status().isForbidden());

        MvcResult r1 = mvc.perform(post("/api/v1/auth/refresh").cookie(first).header("X-Requested-With", "test"))
                .andExpect(status().isOk()).andReturn();
        Cookie second = r1.getResponse().getCookie("mf_refresh");
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // replaying the rotated token = theft signal -> 401 and family revoked
        mvc.perform(post("/api/v1/auth/refresh").cookie(first).header("X-Requested-With", "test"))
                .andExpect(status().isUnauthorized());
        // the legitimate successor is now revoked too
        mvc.perform(post("/api/v1/auth/refresh").cookie(second).header("X-Requested-With", "test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokensAreStoredOnlyAsHashes() throws Exception {
        MvcResult reg = register(email());
        String raw = reg.getResponse().getCookie("mf_refresh").getValue();
        Integer plain = jdbc.queryForObject("select count(*) from refresh_token where token_hash = ?",
                Integer.class, raw);
        assertThat(plain).isZero();
    }

    @Test
    void passwordResetFlowWorksOnceAndRevokesSessions() throws Exception {
        String e = email();
        MvcResult reg = register(e);
        Cookie session = reg.getResponse().getCookie("mf_refresh");
        CapturingMail.SENT.clear();

        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + e + "\"}")).andExpect(status().isAccepted());
        // unknown e-mail gets the same answer
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.test\"}")).andExpect(status().isAccepted());

        assertThat(CapturingMail.SENT).hasSize(1);
        String bodyText = CapturingMail.SENT.get(0)[2];
        String token = bodyText.substring(bodyText.indexOf("token=") + 6).split("\\s")[0];

        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "newPassword", "BrandNewPass42"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "newPassword", "AnotherPass42"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", e, "password", "BrandNewPass42"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").cookie(session).header("X-Requested-With", "t"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userRegisteredEventIsPublishedThroughTheOutbox() throws Exception {
        String e = email();
        String userId = body(register(e)).at("/user/id").asText();
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            List<JsonNode> events = kafka.readUntil("identity.user.events", Duration.ofSeconds(20),
                    l -> l.stream().anyMatch(n -> userId.equals(n.path("aggregateId").asText())));
            JsonNode ev = events.stream().filter(n -> userId.equals(n.path("aggregateId").asText())).findFirst()
                    .orElseThrow();
            assertThat(ev.get("eventType").asText()).isEqualTo("UserRegistered");
            assertThat(ev.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(ev.at("/payload/email").asText()).isEqualTo(e);
            assertThat(ev.toString()).doesNotContain("password");
        }
    }

    @Test
    void vendorApprovalEventGrantsVendorRoleExactlyOnceEvenWhenRedelivered() throws Exception {
        String userId = body(register(email())).at("/user/id").asText();
        UUID eventId = UUID.randomUUID();
        String event = KafkaTestClient.envelope(eventId, "VendorApproved", "Vendor", UUID.randomUUID().toString(),
                Map.of("vendorId", UUID.randomUUID().toString(), "ownerUserId", userId, "status", "APPROVED"));
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("vendor.events", userId, event);
            kafka.send("vendor.events", userId, event); // duplicate delivery
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from user_role where user_id = ?::uuid and role = 'VENDOR'", Integer.class, userId))
                .isEqualTo(1));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select count(*) from processed_event where event_id = ?", Integer.class, eventId)).isEqualTo(1));
        Integer roleEvents = jdbc.queryForObject("select count(*) from outbox_event where event_type = "
                + "'UserRolesChanged' and aggregate_id = ?", Integer.class, userId);
        assertThat(roleEvents).isEqualTo(1);
    }

    @Test
    void malformedEventGoesToDeadLetterTopic() throws Exception {
        try (KafkaTestClient kafka = new KafkaTestClient(brokers)) {
            kafka.send("vendor.events", "k", "{this is not json");
            List<JsonNode> dlt = kafka.readUntil("vendor.events.dlt", Duration.ofSeconds(20), l -> !l.isEmpty());
            assertThat(dlt).isNotEmpty();
        }
    }

    @Test
    void jwksExposesOnlyPublicKeyMaterial() throws Exception {
        String body = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(body).contains("\"kty\":\"RSA\"").doesNotContain("\"d\":").doesNotContain("\"p\":");
    }
}
