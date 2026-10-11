package com.marketflow.test;

import com.marketflow.platform.security.JwtRoles;
import com.marketflow.platform.security.Role;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Builds MockMvc JWT authentications with the same claim layout identity-service issues. */
public final class TestJwt {

    private TestJwt() {
    }

    public static RequestPostProcessor as(UUID userId, Role... roles) {
        List<String> names = Arrays.stream(roles).map(Enum::name).toList();
        return jwt().jwt(j -> j.subject(userId.toString())
                        .claim(JwtRoles.CLAIM, names)
                        .claim("email", "user-" + userId.toString().substring(0, 8) + "@example.test"))
                .authorities(names.stream().map(r -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    public static RequestPostProcessor customer(UUID userId) {
        return as(userId, Role.CUSTOMER);
    }

    public static RequestPostProcessor vendor(UUID userId) {
        return as(userId, Role.CUSTOMER, Role.VENDOR);
    }

    public static RequestPostProcessor admin(UUID userId) {
        return as(userId, Role.CUSTOMER, Role.ADMIN);
    }
}
