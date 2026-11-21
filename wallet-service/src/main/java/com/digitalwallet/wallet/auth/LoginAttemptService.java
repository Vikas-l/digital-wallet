package com.digitalwallet.wallet.auth;

import java.time.Duration;

import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.error.ApiException;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Brute-force protection for login. Failed logins are counted per email in Redis; after
 * {@code maxFailures} wrong passwords the email is locked for {@code lockout} (the counter's
 * TTL starts at the first failure). While locked, even the correct password is refused, so
 * guessing gains nothing. A successful login or a password reset clears the counter.
 *
 * <p>Trade-off: anyone can lock a victim's login for 15 minutes by typing wrong passwords;
 * "forgot password" still works for the real owner. Like the rate limiter this fails open
 * when Redis is down.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final String PREFIX = "digitalwallet:login-failures:";

    private final RedissonClient redisson;
    private final int maxFailures;
    private final Duration lockout;

    public LoginAttemptService(RedissonClient redisson, WalletProperties properties) {
        this.redisson = redisson;
        this.maxFailures = properties.login().maxFailures();
        this.lockout = properties.login().lockout();
    }

    public void checkNotLocked(String email) {
        long failures;
        try {
            failures = counter(email).get();
        } catch (RuntimeException e) {
            log.warn("Login attempt counter unavailable, allowing login: {}", e.getMessage());
            return;
        }
        if (failures >= maxFailures) {
            throw ApiException.tooManyRequests("LOGIN_LOCKED", "Too many failed logins. Try again in "
                    + lockout.toMinutes() + " minutes or reset your password");
        }
    }

    public void recordFailure(String email) {
        try {
            RAtomicLong counter = counter(email);
            if (counter.incrementAndGet() == 1) {
                counter.expire(lockout);
            }
        } catch (RuntimeException e) {
            log.warn("Could not record failed login: {}", e.getMessage());
        }
    }

    public void reset(String email) {
        try {
            counter(email).delete();
        } catch (RuntimeException e) {
            log.warn("Could not reset failed-login counter: {}", e.getMessage());
        }
    }

    private RAtomicLong counter(String email) {
        return redisson.getAtomicLong(PREFIX + email);
    }
}
