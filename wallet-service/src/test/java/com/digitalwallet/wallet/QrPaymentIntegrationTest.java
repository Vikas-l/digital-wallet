package com.digitalwallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class QrPaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void generatedQrImageScansBackToTheSignedPayload() throws Exception {
        String bob = register(uniqueEmail());
        JsonNode qr = createQr(bob, Map.of("amount", 250)).getBody();

        String decoded = scan(Base64.getDecoder().decode(qr.get("pngBase64").asText()));
        assertThat(decoded).isEqualTo(qr.get("payload").asText());

        ResponseEntity<byte[]> png = rest.exchange("/api/qr/image", HttpMethod.POST,
                new HttpEntity<>(Map.of("payload", decoded), headers(bob, null)), byte[].class);
        assertThat(png.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(scan(png.getBody())).isEqualTo(decoded);
    }

    @Test
    void fixedAmountSingleUseQrIsPaidExactlyOnce() {
        String aliceEmail = uniqueEmail();
        String alice = register(aliceEmail);
        String bobEmail = uniqueEmail();
        String bob = register(bobEmail);
        topUp(alice, "1000.00");
        String payload = createQr(bob, Map.of("amount", 250)).getBody().get("payload").asText();

        JsonNode preview = post("/api/qr/preview", alice, Map.of("payload", payload), null).getBody();
        assertThat(preview.get("payeeName").asText()).isEqualTo("Test User");
        assertThat(preview.get("payeeEmail").asText()).startsWith(bobEmail.charAt(0) + "***@");
        assertThat(preview.get("amount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(preview.get("alreadyPaid").asBoolean()).isFalse();

        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> paid = pay(alice, payload, null, key);
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(paid.getBody().get("type").asText()).isEqualTo("TRANSFER");

        // the client retries the same request: replayed, not paid twice
        ResponseEntity<JsonNode> retry = pay(alice, payload, null, key);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("transactionId")).isEqualTo(paid.getBody().get("transactionId"));

        // a new payment attempt on the same bill is refused
        assertError(pay(alice, payload, null, UUID.randomUUID().toString()), HttpStatus.CONFLICT, "QR_ALREADY_USED");
        assertThat(post("/api/qr/preview", alice, Map.of("payload", payload), null)
                .getBody().get("alreadyPaid").asBoolean()).isTrue();

        assertThat(balance(alice)).isEqualByComparingTo("750.00");
        assertThat(balance(bob)).isEqualByComparingTo("250.00");
    }

    @Test
    void reusableOpenAmountQrAcceptsSeveralPayments() {
        String shop = register(uniqueEmail());
        String alice = register(uniqueEmail());
        String carol = register(uniqueEmail());
        topUp(alice, "500.00");
        topUp(carol, "500.00");
        String payload = createQr(shop, Map.of("singleUse", false, "validityMinutes", 60))
                .getBody().get("payload").asText();

        assertError(pay(alice, payload, null, UUID.randomUUID().toString()), HttpStatus.BAD_REQUEST, "AMOUNT_REQUIRED");
        assertThat(pay(alice, payload, "120.50", UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(pay(carol, payload, "80.00", UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(balance(shop)).isEqualByComparingTo("200.50");
    }

    @Test
    void failedPaymentReleasesTheSingleUseClaim() {
        String alice = register(uniqueEmail());
        String bob = register(uniqueEmail());
        String payload = createQr(bob, Map.of("amount", 300)).getBody().get("payload").asText();

        assertError(pay(alice, payload, null, UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS");

        topUp(alice, "300.00");
        assertThat(pay(alice, payload, null, UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(balance(bob)).isEqualByComparingTo("300.00");
    }

    @Test
    void invalidQrPaymentsAreRejected() {
        String alice = register(uniqueEmail());
        String bob = register(uniqueEmail());
        topUp(alice, "1000.00");
        String payload = createQr(bob, Map.of("amount", 250)).getBody().get("payload").asText();

        assertError(pay(alice, payload, "1.00", UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "QR_AMOUNT_MISMATCH");
        assertError(pay(bob, payload, null, UUID.randomUUID().toString()), HttpStatus.BAD_REQUEST, "SELF_TRANSFER");
        String tampered = payload.substring(0, payload.length() - 2) + (payload.endsWith("AA") ? "BB" : "AA");
        assertError(pay(alice, tampered, null, UUID.randomUUID().toString()), HttpStatus.BAD_REQUEST, "INVALID_QR");
        assertError(createQr(bob, Map.of("validityMinutes", 5000)), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        assertThat(balance(alice)).isEqualByComparingTo("1000.00");
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<JsonNode> createQr(String token, Map<String, Object> body) {
        return post("/api/qr", token, body, null);
    }

    private ResponseEntity<JsonNode> pay(String token, String payload, String amount, String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("payload", payload);
        if (amount != null) {
            body.put("amount", new BigDecimal(amount));
        }
        return post("/api/qr/pay", token, body, key);
    }

    private String register(String email) {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/register",
                Map.of("email", email, "password", "Secret123", "fullName", "Test User"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("accessToken").asText();
    }

    private void topUp(String token, String amount) {
        ResponseEntity<JsonNode> response = post("/api/wallets/me/top-ups", token,
                Map.of("amount", new BigDecimal(amount)), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private BigDecimal balance(String token) {
        return rest.exchange("/api/wallets/me", HttpMethod.GET, new HttpEntity<>(headers(token, null)), JsonNode.class)
                .getBody().get("balance").decimalValue();
    }

    private ResponseEntity<JsonNode> post(String path, String token, Object body, String idempotencyKey) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token, idempotencyKey)),
                JsonNode.class);
    }

    private static HttpHeaders headers(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
    }

    private static String uniqueEmail() {
        return "qr-" + UUID.randomUUID() + "@test.dev";
    }

    /** Decodes a PNG the way a phone camera would. */
    private static String scan(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels)));
        return new QRCodeReader().decode(bitmap).getText();
    }
}
