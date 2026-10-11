package com.marketflow.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.platform.audit.AuditRecorder;
import com.marketflow.platform.events.EventCodec;
import com.marketflow.platform.inbox.InboxGuard;
import com.marketflow.platform.kafka.EventListenerSupport;
import com.marketflow.platform.kafka.KafkaErrorHandling;
import com.marketflow.platform.outbox.OutboxProperties;
import com.marketflow.platform.outbox.OutboxPublisher;
import com.marketflow.platform.outbox.OutboxWriter;
import com.marketflow.platform.security.JwtRoles;
import com.marketflow.platform.security.ProblemAuthHandlers;
import com.marketflow.platform.security.SecurityRules;
import com.marketflow.platform.web.CorrelationIdFilter;
import com.marketflow.platform.web.GlobalExceptionHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.config.TopicBuilder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires platform infrastructure into every servlet service that depends on platform-core.
 */
@AutoConfiguration(after = {JacksonAutoConfiguration.class, DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class, KafkaAutoConfiguration.class})
@EnableScheduling
@EnableMethodSecurity
@EnableConfigurationProperties(OutboxProperties.class)
@Import(GlobalExceptionHandler.class)
public class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EventCodec eventCodec(ObjectMapper objectMapper, @Value("${spring.application.name}") String producer) {
        return new EventCodec(objectMapper, producer);
    }

    @Bean
    EventListenerSupport eventListenerSupport(EventCodec codec, InboxGuard inbox, TransactionTemplate tx) {
        return new EventListenerSupport(codec, inbox, tx);
    }

    @Bean
    OutboxWriter outboxWriter(JdbcTemplate jdbc, EventCodec codec) {
        return new OutboxWriter(jdbc, codec);
    }

    @Bean
    InboxGuard inboxGuard(JdbcTemplate jdbc) {
        return new InboxGuard(jdbc);
    }

    @Bean
    AuditRecorder auditRecorder(OutboxWriter outbox, @Value("${spring.application.name}") String service) {
        return new AuditRecorder(outbox, service);
    }

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                                    OutboxProperties props, MeterRegistry meters) {
        return new OutboxPublisher(jdbc, kafka, tx, props, meters);
    }

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    @ConditionalOnBean(KafkaTemplate.class)
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, MeterRegistry meters,
                                         @Value("${spring.application.name}") String consumer,
                                         @Value("${marketflow.kafka.max-retries:4}") int maxRetries,
                                         @Value("${marketflow.kafka.consumes:}") String[] consumes) {
        // Register the DLT counters at 0: a counter that first appears with value 1 is invisible to
        // increase()/rate(), so the first dead letter after a restart would never reach the dashboard or alert.
        KafkaErrorHandling.registerDltCounters(meters, consumes, consumer);
        return KafkaErrorHandling.errorHandler(kafka, meters, consumer, maxRetries);
    }

    /**
     * Declares every topic this service touches: the topics it produces to, the topics it consumes, and the
     * dead-letter topics of the consumed ones ({@code marketflow.kafka.produces / consumes}; topics consumed through
     * Spring retry topics, which bring their own DLT, go in {@code consumes-with-retry-topics}).
     *
     * <p>Consumed topics are declared too, not only produced ones: on a fresh cluster a consumer can start before
     * the producing service has created the topic, subscribe while only part of it exists and then miss partitions
     * until its next metadata refresh (minutes). Declaring is idempotent, so whichever service starts first creates
     * the topic with the full partition count. Broker-side auto-creation stays disabled.
     */
    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    KafkaAdmin.NewTopics platformTopics(org.springframework.core.env.Environment env) {
        return new KafkaAdmin.NewTopics(declaredTopics(
                env.getProperty("marketflow.kafka.produces", String[].class, new String[0]),
                env.getProperty("marketflow.kafka.consumes", String[].class, new String[0]),
                env.getProperty("marketflow.kafka.consumes-with-retry-topics", String[].class, new String[0]),
                env.getProperty("marketflow.kafka.partitions", Integer.class, 3),
                env.getProperty("marketflow.kafka.replication-factor", Short.class, (short) 1)).toArray(NewTopic[]::new));
    }

    static java.util.List<NewTopic> declaredTopics(String[] produces, String[] consumes, String[] consumesWithRetryTopics,
                                                   int partitions, short replicas) {
        java.util.Map<String, NewTopic> topics = new java.util.LinkedHashMap<>();
        java.util.function.Consumer<String> source = t ->
                topics.putIfAbsent(t, TopicBuilder.name(t).partitions(partitions).replicas(replicas).build());
        java.util.stream.Stream.of(produces, consumes, consumesWithRetryTopics).flatMap(java.util.Arrays::stream)
                .map(String::trim).filter(t -> !t.isEmpty()).forEach(source);
        java.util.Arrays.stream(consumes).map(String::trim).filter(t -> !t.isEmpty()).map(KafkaErrorHandling::dltTopic)
                .forEach(dlt -> topics.putIfAbsent(dlt, TopicBuilder.name(dlt).partitions(1).replicas(replicas)
                        .config("retention.ms", String.valueOf(java.time.Duration.ofDays(14).toMillis())).build()));
        return java.util.List.copyOf(topics.values());
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> reg = new FilterRegistrationBean<>(new CorrelationIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * Stateless bearer-token API. CSRF protection is disabled because no cookies or sessions authenticate requests
     * to these services (the refresh-token cookie is only read by identity-service, which enables CSRF-safe
     * SameSite=Strict cookies plus an explicit header check).
     */
    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    SecurityFilterChain platformSecurityFilterChain(HttpSecurity http, ObjectProvider<SecurityRules> rules,
                                                    ObjectMapper mapper) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .headers(h -> h.contentTypeOptions(c -> { }).frameOptions(f -> f.deny()))
                .exceptionHandling(e -> e.authenticationEntryPoint(ProblemAuthHandlers.entryPoint(mapper))
                        .accessDeniedHandler(ProblemAuthHandlers.accessDenied(mapper)))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new JwtRoles()))
                        .authenticationEntryPoint(ProblemAuthHandlers.entryPoint(mapper)))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll();
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    rules.orderedStream().forEach(r -> r.apply(auth));
                    auth.anyRequest().authenticated();
                });
        return http.build();
    }
}
