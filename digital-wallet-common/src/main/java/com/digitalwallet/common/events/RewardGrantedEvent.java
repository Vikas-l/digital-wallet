package com.digitalwallet.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published by rewards-service when a cashback rule matched a transaction. */
public record RewardGrantedEvent(
        UUID eventId,
        UUID rewardId,
        UUID sourceTransactionId,
        Long userId,
        BigDecimal amount,
        String ruleCode,
        Instant occurredAt) {
}
