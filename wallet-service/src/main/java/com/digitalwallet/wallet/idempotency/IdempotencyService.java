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
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header must be 8-64 characters of letters, digits, '-' or '_'");
        }
        Optional<Result> replay = findExisting(userId, key, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        RBucket<String> guard = redisson.getBucket("digitalwallet:idem:" + userId + ":" + key, StringCodec.INSTANCE);
        if (!guard.setIfAbsent("processing", GUARD_TTL)) {
            throw ApiException.conflict("REQUEST_IN_PROGRESS",
                    "A request with this Idempotency-Key is already being processed");
        }
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
            guard.delete();
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
