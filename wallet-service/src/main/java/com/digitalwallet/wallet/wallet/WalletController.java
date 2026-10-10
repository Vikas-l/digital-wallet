package com.digitalwallet.wallet.wallet;

import com.digitalwallet.common.events.TransactionType;
import com.digitalwallet.wallet.auth.AuthService;
import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.idempotency.IdempotencyService;
import com.digitalwallet.wallet.ratelimit.RateLimiter;
import com.digitalwallet.wallet.wallet.WalletDtos.StatementEntry;
import com.digitalwallet.wallet.wallet.WalletDtos.TopUpRequest;
import com.digitalwallet.wallet.wallet.WalletDtos.TransactionResponse;
import com.digitalwallet.wallet.wallet.WalletDtos.TransferRequest;
import com.digitalwallet.wallet.wallet.WalletDtos.WalletResponse;
import com.digitalwallet.wallet.web.CurrentUser;
import com.digitalwallet.wallet.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallets/me")
@Tag(name = "Wallet")
public class WalletController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final RateLimiter rateLimiter;
    private final int transfersPerMinute;

    public WalletController(WalletService walletService, IdempotencyService idempotencyService,
            RateLimiter rateLimiter, WalletProperties properties) {
        this.walletService = walletService;
        this.idempotencyService = idempotencyService;
        this.rateLimiter = rateLimiter;
        this.transfersPerMinute = properties.limits().transfersPerMinute();
    }

    @GetMapping
    @Operation(summary = "Current balance of the caller's wallet")
    public WalletResponse wallet(@AuthenticationPrincipal Jwt jwt) {
        return walletService.getWallet(CurrentUser.id(jwt));
    }

    @GetMapping("/statement")
    @Operation(summary = "Ledger entries of the caller's wallet, newest first")
    public PageResponse<StatementEntry> statement(@AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20) Pageable pageable) {
        return walletService.statement(CurrentUser.id(jwt), pageable);
    }

    @PostMapping("/top-ups")
    @Operation(summary = "Add money to the wallet (idempotent)")
    public ResponseEntity<TransactionResponse> topUp(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Unique per logical request; reuse it when retrying")
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody TopUpRequest request) {
        Long userId = CurrentUser.id(jwt);
        String hash = IdempotencyService.fingerprint(TransactionType.TOP_UP, request.amount().stripTrailingZeros());
        return respond(idempotencyService.execute(userId, idempotencyKey, hash,
                () -> walletService.topUp(userId, request.amount(), idempotencyKey, hash)));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Send money to another user (idempotent, rate limited)")
    public ResponseEntity<TransactionResponse> transfer(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Unique per logical request; reuse it when retrying")
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        Long userId = CurrentUser.id(jwt);
        rateLimiter.checkPerMinute("transfer", userId, transfersPerMinute);
        String hash = IdempotencyService.fingerprint(TransactionType.TRANSFER,
                AuthService.normalize(request.recipientEmail()), request.amount().stripTrailingZeros());
        return respond(idempotencyService.execute(userId, idempotencyKey, hash,
                () -> walletService.transfer(userId, request.recipientEmail(), request.amount(), request.note(),
                        idempotencyKey, hash)));
    }

    private static ResponseEntity<TransactionResponse> respond(IdempotencyService.Result result) {
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(TransactionResponse.of(result.transaction()));
    }
}
