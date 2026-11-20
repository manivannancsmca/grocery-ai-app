package com.grocery.identity.security;

import com.grocery.identity.config.IdentityProperties;
import com.grocery.identity.entity.BusinessMembership;
import com.grocery.identity.entity.UserAccount;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Issues short-lived RS256 access tokens. Claims carry only what other services need to authorise
 * a request without calling identity: roles and the caller's ACTIVE business IDs.
 */
@Service
public class AccessTokenService {

    private final JwtEncoder encoder;
    private final String keyId;
    private final IdentityProperties properties;
    private final Clock clock;

    public AccessTokenService(SigningKeyService signingKeyService, IdentityProperties properties, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKeyService.signingKey())));
        this.keyId = signingKeyService.signingKey().getKeyID();
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UserAccount user, List<BusinessMembership> memberships) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("iat_ms", now.toEpochMilli())     // millisecond precision for "revoked-before" checks
                .claim("email", user.getEmail())
                .claim("name", user.getFullName())
                .claim("roles", List.copyOf(new TreeSet<>(user.getRoles())));
        for (BusinessMembership membership : memberships) {
            if (membership.isActive()) {
                String claim = membership.getBusinessType() == BusinessMembership.BusinessType.VENDOR
                        ? "vendor_id" : "restaurant_id";
                claims.claim(claim, membership.getBusinessId().toString());
            }
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedAccessToken(token, properties.accessTokenTtl().toSeconds());
    }

    public record IssuedAccessToken(String value, long expiresInSeconds) {
    }
}
