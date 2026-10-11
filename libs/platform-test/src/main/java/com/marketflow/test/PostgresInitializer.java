package com.marketflow.test;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Gives every Spring test context its own empty PostgreSQL database, migrated by Flyway on startup.
 *
 * <ul>
 *   <li>Default (CI, developer machines with Docker): a shared Testcontainers {@code postgres:16-alpine}.</li>
 *   <li>If {@code MF_TEST_DB_URL} is set (e.g. {@code jdbc:postgresql://localhost:5432/}), that server is used
 *       instead, with {@code MF_TEST_DB_USER}/{@code MF_TEST_DB_PASSWORD} (default postgres/postgres).</li>
 * </ul>
 * Either way the tests exercise a real PostgreSQL: row locks, constraints, ON CONFLICT and SKIP LOCKED behave
 * exactly as in production, which an in-memory database would not guarantee.
 */
public class PostgresInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static PostgreSQLContainer<?> container;

    @Override
    public void initialize(ConfigurableApplicationContext ctx) {
        String base = System.getenv("MF_TEST_DB_URL");
        String user;
        String password;
        if (base != null && !base.isBlank()) {
            user = envOr("MF_TEST_DB_USER", "postgres");
            password = envOr("MF_TEST_DB_PASSWORD", "postgres");
        } else {
            PostgreSQLContainer<?> pg = container();
            base = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/";
            user = pg.getUsername();
            password = pg.getPassword();
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        String dbName = "mf_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (Connection c = DriverManager.getConnection(base + "postgres", user, password);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + dbName);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test database", e);
        }
        TestPropertyValues.of(
                "spring.datasource.url=" + base + dbName,
                "spring.datasource.username=" + user,
                "spring.datasource.password=" + password,
                "spring.datasource.hikari.maximum-pool-size=20"
        ).applyTo(ctx.getEnvironment());
    }

    private static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine").withCommand("postgres", "-c",
                    "max_connections=300");
            container.start();
        }
        return container;
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
