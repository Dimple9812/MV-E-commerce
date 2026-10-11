package com.marketflow.notification;

import com.marketflow.platform.security.SecurityRules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;

@SpringBootApplication
@EnableKafkaRetryTopic
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }

    @Bean
    SecurityRules notificationRules() {
        return auth -> auth.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
    }
}
