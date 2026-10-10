package com.digitalwallet.wallet.ratelimit;

import java.time.Duration;
import java.time.Instant;

import com.digitalwallet.wallet.error.ApiException;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter backed by Redis, so the limit holds across every
 * instance of the service. One counter per subject per window; INCR is atomic and
 * the key expires on its own, so no cleanup job is needed.
 */
@Component
public class RateLimiter {

    private final RedissonClient redisson;

    public RateLimiter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    public void checkPerMinute(String action, Long userId, int limit) {
        check(action, String.valueOf(userId), limit, Duration.ofMinutes(1));
    }

    public void check(String action, String subject, int limit, Duration window) {
        long windowNumber = Instant.now().getEpochSecond() / window.toSeconds();
        RAtomicLong counter = redisson.getAtomicLong(
                "digitalwallet:rl:" + action + ":" + subject + ":" + windowNumber);
        long count = counter.incrementAndGet();
        if (count == 1) {
            counter.expire(window.multipliedBy(2));
        }
        if (count > limit) {
            throw ApiException.tooManyRequests("RATE_LIMITED", "Too many " + action + " requests; limit is "
                    + limit + " per " + window.toMinutes() + " minute" + (window.toMinutes() == 1 ? "" : "s"));
        }
    }
}
