package com.marketflow.platform.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Adds {@code marketflow-defaults.yml} as the LOWEST-precedence property source so every service shares
 * consistent conventions (actuator exposure, Kafka serializers and producer idempotence, JPA validate mode,
 * graceful shutdown, tracing) while any service, environment variable or profile can override them.
 */
public class PlatformDefaultsPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("marketflow-defaults", new ClassPathResource("marketflow-defaults.yml"));
            sources.forEach(s -> environment.getPropertySources().addLast(s));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
