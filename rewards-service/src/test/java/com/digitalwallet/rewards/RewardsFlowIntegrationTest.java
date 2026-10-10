package com.digitalwallet.rewards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.digitalwallet.common.events.Topics;
import com.digitalwallet.common.events.TransactionCompletedEvent;
import com.digitalwallet.common.events.TransactionType;
import com.digitalwallet.rewards.messaging.ProcessedEventRepository;
import com.digitalwallet.rewards.reward.Reward;
import com.digitalwallet.rewards.reward.RewardRepository;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class RewardsFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String JWT_SECRET = "local-dev-secret-change-me-0123456789abcdef";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RewardRepository rewardRepository;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    // ---------------------------------------------------------------- event processing

    @Test
    void qualifyingTransferEarnsCashbackAndPublishesRewardGranted() throws Exception {
        long userId = randomUserId();
        TransactionCompletedEvent event = event(userId, TransactionType.TRANSFER, "1000.00");

        publish(event);

        Reward reward = awaitSingleReward(userId);
        assertThat(reward.getRuleCode()).isEqualTo("TRANSFER_CASHBACK_2");
        assertThat(reward.getRewardAmount()).isEqualByComparingTo("20.00");
        assertThat(reward.getSourceTransactionId()).isEqualTo(event.transactionId());

        ConsumerRecord<String, String> record = awaitRecord(Topics.REWARD_GRANTED, reward.getId().toString());
        assertThat(record.key()).isEqualTo(String.valueOf(userId));
        JsonNode granted = objectMapper.readTree(record.value());
        assertThat(granted.get("amount").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(granted.get("sourceTransactionId").asText()).isEqualTo(event.transactionId().toString());
    }

    @Test
    void duplicateEventProducesOnlyOneReward() throws Exception {
        long userId = randomUserId();
        TransactionCompletedEvent event = event(userId, TransactionType.TRANSFER, "800.00");

        publish(event);
        publish(event);

        awaitSingleReward(userId);
        Thread.sleep(2000);
        assertThat(rewardsOf(userId)).hasSize(1);
    }

    @Test
    void transactionBelowMinimumEarnsNothing() throws Exception {
        long userId = randomUserId();
        TransactionCompletedEvent event = event(userId, TransactionType.TRANSFER, "499.99");

        publish(event);

        awaitProcessed(event);
        assertThat(rewardsOf(userId)).isEmpty();
    }

    @Test
    void dailyLimitIsEnforcedPerUser() throws Exception {
        long userId = randomUserId();
        // TOPUP_BONUS_1 pays at most once per day
        TransactionCompletedEvent first = event(userId, TransactionType.TOP_UP, "2000.00");
        TransactionCompletedEvent second = event(userId, TransactionType.TOP_UP, "3000.00");

        publish(first);
        publish(second);

        awaitProcessed(second);
        List<Reward> rewards = rewardsOf(userId);
        assertThat(rewards).hasSize(1);
        assertThat(rewards.get(0).getRewardAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void rewardTransactionsNeverEarnRewards() throws Exception {
        long userId = randomUserId();
        TransactionCompletedEvent event = event(userId, TransactionType.REWARD, "5000.00");

        publish(event);

        awaitProcessed(event);
        assertThat(rewardsOf(userId)).isEmpty();
    }

    @Test
    void userCanSeeOwnRewardsSummaryAndLeaderboard() throws Exception {
        long userId = randomUserId();
        publish(event(userId, TransactionType.TRANSFER, "99999.00")); // capped at 100.00
        awaitSingleReward(userId);

        String token = token(userId, "USER");
        JsonNode summary = get("/api/rewards/me/summary", token).getBody();
        assertThat(summary.get("rewardCount").asLong()).isEqualTo(1);
        assertThat(summary.get("totalCashback").decimalValue()).isEqualByComparingTo("100.00");

        JsonNode page = get("/api/rewards/me", token).getBody();
        assertThat(page.get("content").get(0).get("ruleCode").asText()).isEqualTo("TRANSFER_CASHBACK_2");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode board = get("/api/rewards/leaderboard?limit=100", token).getBody();
            assertThat(board.get("entries").findValuesAsText("userId")).contains(String.valueOf(userId));
        });
    }

    // ---------------------------------------------------------------- admin API

    @Test
    void rulesApiIsAdminOnly() {
        assertThat(rest.getForEntity("/api/rules", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/rules", token(1L, "USER")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> rules = get("/api/rules", token(1L, "ADMIN"));
        assertThat(rules.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rules.getBody().findValuesAsText("code")).contains("TRANSFER_CASHBACK_2", "TOPUP_BONUS_1");
    }

    @Test
    void newRuleTakesEffectImmediatelyBecauseCacheIsEvicted() throws Exception {
        String admin = token(1L, "ADMIN");
        // warm the Redis rule cache for TRANSFER
        long warmUser = randomUserId();
        publish(event(warmUser, TransactionType.TRANSFER, "600.00"));
        awaitSingleReward(warmUser);

        String code = "BIG_SPENDER_" + ThreadLocalRandom.current().nextInt(1_000_000);
        ResponseEntity<JsonNode> created = post("/api/rules", admin, Map.of(
                "code", code, "name", "Big spender", "transactionType", "TRANSFER",
                "minAmount", 90000, "cashbackPercent", 0.5, "maxReward", 1000,
                "priority", 999));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("active").asBoolean()).isTrue();

        long userId = randomUserId();
        publish(event(userId, TransactionType.TRANSFER, "95000.00"));
        Reward reward = awaitSingleReward(userId);
        assertThat(reward.getRuleCode()).isEqualTo(code);
        assertThat(reward.getRewardAmount()).isEqualByComparingTo("475.00");

        // deactivate so other tests are not affected
        Long id = created.getBody().get("id").asLong();
        ResponseEntity<JsonNode> deactivated = rest.exchange("/api/rules/" + id + "/status", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("active", false), headers(admin)), JsonNode.class);
        assertThat(deactivated.getBody().get("active").asBoolean()).isFalse();
    }

    @Test
    void invalidOrDuplicateRulesAreRejected() {
        String admin = token(1L, "ADMIN");

        ResponseEntity<JsonNode> invalid = post("/api/rules", admin, Map.of(
                "code", "bad code", "name", "x", "transactionType", "REWARD",
                "minAmount", -1, "cashbackPercent", 150, "maxReward", 0, "priority", 1));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().get("errors").fieldNames()).toIterable()
                .contains("code", "cashbackPercent", "minAmount", "maxReward", "rewardableType");

        ResponseEntity<JsonNode> duplicate = post("/api/rules", admin, Map.of(
                "code", "TRANSFER_CASHBACK_2", "name", "dup", "transactionType", "TRANSFER",
                "minAmount", 1, "cashbackPercent", 1, "maxReward", 1, "priority", 1));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code").asText()).isEqualTo("RULE_CODE_EXISTS");
    }

    @Test
    void corsAllowsOnlyTheWebUiOrigin() {
        assertThat(preflight("http://localhost:8081").getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:8081");

        ResponseEntity<String> foreign = preflight("https://evil.example");
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreign.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private ResponseEntity<String> preflight(String origin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.setAccessControlRequestHeaders(List.of("authorization"));
        return rest.exchange("/api/rewards/me", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    @Test
    void aiDraftReportsWhenDisabled() {
        ResponseEntity<JsonNode> response = post("/api/rules/ai-draft", token(1L, "ADMIN"),
                Map.of("instruction", "3% cashback on transfers over 1000"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("code").asText()).isEqualTo("AI_DISABLED");
    }

    // ---------------------------------------------------------------- helpers

    private void publish(TransactionCompletedEvent event) throws Exception {
        kafkaTemplate.send(Topics.TRANSACTION_COMPLETED, String.valueOf(event.userId()),
                objectMapper.writeValueAsString(event)).get();
    }

    private Reward awaitSingleReward(long userId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> rewardsOf(userId).size() == 1);
        return rewardsOf(userId).get(0);
    }

    private void awaitProcessed(TransactionCompletedEvent event) {
        await().atMost(Duration.ofSeconds(30)).until(() -> processedEventRepository.existsById(event.eventId()));
    }

    private List<Reward> rewardsOf(long userId) {
        return rewardRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged()).getContent();
    }

    private static TransactionCompletedEvent event(long userId, TransactionType type, String amount) {
        return new TransactionCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), type, userId,
                new BigDecimal(amount), "INR", Instant.now());
    }

    private static long randomUserId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    }

    private static String token(long userId, String role) {
        var key = new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer("digitalwallet")
                .subject(String.valueOf(userId))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600))
                .claim("roles", List.of(role))
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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
