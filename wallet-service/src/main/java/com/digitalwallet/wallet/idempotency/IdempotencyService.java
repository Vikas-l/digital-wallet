package com.digitalwallet.wallet.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.digitalwallet.wallet.error.ApiException;
import com.digitalwallet.wallet.wallet.TransactionRepository;
import com.digitalwallet.wallet.wallet.WalletTransaction;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Makes money-moving requests safe to retry. A client sends the same
 * {@code Idempotency-Key} when it retries (timeout, double click, network error)
 * and gets the original result back instead of being charged twice.
 *
 * <ol>
 *   <li>The DB unique key (initiator_user_id, idempotency_key) is the source of truth.</li>
 *   <li>A short-lived Redis guard (SET NX + TTL) stops two concurrent requests with the
 *       same key from both doing the work; the loser gets 409 and can retry.</li>
 *   <li>If Redis loses the guard, the unique constraint still rejects the duplicate and
 *       the stored result is replayed.</li>
 * </ol>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Duration GUARD_TTL = Duration.ofSeconds(30);

    private final RedissonClient redisson;
    private final TransactionRepository transactionRepository;

    public IdempotencyService(RedissonClient redisson, TransactionRepository transactionRepository) {
        this.redisson = redisson;
        this.transactionRepository = transactionRepository;
    }

    public record Result(WalletTransaction transaction, boolean replayed) {
    }

    /** Must be called outside a transaction so that a replay sees committed data. */
    public Result execute(Long userId, String key, String requestHash, Supplier<WalletTransaction> action) {
        return execute(userId, key, requestHash, () -> { }, action);
    }

    /**
     * @param beforeNewExecution runs only when the request is NOT a replay, right before the
     *        work starts. Used for rate limiting, so that a client safely retrying with the
     *        same key gets its original answer instead of a 429.
     */
    public Result execute(Long userId, String key, String requestHash, Runnable beforeNewExecution,
            Supplier<WalletTransaction> action) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header must be 8-64 characters of letters, digits, '-' or '_'");
        }
        Optional<Result> replay = findExisting(userId, key, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        beforeNewExecution.run();

        RBucket<String> guard = acquire("digitalwallet:idem:" + userId + ":" + key);
        boolean guarded = guard != null;
        try {
            // the previous holder of the guard may have committed just before we acquired it
            replay = findExisting(userId, key, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
            return new Result(action.get(), false);
        } catch (DataIntegrityViolationException e) {
            return findExisting(userId, key, requestHash).orElseThrow(() -> e);
        } finally {
            if (guarded) {
                release(guard);
            }
        }
    }

    /**
     * Returns the guard we now hold, or null if Redis is unavailable and we continue without it
     * (the DB unique key still stops duplicates). Throws 409 if another request holds it.
     */
    private RBucket<String> acquire(String name) {
        RBucket<String> guard;
        boolean acquired;
        try {
            guard = redisson.getBucket(name, StringCodec.INSTANCE);
            acquired = guard.setIfAbsent("processing", GUARD_TTL);
        } catch (RuntimeException e) {
            log.warn("Idempotency guard unavailable, relying on the database unique key: {}", e.getMessage());
            return null;
        }
        if (!acquired) {
            throw ApiException.conflict("REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is already being processed");
        }
        return guard;
    }

    private static void release(RBucket<String> guard) {
        try {
            guard.delete();
        } catch (RuntimeException e) {
            log.warn("Could not release idempotency guard (it expires by itself): {}", e.getMessage());
        }
    }

    private Optional<Result> findExisting(Long userId, String key, String requestHash) {
        return transactionRepository.findByInitiatorUserIdAndIdempotencyKey(userId, key)
                .map(existing -> {
                    if (!requestHash.equals(existing.getRequestHash())) {
                        throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                                "This Idempotency-Key was already used with a different request");
                    }
                    return new Result(existing, true);
                });
    }

    /** SHA-256 over the parts that define "the same request". */
    public static String fingerprint(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object part : parts) {
            canonical.append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
