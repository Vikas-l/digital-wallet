package com.digitalwallet.rewards.reward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import com.digitalwallet.rewards.config.RewardsProperties;

import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Monthly "top cashback earners" in a Redis sorted set (ZINCRBY / ZREVRANGE):
 * O(log n) updates and top-N reads without touching MySQL. Redis is a derived
 * view here; MySQL stays the source of truth.
 */
@Component
public class Leaderboard {

    private static final Logger log = LoggerFactory.getLogger(Leaderboard.class);

    public record Entry(int rank, Long userId, BigDecimal totalCashback) {
    }

    private final RedissonClient redisson;
    private final ZoneId zone;

    public Leaderboard(RedissonClient redisson, RewardsProperties properties) {
        this.redisson = redisson;
        this.zone = properties.businessZone();
    }

    /** Only updates Redis once the reward is committed, so a rolled-back reward never shows up. */
    public void recordAfterCommit(Long userId, BigDecimal amount, Instant at) {
        Runnable update = () -> {
            try {
                RScoredSortedSet<String> set = set(YearMonth.from(at.atZone(zone)));
                set.addScore(String.valueOf(userId), amount.doubleValue());
                set.expire(Duration.ofDays(400));
            } catch (RuntimeException e) {
                log.warn("Leaderboard update failed for user {}: {}", userId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    update.run();
                }
            });
        } else {
            update.run();
        }
    }

    public List<Entry> top(YearMonth month, int limit) {
        var entries = set(month).entryRangeReversed(0, limit - 1);
        int[] rank = {0};
        return entries.stream()
                .map(e -> new Entry(++rank[0], Long.valueOf(e.getValue()),
                        BigDecimal.valueOf(e.getScore()).setScale(2, RoundingMode.HALF_EVEN)))
                .toList();
    }

    public YearMonth currentMonth() {
        return YearMonth.now(zone);
    }

    private RScoredSortedSet<String> set(YearMonth month) {
        return redisson.getScoredSortedSet("digitalwallet:leaderboard:" + month, StringCodec.INSTANCE);
    }
}
