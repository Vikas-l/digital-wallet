package com.digitalwallet.wallet.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.digitalwallet.wallet.wallet.TransactionRepository;
import com.digitalwallet.wallet.wallet.Wallet;
import com.digitalwallet.wallet.wallet.WalletTransaction;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.codec.Codec;

class IdempotencyServiceTest {

    private static final String KEY = "retry-key-123";
    private static final String HASH = "hash-1";

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final IdempotencyService service = new IdempotencyService(redisson, transactions);

    @Test
    void replayDoesNotRunTheRateLimitCheck() {
        WalletTransaction existing = WalletTransaction.topUp(1L, new Wallet(1L, "INR"),
                new BigDecimal("10.00"), KEY, HASH);
        when(transactions.findByInitiatorUserIdAndIdempotencyKey(1L, KEY)).thenReturn(Optional.of(existing));
        AtomicInteger rateLimitChecks = new AtomicInteger();

        IdempotencyService.Result result = service.execute(1L, KEY, HASH, rateLimitChecks::incrementAndGet,
                () -> { throw new AssertionError("must not execute again"); });

        assertThat(result.replayed()).isTrue();
        assertThat(rateLimitChecks).hasValue(0);
    }

    @Test
    void redisDownStillExecutesOnceRelyingOnTheDatabaseKey() {
        when(transactions.findByInitiatorUserIdAndIdempotencyKey(eq(1L), anyString())).thenReturn(Optional.empty());
        when(redisson.getBucket(anyString(), any(Codec.class)))
                .thenThrow(new RedisConnectionException("Redis is down"));
        WalletTransaction created = WalletTransaction.topUp(1L, new Wallet(1L, "INR"),
                new BigDecimal("10.00"), KEY, HASH);
        AtomicInteger rateLimitChecks = new AtomicInteger();

        IdempotencyService.Result result = service.execute(1L, KEY, HASH, rateLimitChecks::incrementAndGet,
                () -> created);

        assertThat(result.replayed()).isFalse();
        assertThat(result.transaction()).isSameAs(created);
        assertThat(rateLimitChecks).hasValue(1);
    }
}
