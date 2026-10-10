package com.digitalwallet.rewards.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;

import com.digitalwallet.common.events.TransactionType;
import com.digitalwallet.rewards.config.RewardsProperties;
import com.digitalwallet.rewards.error.ApiException;
import com.digitalwallet.rewards.rule.RuleRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;

class AiRuleGeneratorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

    @Test
    void returnsValidatedDraftFromModel() {
        RuleRequest draft = new RuleRequest("DIWALI_TRANSFER_5", "Diwali 5% cashback", "5% on transfers above 2000",
                TransactionType.TRANSFER, new BigDecimal("2000"), new BigDecimal("5"), new BigDecimal("150"),
                2, 200, null, null);
        when(chatClient.prompt().user(anyString()).call().entity(RuleRequest.class)).thenReturn(draft);

        assertThat(generator(true).generate("5% cashback on transfers above 2000, max 150, twice a day"))
                .isEqualTo(draft);
    }

    @Test
    void rejectsInvalidModelOutput() {
        RuleRequest invalid = new RuleRequest("bad code", "", null, TransactionType.REWARD,
                new BigDecimal("-1"), new BigDecimal("250"), new BigDecimal("0"), 0, 5000, null, null);
        when(chatClient.prompt().user(anyString()).call().entity(RuleRequest.class)).thenReturn(invalid);

        assertThatThrownBy(() -> generator(true).generate("nonsense"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getCode()).isEqualTo("AI_INVALID_RULE");
                    assertThat(e.getMessage()).contains("cashbackPercent", "code", "rewardableType");
                });
    }

    @Test
    void mapsProviderFailureToBadGateway() {
        when(chatClient.prompt().user(anyString()).call().entity(RuleRequest.class))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> generator(true).generate("anything"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void disabledFeatureReturnsServiceUnavailable() {
        assertThatThrownBy(() -> generator(false).generate("anything"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private AiRuleGenerator generator(boolean enabled) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        RewardsProperties properties = new RewardsProperties(
                new RewardsProperties.Jwt("x".repeat(32), "digitalwallet"), "redis://localhost:6379",
                ZoneId.of("Asia/Kolkata"), Duration.ofMinutes(10), new RewardsProperties.Ai(enabled),
                new RewardsProperties.Outbox(100), java.util.List.of("http://localhost:8081"));
        return new AiRuleGenerator(builder, validator, properties);
    }
}
