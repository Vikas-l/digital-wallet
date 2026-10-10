package com.digitalwallet.wallet.qr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import com.digitalwallet.wallet.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class QrCodecTest {

    private static final String SECRET = "unit-test-qr-secret-0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QrCodec codec = new QrCodec(SECRET, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void roundTripsClaims() {
        QrCodec.Claims claims = claims(new BigDecimal("250.00"), NOW.plusSeconds(900));
        String payload = codec.encode(claims);

        assertThat(payload).startsWith("DW1.");
        assertThat(codec.decode(payload)).isEqualTo(claims);
    }

    @Test
    void rejectsEditedAmount() throws Exception {
        String payload = codec.encode(claims(new BigDecimal("250.00"), NOW.plusSeconds(900)));
        String[] parts = payload.split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String forgedBody = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.replace("250.00", "1.00").getBytes(StandardCharsets.UTF_8));

        assertInvalid(parts[0] + "." + forgedBody + "." + parts[2]);
    }

    @Test
    void rejectsCodeSignedWithAnotherKey() {
        QrCodec attacker = new QrCodec("attacker-secret-0123456789abcdefghij", objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertInvalid(attacker.encode(claims(null, NOW.plusSeconds(900))));
    }

    @Test
    void rejectsMalformedPayloads() {
        for (String payload : new String[] {null, "", "hello", "DW1.abc", "XX1.a.b", "DW1.!!!.???", "upi://pay?pa=x"}) {
            assertInvalid(payload);
        }
    }

    @Test
    void rejectsExpiredCode() {
        String payload = codec.encode(claims(null, NOW));

        assertThatThrownBy(() -> codec.decode(payload))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(e.getCode()).isEqualTo("QR_EXPIRED");
                });
    }

    @Test
    void masksEmail() {
        assertThat(QrPaymentService.mask("bob@example.com")).isEqualTo("b***@example.com");
    }

    private void assertInvalid(String payload) {
        assertThatThrownBy(() -> codec.decode(payload))
                .as("payload %s", payload)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_QR"));
    }

    private static QrCodec.Claims claims(BigDecimal amount, Instant expiresAt) {
        return new QrCodec.Claims(UUID.randomUUID(), 42L, amount, true, expiresAt.getEpochSecond());
    }
}
