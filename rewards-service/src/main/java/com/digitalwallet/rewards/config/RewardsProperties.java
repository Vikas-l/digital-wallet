package com.digitalwallet.rewards.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "digitalwallet")
public record RewardsProperties(
        @Valid @NotNull Jwt jwt,
        @NotBlank String redisAddress,
        @NotNull ZoneId businessZone,
        @NotNull Duration ruleCacheTtl,
        @Valid @NotNull Ai ai,
        @Valid @NotNull Outbox outbox,
        @NotEmpty List<String> corsAllowedOrigins) {

    /**
     * Only the public key is needed to verify wallet-service's RS256 tokens.
     *
     * @param jwkSetUri               wallet-service's JWKS endpoint (keys are cached; a new key id triggers a refetch)
     * @param publicKey               alternatively the RSA public key as PEM (wins over jwkSetUri when set)
     * @param revocationCheckFailOpen if Redis is down: accept otherwise valid tokens (true) or reject them (false)
     */
    public record Jwt(String jwkSetUri, String publicKey, @NotBlank String issuer, boolean revocationCheckFailOpen) {

        @AssertTrue(message = "set digitalwallet.jwt.jwk-set-uri or digitalwallet.jwt.public-key")
        public boolean isKeySourceConfigured() {
            return (jwkSetUri != null && !jwkSetUri.isBlank()) || (publicKey != null && !publicKey.isBlank());
        }
    }

    public record Ai(boolean enabled) {
    }

    public record Outbox(
            @Min(1) int batchSize,
            @Min(1) int maxAttempts,
            @NotNull Duration initialBackoff,
            @NotNull Duration maxBackoff) {
    }
}
