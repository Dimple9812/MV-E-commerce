package com.marketflow.payment;

import com.marketflow.platform.security.SecurityRules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;

@SpringBootApplication
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    @Bean
    SecurityRules paymentRules() {
        return auth -> auth
                // authenticated by HMAC signature, not by JWT
                .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhooks/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
    }
}
