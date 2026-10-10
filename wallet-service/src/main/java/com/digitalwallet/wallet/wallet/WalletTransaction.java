package com.digitalwallet.wallet.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.digitalwallet.common.events.TransactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** An immutable record of one money movement. Balances change only through these. */
@Entity
@Table(name = "transactions")
public class WalletTransaction implements Persistable<UUID> {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "initiator_user_id", nullable = false)
    private Long initiatorUserId;

    @Column(name = "source_wallet_id")
    private Long sourceWalletId;

    @Column(name = "target_wallet_id", nullable = false)
    private Long targetWalletId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Set only for payments of single-use QR codes; unique in the database. */
    @Column(name = "single_use_qr_id", length = 36)
    private UUID singleUseQrId;

    /** Ids are assigned by the application, so tell Spring Data to INSERT instead of merge. */
    @Transient
    private boolean isNew = true;

    protected WalletTransaction() {
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

    private WalletTransaction(TransactionType type, Long initiatorUserId, Long sourceWalletId, Long targetWalletId,
            BigDecimal amount, String currency, String description, String idempotencyKey, String requestHash) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.initiatorUserId = initiatorUserId;
        this.sourceWalletId = sourceWalletId;
        this.targetWalletId = targetWalletId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
    }

    public static WalletTransaction topUp(Long userId, Wallet wallet, BigDecimal amount,
            String idempotencyKey, String requestHash) {
        return new WalletTransaction(TransactionType.TOP_UP, userId, null, wallet.getId(), amount,
                wallet.getCurrency(), "Wallet top-up", idempotencyKey, requestHash);
    }

    public static WalletTransaction transfer(Long userId, Wallet source, Wallet target, BigDecimal amount,
            String description, String idempotencyKey, String requestHash) {
        return new WalletTransaction(TransactionType.TRANSFER, userId, source.getId(), target.getId(), amount,
                source.getCurrency(), description, idempotencyKey, requestHash);
    }

    public static WalletTransaction reward(Wallet wallet, BigDecimal amount, String description, String rewardId) {
        return new WalletTransaction(TransactionType.REWARD, wallet.getUserId(), null, wallet.getId(), amount,
                wallet.getCurrency(), description, rewardId, null);
    }

    WalletTransaction paidWithSingleUseQr(UUID qrId) {
        this.singleUseQrId = qrId;
        return this;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getSingleUseQrId() {
        return singleUseQrId;
    }

    public TransactionType getType() {
        return type;
    }

    public Long getInitiatorUserId() {
        return initiatorUserId;
    }

    public Long getSourceWalletId() {
        return sourceWalletId;
    }

    public Long getTargetWalletId() {
        return targetWalletId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
