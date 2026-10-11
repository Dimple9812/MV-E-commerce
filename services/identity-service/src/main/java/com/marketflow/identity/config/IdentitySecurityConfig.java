package com.marketflow.identity.config;

import com.marketflow.platform.security.SecurityRules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class IdentitySecurityConfig {

    @Bean
    SecurityRules identityRules() {
        return auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                        "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/password-reset/request",
                        "/api/v1/auth/password-reset/confirm").permitAll()
                .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
    }

    @Bean
    PasswordEncoder passwordEncoder(IdentityProperties props) {
        return new BCryptPasswordEncoder(props.bcryptStrength());
    }
}
