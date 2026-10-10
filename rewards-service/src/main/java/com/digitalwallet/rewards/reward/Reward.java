package com.digitalwallet.rewards.reward;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "rewards")
public class Reward implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_transaction_id", nullable = false, unique = true)
    private UUID sourceTransactionId;

    @Column(name = "rule_code", nullable = false, length = 50)
    private String ruleCode;

    @Column(name = "transaction_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal transactionAmount;

    @Column(name = "reward_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal rewardAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Reward() {
    }

    public Reward(Long userId, UUID sourceTransactionId, String ruleCode, BigDecimal transactionAmount,
            BigDecimal rewardAmount, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.sourceTransactionId = sourceTransactionId;
        this.ruleCode = ruleCode;
        this.transactionAmount = transactionAmount;
        this.rewardAmount = rewardAmount;
        this.createdAt = createdAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getSourceTransactionId() {
        return sourceTransactionId;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public BigDecimal getTransactionAmount() {
        return transactionAmount;
    }

    public BigDecimal getRewardAmount() {
        return rewardAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
