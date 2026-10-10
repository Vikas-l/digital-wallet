package com.digitalwallet.rewards.rule;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.digitalwallet.common.events.TransactionType;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create/update payload for a cashback rule; also the structured output the AI must produce. */
public record RuleRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,49}$",
                message = "must be 3-50 chars of upper-case letters, digits or '_' and start with a letter")
        String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        @NotNull TransactionType transactionType,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal minAmount,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal cashbackPercent,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal maxReward,
        @Min(1) @Max(1000) Integer dailyLimitPerUser,
        @NotNull @Min(0) @Max(1000) Integer priority,
        Instant validFrom,
        Instant validTo) {

    @JsonIgnore
    @AssertTrue(message = "rewards can only be configured for TOP_UP or TRANSFER transactions")
    public boolean isRewardableType() {
        return transactionType != TransactionType.REWARD;
    }

    @JsonIgnore
    @AssertTrue(message = "validTo must be after validFrom")
    public boolean isValidWindow() {
        return validFrom == null || validTo == null || validTo.isAfter(validFrom);
    }
}
