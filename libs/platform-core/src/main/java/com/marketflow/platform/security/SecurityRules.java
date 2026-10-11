package com.marketflow.platform.security;

import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Each service declares its endpoint rules by providing a bean of this type. The platform appends
 * {@code anyRequest().authenticated()} afterwards, so anything not explicitly opened requires a valid JWT
 * (deny-by-default). Fine-grained checks (ownership) still happen in the application layer.
 */
@FunctionalInterface
public interface SecurityRules {
    void apply(AuthorizeHttpRequestsConfigurer<?>.AuthorizationManagerRequestMatcherRegistry registry);
}
