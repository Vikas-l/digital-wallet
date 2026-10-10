package com.digitalwallet.wallet.qr;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class QrDtos {

    private QrDtos() {
    }

    /**
     * @param amount          fixed amount, or null to let the payer enter it (e.g. a shop's counter QR)
     * @param singleUse       true (default) for a one-time bill; false for a reusable "pay me" code
     * @param validityMinutes how long the code can be paid (default 15)
     */
    public record CreateQrRequest(
            @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal amount,
            Boolean singleUse,
            @Min(1) @Max(1440) Integer validityMinutes) {
    }

    public record QrResponse(UUID qrId, String payload, BigDecimal amount, boolean singleUse, Instant expiresAt,
            String pngBase64) {
    }

    public record ScanRequest(@NotBlank @Size(max = 1000) String payload) {
    }

    /** What the payer's app shows after scanning, before confirming the payment. */
    public record QrPreview(UUID qrId, String payeeName, String payeeEmail, BigDecimal amount, boolean singleUse,
            Instant expiresAt, boolean alreadyPaid) {
    }

    public record PayQrRequest(
            @NotBlank @Size(max = 1000) String payload,
            @DecimalMin("1.00") @DecimalMax("100000.00") @Digits(integer = 6, fraction = 2) BigDecimal amount,
            @Size(max = 140) String note) {
    }
}
