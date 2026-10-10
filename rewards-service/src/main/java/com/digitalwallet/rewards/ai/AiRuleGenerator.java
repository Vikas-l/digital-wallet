package com.digitalwallet.rewards.ai;

import java.util.Set;
import java.util.stream.Collectors;

import com.digitalwallet.rewards.config.RewardsProperties;
import com.digitalwallet.rewards.error.ApiException;
import com.digitalwallet.rewards.rule.RuleRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Turns a plain-English campaign description into a {@link RuleRequest} draft.
 * The LLM output is never trusted: it is parsed into a typed object, validated
 * with the same constraints as a manual request, and only returned as a draft. An
 * admin reviews it and saves it through the normal create endpoint (human in the loop).
 */
@Service
public class AiRuleGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiRuleGenerator.class);

    static final String SYSTEM_PROMPT = """
            You convert a business description of a cashback campaign into ONE reward rule for a
            digital wallet. Amounts are in INR. Respond only with the requested JSON object.
            Field rules:
            - code: UPPER_SNAKE_CASE, 3-50 chars, starts with a letter, describes the campaign.
            - name: short human title (max 100 chars). description: one sentence (max 500 chars).
            - transactionType: TRANSFER (sending money to someone) or TOP_UP (adding money to the wallet).
            - minAmount: minimum transaction amount that qualifies; 0 if not stated.
            - cashbackPercent: percentage between 0.01 and 100, max 2 decimals.
            - maxReward: maximum cashback per transaction. If not stated, use 100.
            - dailyLimitPerUser: how many times per day one user can earn it; null if unlimited.
            - priority: 0-1000, higher wins when several rules match; default 100.
            - validFrom / validTo: ISO-8601 UTC instants, or null when not stated.
            Never invent conditions that are not in the description.
            """;

    private final ChatClient chatClient;
    private final Validator validator;
    private final boolean enabled;

    public AiRuleGenerator(ChatClient.Builder chatClientBuilder, Validator validator, RewardsProperties properties) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
        this.validator = validator;
        this.enabled = properties.ai().enabled();
    }

    public RuleRequest generate(String instruction) {
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED",
                    "AI rule generation is disabled. Set AI_ENABLED=true and OPENAI_API_KEY to enable it.");
        }
        RuleRequest draft;
        try {
            draft = chatClient.prompt().user(instruction).call().entity(RuleRequest.class);
        } catch (RuntimeException e) {
            log.warn("LLM call failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE",
                    "The AI provider did not return a usable answer, please try again");
        }
        if (draft == null) {
            throw ApiException.unprocessable("AI_EMPTY_RESPONSE", "The AI could not derive a rule from that text");
        }
        Set<ConstraintViolation<RuleRequest>> violations = validator.validate(draft);
        if (!violations.isEmpty()) {
            String problems = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw ApiException.unprocessable("AI_INVALID_RULE", "The AI produced an invalid rule: " + problems);
        }
        return draft;
    }
}
