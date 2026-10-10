package com.digitalwallet.wallet.qr;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.error.ApiException;
import com.digitalwallet.wallet.idempotency.IdempotencyService;
import com.digitalwallet.wallet.qr.QrDtos.CreateQrRequest;
import com.digitalwallet.wallet.qr.QrDtos.PayQrRequest;
import com.digitalwallet.wallet.qr.QrDtos.QrPreview;
import com.digitalwallet.wallet.qr.QrDtos.QrResponse;
import com.digitalwallet.wallet.user.AppUser;
import com.digitalwallet.wallet.user.UserRepository;
import com.digitalwallet.wallet.wallet.TransactionRepository;
import com.digitalwallet.wallet.wallet.WalletService;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

/**
 * QR payments, like UPI "scan and pay". A payee creates a signed QR code; a payer
 * scans it (preview) and pays it through the normal transfer flow.
 *
 * Single-use codes are protected twice: an atomic Redis claim (SET NX, expiring
 * with the code) stops two payers racing for the same bill, and a unique DB
 * column linking the code to its transaction guarantees it even if Redis loses data.
 */
@Service
public class QrPaymentService {

    private static final Duration DEFAULT_VALIDITY = Duration.ofMinutes(15);

    private final QrCodec codec;
    private final QrImageRenderer renderer;
    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final RedissonClient redisson;
    private final Duration maxValidity;

    public QrPaymentService(QrCodec codec, QrImageRenderer renderer, WalletService walletService,
            IdempotencyService idempotencyService, UserRepository userRepository,
            TransactionRepository transactionRepository, RedissonClient redisson, WalletProperties properties) {
        this.codec = codec;
        this.renderer = renderer;
        this.walletService = walletService;
        this.idempotencyService = idempotencyService;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.redisson = redisson;
        this.maxValidity = properties.qr().maxValidity();
    }

    public QrResponse create(Long payeeUserId, CreateQrRequest request) {
        Duration validity = request.validityMinutes() == null
                ? DEFAULT_VALIDITY : Duration.ofMinutes(request.validityMinutes());
        if (validity.compareTo(maxValidity) > 0) {
            throw ApiException.badRequest("QR_VALIDITY_TOO_LONG",
                    "A QR code can be valid for at most " + maxValidity.toMinutes() + " minutes");
        }
        boolean singleUse = request.singleUse() == null || request.singleUse();
        BigDecimal amount = request.amount() == null ? null : request.amount().setScale(2);
        Instant expiresAt = codec.now().plus(validity);

        QrCodec.Claims claims = new QrCodec.Claims(UUID.randomUUID(), payeeUserId, amount, singleUse,
                expiresAt.getEpochSecond());
        String payload = codec.encode(claims);
        return new QrResponse(claims.qrId(), payload, amount, singleUse, claims.expiresAt(),
                Base64.getEncoder().encodeToString(renderer.png(payload)));
    }

    public byte[] image(String payload) {
        return renderer.png(payload);
    }

    public QrPreview preview(String payload) {
        QrCodec.Claims qr = codec.decode(payload);
        AppUser payee = userRepository.findById(qr.payeeUserId())
                .orElseThrow(() -> ApiException.notFound("RECIPIENT_NOT_FOUND", "The QR code's owner no longer exists"));
        boolean alreadyPaid = qr.singleUse() && transactionRepository.existsBySingleUseQrId(qr.qrId());
        return new QrPreview(qr.qrId(), payee.getFullName(), mask(payee.getEmail()), qr.amount(), qr.singleUse(),
                qr.expiresAt(), alreadyPaid);
    }

    public IdempotencyService.Result pay(Long payerUserId, PayQrRequest request, String idempotencyKey) {
        QrCodec.Claims qr = codec.decode(request.payload());
        BigDecimal amount = resolveAmount(qr, request.amount());
        if (qr.payeeUserId().equals(payerUserId)) {
            throw ApiException.badRequest("SELF_TRANSFER", "You cannot pay your own QR code");
        }
        String hash = IdempotencyService.fingerprint("QR", qr.qrId(), amount.stripTrailingZeros());

        return idempotencyService.execute(payerUserId, idempotencyKey, hash, () -> {
            if (!qr.singleUse()) {
                return walletService.payQr(payerUserId, qr.payeeUserId(), amount, request.note(), null,
                        idempotencyKey, hash);
            }
            RBucket<String> claim = redisson.getBucket("digitalwallet:qr:claim:" + qr.qrId(), StringCodec.INSTANCE);
            Duration untilExpiry = Duration.between(codec.now(), qr.expiresAt()).plusMinutes(1);
            if (!claim.setIfAbsent(String.valueOf(payerUserId), untilExpiry)) {
                throw ApiException.conflict("QR_ALREADY_USED", "This QR code has already been paid");
            }
            try {
                return walletService.payQr(payerUserId, qr.payeeUserId(), amount, request.note(), qr.qrId(),
                        idempotencyKey, hash);
            } catch (RuntimeException e) {
                claim.delete(); // payment failed (e.g. insufficient funds): the bill can still be paid later
                throw e;
            }
        });
    }

    private static BigDecimal resolveAmount(QrCodec.Claims qr, BigDecimal requested) {
        if (qr.amount() == null) {
            if (requested == null) {
                throw ApiException.badRequest("AMOUNT_REQUIRED", "This QR code has no fixed amount; enter one");
            }
            return requested;
        }
        if (requested != null && requested.compareTo(qr.amount()) != 0) {
            throw ApiException.unprocessable("QR_AMOUNT_MISMATCH",
                    "This QR code is for exactly " + qr.amount().toPlainString());
        }
        return qr.amount();
    }

    /** b***@example.com: enough for the payer to recognise the payee without exposing the address. */
    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? email : email.charAt(0) + "***" + email.substring(at);
    }
}
