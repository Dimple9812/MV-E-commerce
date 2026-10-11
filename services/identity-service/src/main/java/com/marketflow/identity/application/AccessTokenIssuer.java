package com.marketflow.identity.application;

import com.marketflow.identity.config.IdentityProperties;
import com.marketflow.identity.domain.User;
import com.marketflow.platform.security.JwtRoles;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Issues short-lived RS256 access tokens. Roles always come from the database, never from the request. */
@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final IdentityProperties props;

    public AccessTokenIssuer(JwtEncoder encoder, IdentityProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .audience(List.of(props.audience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim(JwtRoles.CLAIM, user.getRoles().stream().map(Enum::name).sorted().toList())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, props.accessTokenTtl().toSeconds());
    }
}
