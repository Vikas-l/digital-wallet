package com.digitalwallet.rewards.rule;

import java.math.BigDecimal;
import java.time.Instant;

import com.digitalwallet.common.events.TransactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reward_rules")
public class RewardRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @Column(name = "min_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "cashback_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal cashbackPercent;

    @Column(name = "max_reward", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxReward;

    @Column(name = "daily_limit_per_user")
    private Integer dailyLimitPerUser;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RewardRule() {
    }

    public RewardRule(RuleRequest request) {
        this.code = request.code();
        this.createdAt = Instant.now();
        this.active = true;
        apply(request);
    }

    /** Everything except the code (a stable business identifier) can be changed. */
    public void apply(RuleRequest request) {
        this.name = request.name();
        this.description = request.description();
        this.transactionType = request.transactionType();
        this.minAmount = request.minAmount();
        this.cashbackPercent = request.cashbackPercent();
        this.maxReward = request.maxReward();
        this.dailyLimitPerUser = request.dailyLimitPerUser();
        this.priority = request.priority();
        this.validFrom = request.validFrom();
        this.validTo = request.validTo();
        this.updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public RuleSnapshot toSnapshot() {
        return new RuleSnapshot(id, code, transactionType, minAmount, cashbackPercent, maxReward,
                dailyLimitPerUser, priority, validFrom, validTo);
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public BigDecimal getCashbackPercent() {
        return cashbackPercent;
    }

    public BigDecimal getMaxReward() {
        return maxReward;
    }

    public Integer getDailyLimitPerUser() {
        return dailyLimitPerUser;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
