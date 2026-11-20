package com.grocery.identity.config;

import com.grocery.identity.security.SigningKeyService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** BCrypt today; the {id} prefix lets us move to a stronger algorithm later without a reset. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Identity validates its own tokens (for /api/auth/me and admin APIs) with its in-memory public
     * key instead of calling its own JWKS endpoint. Other services use the JWKS URL.
     */
    @Bean
    JwtDecoder jwtDecoder(SigningKeyService signingKeyService, IdentityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKeyService.signingPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>("aud",
                        aud -> aud != null && aud.contains(properties.audience()))));
        return decoder;
    }
}
