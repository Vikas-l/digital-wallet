package com.digitalwallet.wallet.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.error.ApiException;
import com.digitalwallet.wallet.ratelimit.RateLimiter;
import com.digitalwallet.wallet.user.AppUser;
import com.digitalwallet.wallet.user.UserRepository;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Forgot password" with a one-time code sent by email.
 *
 * <ul>
 *   <li>The answer to "send me a code" is identical whether or not the email exists,
 *       so the endpoint cannot be used to discover who has an account.</li>
 *   <li>Only a SHA-256 hash of the 6-digit code is kept, in Redis, expiring after 10 minutes.</li>
 *   <li>After 5 wrong guesses the code is destroyed (a 6-digit code must not be brute-forced).</li>
 *   <li>A successful reset logs the user out on every device.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    private static final String CODE_PREFIX = "digitalwallet:pwd-reset:code:";
    private static final String ATTEMPTS_PREFIX = "digitalwallet:pwd-reset:attempts:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedissonClient redisson;
    private final ResetCodeMailer mailer;
    private final RateLimiter rateLimiter;
    private final TokenRevocationService tokenRevocationService;
    private final Duration codeTtl;
    private final int maxAttempts;

    public PasswordResetService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            RedissonClient redisson, ResetCodeMailer mailer, RateLimiter rateLimiter,
            TokenRevocationService tokenRevocationService, WalletProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.redisson = redisson;
        this.mailer = mailer;
        this.rateLimiter = rateLimiter;
        this.tokenRevocationService = tokenRevocationService;
        this.codeTtl = properties.passwordReset().codeTtl();
        this.maxAttempts = properties.passwordReset().maxAttempts();
    }

    public void requestCode(String rawEmail) {
        String email = AuthService.normalize(rawEmail);
        // limited per email address, existing or not, so the limit itself reveals nothing
        rateLimiter.check("password-reset", email, 3, Duration.ofMinutes(15));
        userRepository.findByEmail(email).ifPresent(user -> {
            String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
            codeBucket(user).set(hash(user, code), codeTtl);
            attempts(user).delete();
            mailer.send(user.getEmail(), user.getFullName(), code);
        });
    }

    @Transactional
    public void resetPassword(String rawEmail, String code, String newPassword) {
        AppUser user = userRepository.findByEmail(AuthService.normalize(rawEmail)).orElseThrow(PasswordResetService::invalidCode);
        RBucket<String> stored = codeBucket(user);
        String expectedHash = stored.get();
        if (expectedHash == null) {
            throw invalidCode();
        }
        RAtomicLong attempts = attempts(user);
        if (attempts.incrementAndGet() == 1) {
            attempts.expire(codeTtl);
        }
        if (attempts.get() > maxAttempts) {
            stored.delete();
            attempts.delete();
            throw ApiException.tooManyRequests("TOO_MANY_ATTEMPTS",
                    "Too many wrong codes. Request a new code and try again");
        }
        if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                hash(user, code).getBytes(StandardCharsets.US_ASCII))) {
            throw invalidCode();
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        stored.delete();
        attempts.delete();
        tokenRevocationService.revokeAllIssuedBefore(user.getId(), Instant.now());
    }

    private RBucket<String> codeBucket(AppUser user) {
        return redisson.getBucket(CODE_PREFIX + user.getId(), StringCodec.INSTANCE);
    }

    private RAtomicLong attempts(AppUser user) {
        return redisson.getAtomicLong(ATTEMPTS_PREFIX + user.getId());
    }

    private static String hash(AppUser user, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((user.getId() + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalidCode() {
        return ApiException.badRequest("INVALID_RESET_CODE", "The code is wrong or has expired");
    }
}
