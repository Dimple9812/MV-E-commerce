package com.marketflow.test;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;

/**
 * Real Redis for tests: Testcontainers {@code redis:7-alpine}, or an existing server via MF_TEST_REDIS_HOST/PORT.
 */
public class RedisInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static GenericContainer<?> container;

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        String host = System.getenv("MF_TEST_REDIS_HOST");
        String port = System.getenv("MF_TEST_REDIS_PORT");
        if (host == null || host.isBlank()) {
            GenericContainer<?> redis = container();
            host = redis.getHost();
            port = String.valueOf(redis.getMappedPort(6379));
        }
        TestPropertyValues.of("spring.data.redis.host=" + host,
                "spring.data.redis.port=" + (port == null || port.isBlank() ? "6379" : port)).applyTo(ctx);
    }

    private static synchronized GenericContainer<?> container() {
        if (container == null) {
            container = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
            container.start();
        }
        return container;
    }
}
