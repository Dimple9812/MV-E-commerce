package com.marketflow.platform.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Maps the {@code roles} claim to {@code ROLE_*} authorities. */
public final class JwtRoles implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String CLAIM = "roles";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Object raw = jwt.getClaims().get(CLAIM);
        Collection<?> values = raw instanceof Collection<?> c ? c : List.of();
        List<GrantedAuthority> authorities = values.stream()
                .map(Object::toString)
                .map(Role::parse)
                .filter(Objects::nonNull)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r.name()))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
