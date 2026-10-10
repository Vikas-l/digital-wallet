package com.digitalwallet.rewards.rule;

import java.math.BigDecimal;
import java.time.Instant;

import com.digitalwallet.common.events.TransactionType;

/** Immutable, cache-friendly view of an active rule; this is what the engine evaluates. */
public record RuleSnapshot(
        Long id,
        String code,
        TransactionType transactionType,
        BigDecimal minAmount,
        BigDecimal cashbackPercent,
        BigDecimal maxReward,
        Integer dailyLimitPerUser,
        int priority,
        Instant validFrom,
        Instant validTo) {

    public boolean isLiveAt(Instant at) {
        return (validFrom == null || !at.isBefore(validFrom)) && (validTo == null || at.isBefore(validTo));
    }
}
