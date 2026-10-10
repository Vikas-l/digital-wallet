package com.digitalwallet.rewards.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

    public record Jwt(@NotBlank @Size(min = 32) String secret, @NotBlank String issuer) {
    }

    public record Ai(boolean enabled) {
    }

    public record Outbox(@Min(1) int batchSize) {
    }
}
