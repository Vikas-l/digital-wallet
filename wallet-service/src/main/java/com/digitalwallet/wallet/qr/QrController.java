package com.digitalwallet.wallet.qr;

import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.idempotency.IdempotencyService;
import com.digitalwallet.wallet.qr.QrDtos.CreateQrRequest;
import com.digitalwallet.wallet.qr.QrDtos.PayQrRequest;
import com.digitalwallet.wallet.qr.QrDtos.QrPreview;
import com.digitalwallet.wallet.qr.QrDtos.QrResponse;
import com.digitalwallet.wallet.qr.QrDtos.ScanRequest;
import com.digitalwallet.wallet.ratelimit.RateLimiter;
import com.digitalwallet.wallet.wallet.WalletDtos.TransactionResponse;
import com.digitalwallet.wallet.web.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qr")
@Tag(name = "QR payments")
public class QrController {

    private final QrPaymentService qrPaymentService;
    private final RateLimiter rateLimiter;
    private final int transfersPerMinute;

    public QrController(QrPaymentService qrPaymentService, RateLimiter rateLimiter, WalletProperties properties) {
        this.qrPaymentService = qrPaymentService;
        this.rateLimiter = rateLimiter;
        this.transfersPerMinute = properties.limits().transfersPerMinute();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a signed QR code to receive money (payload + PNG as base64)")
    public QrResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateQrRequest request) {
        return qrPaymentService.create(CurrentUser.id(jwt), request);
    }

    @PostMapping(value = "/image", produces = MediaType.IMAGE_PNG_VALUE)
    @Operation(summary = "Render a QR payload as a PNG image (shows the scannable code in Swagger)")
    public byte[] image(@Valid @RequestBody ScanRequest request) {
        return qrPaymentService.image(request.payload());
    }

    @PostMapping("/preview")
    @Operation(summary = "Scan: check a QR code and show who will be paid, before paying")
    public QrPreview preview(@Valid @RequestBody ScanRequest request) {
        return qrPaymentService.preview(request.payload());
    }

    @PostMapping("/pay")
    @Operation(summary = "Pay a scanned QR code (idempotent, rate limited, earns cashback like a transfer)")
    public ResponseEntity<TransactionResponse> pay(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Unique per payment attempt; reuse it when retrying")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PayQrRequest request) {
        Long userId = CurrentUser.id(jwt);
        rateLimiter.checkPerMinute("transfer", userId, transfersPerMinute);
        IdempotencyService.Result result = qrPaymentService.pay(userId, request, idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(TransactionResponse.of(result.transaction()));
    }
}
