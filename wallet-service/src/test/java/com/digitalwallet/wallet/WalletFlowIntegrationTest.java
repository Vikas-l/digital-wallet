package com.digitalwallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.digitalwallet.common.events.RewardGrantedEvent;
import com.digitalwallet.common.events.Topics;
import com.digitalwallet.wallet.wallet.LedgerEntryRepository;
import com.digitalwallet.wallet.wallet.WalletRepository;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

class WalletFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    // ---------------------------------------------------------------- auth

    @Test
    void registerThenLoginReturnsJwtAndOpensEmptyWallet() {
        String email = uniqueEmail();
        register(email);

        ResponseEntity<JsonNode> login = rest.postForEntity("/api/auth/login",
                Map.of("email", email.toUpperCase(), "password", "Secret123"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = login.getBody().get("accessToken").asText();

        JsonNode wallet = get("/api/wallets/me", token).getBody();
        assertThat(wallet.get("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(wallet.get("currency").asText()).isEqualTo("INR");
    }

    @Test
    void duplicateEmailAndBadCredentialsAreRejected() {
        String email = uniqueEmail();
        register(email);

        ResponseEntity<JsonNode> duplicate = rest.postForEntity("/api/auth/register",
                Map.of("email", email, "password", "Secret123", "fullName", "Again"), JsonNode.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code").asText()).isEqualTo("EMAIL_ALREADY_REGISTERED");

        ResponseEntity<JsonNode> badLogin = rest.postForEntity("/api/auth/login",
                Map.of("email", email, "password", "Wrong1234"), JsonNode.class);
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void webUiIsServedWithoutLogin() {
        ResponseEntity<String> page = rest.getForEntity("/", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("<title>Digital Wallet</title>");
        for (String asset : List.of("/app.js", "/styles.css", "/vendor/jsQR.js")) {
            assertThat(rest.getForEntity(asset, String.class).getStatusCode()).as(asset).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestsWithoutTokenAreUnauthorized() {
        ResponseEntity<String> response = rest.getForEntity("/api/wallets/me", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheToken() {
        String token = register(uniqueEmail());
        assertThat(get("/api/wallets/me", token).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Void> logout = rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(headers(token, null)), Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/api/wallets/me", token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bootstrapAdminCanLogIn() {
        ResponseEntity<JsonNode> login = rest.postForEntity("/api/auth/login",
                Map.of("email", "admin@digitalwallet.dev", "password", "Admin@12345"), JsonNode.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("role").asText()).isEqualTo("ADMIN");
    }

    // ---------------------------------------------------------------- top-up & idempotency

    @Test
    void topUpWithSameIdempotencyKeyIsAppliedOnce() {
        String token = register(uniqueEmail());
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = topUp(token, "500.00", key);
        ResponseEntity<JsonNode> retry = topUp(token, "500.00", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("transactionId")).isEqualTo(first.getBody().get("transactionId"));
        assertThat(balance(token)).isEqualByComparingTo("500.00");
    }

    @Test
    void reusingIdempotencyKeyForDifferentRequestIsRejected() {
        String token = register(uniqueEmail());
        String key = UUID.randomUUID().toString();
        topUp(token, "100.00", key);

        ResponseEntity<JsonNode> reused = topUp(token, "999.00", key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(token)).isEqualByComparingTo("100.00");
    }

    @Test
    void missingOrMalformedIdempotencyKeyIsRejected() {
        String token = register(uniqueEmail());
        assertThat(topUp(token, "10.00", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<JsonNode> malformed = topUp(token, "10.00", "bad key!");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody().get("code").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    void invalidAmountsFailValidation() {
        String token = register(uniqueEmail());
        for (String amount : List.of("0", "-5", "10.123", "100000.01")) {
            ResponseEntity<JsonNode> response = topUp(token, amount, UUID.randomUUID().toString());
            assertThat(response.getStatusCode()).as("amount %s", amount).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        }
    }

    // ---------------------------------------------------------------- transfers

    @Test
    void transferMovesMoneyAndWritesBalancedLedger() {
        String alice = register(uniqueEmail());
        String bobEmail = uniqueEmail();
        String bob = register(bobEmail);
        topUp(alice, "1000.00", UUID.randomUUID().toString());

        ResponseEntity<JsonNode> transfer = transfer(alice, bobEmail, "300.00", UUID.randomUUID().toString());

        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(transfer.getBody().get("type").asText()).isEqualTo("TRANSFER");
        assertThat(balance(alice)).isEqualByComparingTo("700.00");
        assertThat(balance(bob)).isEqualByComparingTo("300.00");

        JsonNode statement = get("/api/wallets/me/statement", alice).getBody();
        assertThat(statement.get("totalElements").asLong()).isEqualTo(2);
        JsonNode latest = statement.get("content").get(0);
        assertThat(latest.get("direction").asText()).isEqualTo("DEBIT");
        assertThat(latest.get("balanceAfter").decimalValue()).isEqualByComparingTo("700.00");

        // reconciliation: the ledger always adds up to the stored balance
        for (String token : List.of(alice, bob)) {
            Long walletId = get("/api/wallets/me", token).getBody().get("walletId").asLong();
            assertThat(ledgerEntryRepository.netBalance(walletId)).isEqualByComparingTo(balance(token));
        }
    }

    @Test
    void invalidTransfersAreRejectedWithClearCodes() {
        String aliceEmail = uniqueEmail();
        String alice = register(aliceEmail);
        String bobEmail = uniqueEmail();
        register(bobEmail);
        topUp(alice, "50.00", UUID.randomUUID().toString());

        assertError(transfer(alice, bobEmail, "50.01", UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS");
        assertError(transfer(alice, aliceEmail, "10.00", UUID.randomUUID().toString()),
                HttpStatus.BAD_REQUEST, "SELF_TRANSFER");
        assertError(transfer(alice, "nobody-" + aliceEmail, "10.00", UUID.randomUUID().toString()),
                HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND");
        assertThat(balance(alice)).isEqualByComparingTo("50.00");
    }

    @Test
    void concurrentTransfersNeverOverdrawTheWallet() throws Exception {
        String alice = register(uniqueEmail());
        String bobEmail = uniqueEmail();
        String bob = register(bobEmail);
        topUp(alice, "100.00", UUID.randomUUID().toString());

        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(transfer(alice, bobEmail, "10.00", UUID.randomUUID().toString())
                        .getStatusCode().value());
            }));
        }
        start.countDown();
        long succeeded = 0;
        long rejected = 0;
        for (Future<HttpStatus> result : results) {
            HttpStatus status = result.get();
            if (status == HttpStatus.CREATED) {
                succeeded++;
            } else if (status == HttpStatus.UNPROCESSABLE_ENTITY) {
                rejected++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(10);
        assertThat(rejected).isEqualTo(10);
        assertThat(balance(alice)).isEqualByComparingTo("0.00");
        assertThat(balance(bob)).isEqualByComparingTo("100.00");
    }

    // ---------------------------------------------------------------- events

    @Test
    void completedTransactionIsPublishedToKafkaViaOutbox() throws Exception {
        String token = register(uniqueEmail());
        Long userId = get("/api/wallets/me", token).getBody().get("userId").asLong();
        String transactionId = topUp(token, "250.00", UUID.randomUUID().toString())
                .getBody().get("transactionId").asText();

        ConsumerRecord<String, String> record = awaitRecord(Topics.TRANSACTION_COMPLETED, transactionId);

        assertThat(record.key()).isEqualTo(String.valueOf(userId));
        JsonNode event = objectMapper.readTree(record.value());
        assertThat(event.get("type").asText()).isEqualTo("TOP_UP");
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(event.get("userId").asLong()).isEqualTo(userId);
    }

    @Test
    void rewardEventIsCreditedExactlyOnceEvenIfDeliveredTwice() throws Exception {
        String token = register(uniqueEmail());
        Long userId = get("/api/wallets/me", token).getBody().get("userId").asLong();
        RewardGrantedEvent event = new RewardGrantedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                userId, new BigDecimal("25.50"), "TEST_RULE", Instant.now());
        String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(Topics.REWARD_GRANTED, String.valueOf(userId), payload).get();
        kafkaTemplate.send(Topics.REWARD_GRANTED, String.valueOf(userId), payload).get();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(balance(token)).isEqualByComparingTo("25.50"));
        // give a duplicate delivery time to (wrongly) apply, then check it did not
        Thread.sleep(2000);
        assertThat(balance(token)).isEqualByComparingTo("25.50");
        JsonNode entry = get("/api/wallets/me/statement", token).getBody().get("content").get(0);
        assertThat(entry.get("type").asText()).isEqualTo("REWARD");
    }

    // ---------------------------------------------------------------- helpers

    private String register(String email) {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/register",
                Map.of("email", email, "password", "Secret123", "fullName", "Test User"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> topUp(String token, String amount, String key) {
        return rest.exchange("/api/wallets/me/top-ups", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", new BigDecimal(amount)), headers(token, key)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> transfer(String token, String to, String amount, String key) {
        return rest.exchange("/api/wallets/me/transfers", HttpMethod.POST,
                new HttpEntity<>(Map.of("recipientEmail", to, "amount", new BigDecimal(amount)), headers(token, key)),
                JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token, null)), JsonNode.class);
    }

    private BigDecimal balance(String token) {
        return get("/api/wallets/me", token).getBody().get("balance").decimalValue();
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
        return "user-" + UUID.randomUUID() + "@test.dev";
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, String contains) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(contains)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record containing " + contains + " on " + topic);
    }
}
