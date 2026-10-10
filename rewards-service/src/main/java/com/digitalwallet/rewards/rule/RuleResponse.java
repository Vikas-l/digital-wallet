package com.digitalwallet.rewards.rule;

import java.math.BigDecimal;
import java.time.Instant;

import com.digitalwallet.common.events.TransactionType;

public record RuleResponse(Long id, String code, String name, String description, TransactionType transactionType,
        BigDecimal minAmount, BigDecimal cashbackPercent, BigDecimal maxReward, Integer dailyLimitPerUser,
        int priority, boolean active, Instant validFrom, Instant validTo, long version, Instant updatedAt) {

    public static RuleResponse of(RewardRule r) {
        return new RuleResponse(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.getTransactionType(),
                r.getMinAmount(), r.getCashbackPercent(), r.getMaxReward(), r.getDailyLimitPerUser(),
                r.getPriority(), r.isActive(), r.getValidFrom(), r.getValidTo(), r.getVersion(), r.getUpdatedAt());
    }
}
