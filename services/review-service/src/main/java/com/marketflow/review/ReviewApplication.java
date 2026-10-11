package com.marketflow.review;

import com.marketflow.platform.security.SecurityRules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;

@SpringBootApplication
public class ReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewApplication.class, args);
    }

    @Bean
    SecurityRules reviewRules() {
        return auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/reviews", "/api/v1/products/*/rating").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
    }
}
