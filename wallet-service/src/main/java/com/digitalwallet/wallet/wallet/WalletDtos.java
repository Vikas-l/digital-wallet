package com.digitalwallet.wallet.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.digitalwallet.common.events.TransactionType;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WalletDtos {

    private WalletDtos() {
    }

    public record TopUpRequest(
            @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2)
            BigDecimal amount) {
    }

    public record TransferRequest(
            @NotBlank @Email String recipientEmail,
            @NotNull @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2)
            BigDecimal amount,
            @Size(max = 140) String note) {
    }

    public record WalletResponse(Long walletId, Long userId, BigDecimal balance, String currency, Instant updatedAt) {

        static WalletResponse of(Wallet w) {
            return new WalletResponse(w.getId(), w.getUserId(), w.getBalance(), w.getCurrency(), w.getUpdatedAt());
        }
    }

    public record TransactionResponse(UUID transactionId, TransactionType type, BigDecimal amount, String currency,
            Long sourceWalletId, Long targetWalletId, String description, Instant createdAt) {

        public static TransactionResponse of(WalletTransaction t) {
            return new TransactionResponse(t.getId(), t.getType(), t.getAmount(), t.getCurrency(),
                    t.getSourceWalletId(), t.getTargetWalletId(), t.getDescription(), t.getCreatedAt());
        }
    }

    public record StatementEntry(UUID transactionId, TransactionType type, LedgerEntry.Direction direction,
            BigDecimal amount, BigDecimal balanceAfter, String description, Instant createdAt) {

        static StatementEntry of(LedgerEntry e) {
            WalletTransaction t = e.getTransaction();
            return new StatementEntry(t.getId(), t.getType(), e.getDirection(), e.getAmount(),
                    e.getBalanceAfter(), t.getDescription(), e.getCreatedAt());
        }
    }
}
