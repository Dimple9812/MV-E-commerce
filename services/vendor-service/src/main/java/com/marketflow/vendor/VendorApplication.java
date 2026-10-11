package com.marketflow.vendor;

import com.marketflow.platform.security.SecurityRules;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;

@SpringBootApplication
public class VendorApplication {

    public static void main(String[] args) {
        SpringApplication.run(VendorApplication.class, args);
    }

    @Bean
    SecurityRules vendorRules() {
        return auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/vendors/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/vendors/{vendorId}").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/vendor/**").hasRole("VENDOR");
    }
}
