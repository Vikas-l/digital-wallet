package com.digitalwallet.wallet.auth;

import java.time.Duration;
import java.time.Instant;

import com.digitalwallet.wallet.config.WalletProperties;

import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.client.codec.StringCodec;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * JWTs are stateless, so revocation is done with two small Redis records that both
 * services check on every request:
 * <ul>
 *   <li>logout: the token id (jti) goes on a denylist, expiring when the token would;</li>
 *   <li>password reset: a per-user "valid after" time, so every token issued before
 *       the reset (on any device) is rejected; it expires after the token lifetime.</li>
 * </ul>
 */
@Service
public class TokenRevocationService implements OAuth2TokenValidator<Jwt> {

    private static final String REVOKED_PREFIX = "digitalwallet:revoked-jwt:";
    private static final String VALID_AFTER_PREFIX = "digitalwallet:tokens-valid-after:";
    private static final OAuth2Error REVOKED =
            new OAuth2Error("invalid_token", "The token has been revoked", null);

    private static final OAuth2Error UNAVAILABLE =
            new OAuth2Error("invalid_token", "The token could not be checked right now, please retry", null);
    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RedissonClient redisson;
    private final Duration tokenTtl;
    private final boolean failOpen;

    public TokenRevocationService(RedissonClient redisson, WalletProperties properties) {
        this.redisson = redisson;
        this.tokenTtl = properties.jwt().ttl();
        this.failOpen = properties.jwt().revocationCheckFailOpen();
    }

    public void revoke(Jwt jwt) {
        if (jwt.getId() == null || jwt.getExpiresAt() == null) {
            return;
        }
        Duration remaining = Duration.between(Instant.now(), jwt.getExpiresAt());
        if (!remaining.isNegative() && !remaining.isZero()) {
            redisson.getBucket(REVOKED_PREFIX + jwt.getId(), StringCodec.INSTANCE).set("1", remaining);
        }
    }

    /** Logs the user out everywhere: tokens issued before this second stop working. */
    public void revokeAllIssuedBefore(Long userId, Instant instant) {
        redisson.getBucket(VALID_AFTER_PREFIX + userId, StringCodec.INSTANCE)
                .set(String.valueOf(instant.getEpochSecond()), tokenTtl);
    }

    /**
     * The signature and expiry are already checked by then; this only asks Redis whether the
     * token was revoked. If Redis is unreachable the behaviour is a deliberate, configurable
     * choice: fail open (accept the token; it still expires within the JWT lifetime) keeps the
     * app usable during a Redis outage, fail closed rejects every request.
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            if (jwt.getId() != null
                    && redisson.getBucket(REVOKED_PREFIX + jwt.getId(), StringCodec.INSTANCE).isExists()) {
                return OAuth2TokenValidatorResult.failure(REVOKED);
            }
            String validAfter = redisson.<String>getBucket(VALID_AFTER_PREFIX + jwt.getSubject(),
                    StringCodec.INSTANCE).get();
            if (validAfter != null && jwt.getIssuedAt() != null
                    && jwt.getIssuedAt().getEpochSecond() < Long.parseLong(validAfter)) {
                return OAuth2TokenValidatorResult.failure(REVOKED);
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException e) {
            if (failOpen) {
                log.warn("Revocation check skipped, Redis unavailable (fail-open): {}", e.getMessage());
                return OAuth2TokenValidatorResult.success();
            }
            log.warn("Revocation check failed, Redis unavailable (fail-closed): {}", e.getMessage());
            return OAuth2TokenValidatorResult.failure(UNAVAILABLE);
        }
    }
}
