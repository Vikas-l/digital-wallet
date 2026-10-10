package com.digitalwallet.wallet.qr;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.digitalwallet.wallet.config.WalletProperties;
import com.digitalwallet.wallet.error.ApiException;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Encodes and verifies the text inside a payment QR code:
 * {@code DW1.<base64url(json claims)>.<base64url(HMAC-SHA256 signature)>}.
 *
 * The QR is self-contained (no DB row is needed to create one), and because it is
 * signed with a server-side key, editing the payee or amount invalidates it.
 */
@Component
public class QrCodec {

    static final String PREFIX = "DW1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    /** What a QR code asserts. Short JSON names keep the QR small and easy to scan. */
    public record Claims(
            @JsonProperty("id") UUID qrId,
            @JsonProperty("to") Long payeeUserId,
            @JsonProperty("amt") BigDecimal amount,
            @JsonProperty("su") boolean singleUse,
            @JsonProperty("exp") long expiresAtEpochSecond) {

        public Instant expiresAt() {
            return Instant.ofEpochSecond(expiresAtEpochSecond);
        }
    }

    private final byte[] key;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public QrCodec(WalletProperties properties, ObjectMapper objectMapper) {
        this(properties.qr().secret(), objectMapper, Clock.systemUTC());
    }

    QrCodec(String secret, ObjectMapper objectMapper, Clock clock) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String encode(Claims claims) {
        try {
            String body = B64.encodeToString(objectMapper.writeValueAsBytes(claims));
            return PREFIX + "." + body + "." + B64.encodeToString(sign(body));
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode QR claims", e);
        }
    }

    /** Returns the claims only if the signature is valid and the code has not expired. */
    public Claims decode(String payload) {
        String[] parts = payload == null ? new String[0] : payload.trim().split("\\.");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw invalid();
        }
        byte[] signature;
        Claims claims;
        try {
            signature = B64_DECODER.decode(parts[2]);
            // constant-time comparison: no timing hints about the correct signature
            if (!MessageDigest.isEqual(sign(parts[1]), signature)) {
                throw invalid();
            }
            claims = objectMapper.readValue(B64_DECODER.decode(parts[1]), Claims.class);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
        if (claims.qrId() == null || claims.payeeUserId() == null) {
            throw invalid();
        }
        if (!clock.instant().isBefore(claims.expiresAt())) {
            throw new ApiException(HttpStatus.GONE, "QR_EXPIRED", "This QR code has expired");
        }
        return claims;
    }

    public Instant now() {
        return clock.instant();
    }

    private byte[] sign(String body) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); // Mac is not thread-safe: one per call
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
    }

    private static ApiException invalid() {
        return ApiException.badRequest("INVALID_QR", "This is not a valid Digital Wallet QR code");
    }
}
