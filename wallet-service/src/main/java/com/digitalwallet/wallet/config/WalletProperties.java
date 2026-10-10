package com.digitalwallet.wallet.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "digitalwallet")
public record WalletProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Admin admin,
        @NotBlank String redisAddress,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull Qr qr,
        @Valid @NotNull PasswordReset passwordReset) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (256 bits) for HS256") String secret,
            @NotBlank String issuer,
            @NotNull Duration ttl) {
    }

    public record Admin(@NotBlank String email, @NotBlank String password) {
    }

    public record Limits(@Min(1) int transfersPerMinute) {
    }

    public record Outbox(@Min(1) int batchSize) {
    }

    public record PasswordReset(@NotNull Duration codeTtl, @Min(1) int maxAttempts, @NotBlank String fromAddress) {
    }

    /** HMAC key for signing QR codes (kept separate from the JWT key) and the longest allowed validity. */
    public record Qr(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (256 bits)") String secret,
            @NotNull Duration maxValidity) {
    }
}
