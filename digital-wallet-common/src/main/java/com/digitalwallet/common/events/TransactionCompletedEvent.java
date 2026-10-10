package com.digitalwallet.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by wallet-service (via the transactional outbox) whenever a
 * user-initiated money movement commits. {@code userId} is the user who
 * initiated it: the payer for a transfer, the wallet owner for a top-up.
 */
public record TransactionCompletedEvent(
        UUID eventId,
        UUID transactionId,
        TransactionType type,
        Long userId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
