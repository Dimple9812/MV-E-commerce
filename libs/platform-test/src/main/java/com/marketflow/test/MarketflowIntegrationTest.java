package com.marketflow.test;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Full application context + real PostgreSQL + embedded KRaft Kafka + MockMvc.
 * Kafka listeners, the outbox relay and Flyway all run exactly as in production.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(kraft = true, partitions = 1)
@ContextConfiguration(initializers = PostgresInitializer.class)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "logging.structured.format.console=",
        "marketflow.outbox.poll-interval=100ms",
        "marketflow.kafka.max-retries=2",
        "management.tracing.enabled=false",
        "spring.data.redis.timeout=200ms"
})
public @interface MarketflowIntegrationTest {
}
