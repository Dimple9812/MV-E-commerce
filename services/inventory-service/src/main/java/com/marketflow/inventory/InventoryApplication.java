package com.marketflow.inventory;

import com.marketflow.platform.security.SecurityRules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;

@SpringBootApplication
public class InventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }

    @Bean
    SecurityRules inventoryRules() {
        return auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/inventory/availability").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/vendor/**").hasRole("VENDOR");
    }
}
