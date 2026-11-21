package com.digitalwallet.wallet.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.digitalwallet.wallet.auth.TokenRevocationService;
import com.digitalwallet.wallet.config.WalletProperties;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.Codec;
import org.springframework.security.oauth2.jwt.Jwt;

/** Redis is a helper, MySQL is the source of truth: a Redis outage must not take the wallet down. */
class RedisOutageTest {

    private final RedissonClient redisson = mock(RedissonClient.class);

    RedisOutageTest() {
        RedisConnectionException down = new RedisConnectionException("Redis is down");
        when(redisson.getAtomicLong(anyString())).thenThrow(down);
        when(redisson.getBucket(anyString(), any(Codec.class))).thenThrow(down);
    }

    @Test
    void rateLimiterFailsOpen() {
        RateLimiter limiter = new RateLimiter(redisson);
        assertThatNoException().isThrownBy(() -> limiter.checkPerMinute("transfer", 1L, 1));
    }

    @Test
    void revocationCheckFailsOpenWhenConfigured() {
        assertThat(revocation(true).validate(jwt()).hasErrors()).isFalse();
    }

    @Test
    void revocationCheckFailsClosedWhenConfigured() {
        assertThat(revocation(false).validate(jwt()).hasErrors()).isTrue();
    }

    private TokenRevocationService revocation(boolean failOpen) {
        WalletProperties properties = new WalletProperties(
                new WalletProperties.Jwt(null, "digitalwallet", Duration.ofHours(1), failOpen),
                null, "redis://localhost:6379", "INR", null, null, null, null, null);
        return new TokenRevocationService(redisson, properties);
    }

    private static Jwt jwt() {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("alg", "RS256"), Map.of("sub", "1", "jti", "abc"));
    }
}
