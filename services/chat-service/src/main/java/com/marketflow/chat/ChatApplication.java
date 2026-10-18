package com.marketflow.chat;

import com.marketflow.platform.security.SecurityRules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }

    @Bean
    SecurityRules chatRules() {
        return auth -> auth
                // The WebSocket handshake cannot carry a bearer header in browsers. It is opened anonymously and
                // every STOMP session is authenticated on CONNECT by JwtChannelInterceptor instead.
                .requestMatchers("/ws/chat", "/ws/chat/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/vendor/**").hasRole("VENDOR");
    }
}
