package com.digitalwallet.rewards.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * rewards-service only verifies tokens issued by wallet-service. Tokens are RS256, so it
 * needs only wallet-service's PUBLIC key (from the JWKS endpoint, or a PEM): it can check
 * tokens but cannot create them. It shares the Redis denylist, so a token revoked by
 * logout is rejected here as well.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    static final String REVOKED_KEY_PREFIX = "digitalwallet:revoked-jwt:";
    static final String VALID_AFTER_KEY_PREFIX = "digitalwallet:tokens-valid-after:";

    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * The web UI is served by wallet-service (a different origin: port 8081), so the
     * browser only lets it call this service if CORS allows that origin explicitly.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(RewardsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    JwtDecoder jwtDecoder(RewardsProperties properties, RedissonClient redisson) {
        RewardsProperties.Jwt jwt = properties.jwt();
        NimbusJwtDecoder decoder;
        if (jwt.publicKey() != null && !jwt.publicKey().isBlank()) {
            decoder = NimbusJwtDecoder.withPublicKey(parsePublicKey(jwt.publicKey()))
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } else {
            // keys are fetched lazily and cached; an unknown "kid" (key rotation) triggers a refetch
            decoder = NimbusJwtDecoder.withJwkSetUri(jwt.jwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(jwt.issuer()),
                notRevoked(redisson, jwt.revocationCheckFailOpen())));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> notRevoked(RedissonClient redisson, boolean failOpen) {
        OAuth2Error revoked = new OAuth2Error("invalid_token", "The token has been revoked", null);
        OAuth2Error unavailable = new OAuth2Error("invalid_token",
                "The token could not be checked right now, please retry", null);
        return token -> {
            try {
                // logged out: this token id is on the denylist
                if (token.getId() != null
                        && redisson.getBucket(REVOKED_KEY_PREFIX + token.getId(), StringCodec.INSTANCE).isExists()) {
                    return OAuth2TokenValidatorResult.failure(revoked);
                }
                // password reset: every token issued before it is invalid
                String validAfter = redisson.<String>getBucket(VALID_AFTER_KEY_PREFIX + token.getSubject(),
                        StringCodec.INSTANCE).get();
                if (validAfter != null && token.getIssuedAt() != null
                        && token.getIssuedAt().getEpochSecond() < Long.parseLong(validAfter)) {
                    return OAuth2TokenValidatorResult.failure(revoked);
                }
                return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException e) {
                // Redis down: a deliberate, configurable choice between availability and strictness
                log.warn("Revocation check unavailable (fail-{}): {}", failOpen ? "open" : "closed", e.getMessage());
                return failOpen ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(unavailable);
            }
        };
    }

    /** X.509 SubjectPublicKeyInfo PEM (-----BEGIN PUBLIC KEY-----); "\n" escapes are accepted. */
    static RSAPublicKey parsePublicKey(String pem) {
        String base64 = pem.replace("\\n", "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("JWT_PUBLIC_KEY must be an RSA public key in PEM format", e);
        }
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
