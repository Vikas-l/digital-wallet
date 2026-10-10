package com.digitalwallet.rewards.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.digitalwallet.common.events.TransactionCompletedEvent;
import com.digitalwallet.common.events.TransactionType;

import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    private final RuleEngine engine = new RuleEngine();

    @Test
    void appliesPercentageCashback() {
        var decision = engine.evaluate(transfer("1000.00"), List.of(rule("CB2", 2, "500", "2.00", "100", null)),
                code -> 0);
        assertThat(decision).hasValueSatisfying(d -> {
            assertThat(d.rule().code()).isEqualTo("CB2");
            assertThat(d.rewardAmount()).isEqualByComparingTo("20.00");
        });
    }

    @Test
    void capsRewardAtMaxReward() {
        var decision = engine.evaluate(transfer("50000.00"), List.of(rule("CB2", 1, "500", "2.00", "100", null)),
                code -> 0);
        assertThat(decision.orElseThrow().rewardAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void roundsDownToThePaisa() {
        var decision = engine.evaluate(transfer("999.99"), List.of(rule("CB", 1, "0", "1.50", "100", null)),
                code -> 0);
        // 999.99 * 1.5% = 14.99985 -> 14.99 (never over-pay)
        assertThat(decision.orElseThrow().rewardAmount()).isEqualByComparingTo("14.99");
    }

    @Test
    void ignoresTransactionsBelowMinimum() {
        assertThat(engine.evaluate(transfer("499.99"), List.of(rule("CB2", 1, "500", "2.00", "100", null)),
                code -> 0)).isEmpty();
    }

    @Test
    void ignoresRulesForOtherTransactionTypes() {
        RuleSnapshot topUpRule = new RuleSnapshot(1L, "TOPUP", TransactionType.TOP_UP, BigDecimal.ZERO,
                new BigDecimal("1"), new BigDecimal("50"), null, 1, null, null);
        assertThat(engine.evaluate(transfer("1000"), List.of(topUpRule), code -> 0)).isEmpty();
    }

    @Test
    void highestPriorityMatchingRuleWins() {
        List<RuleSnapshot> rules = List.of(
                rule("LOW", 10, "0", "1.00", "100", null),
                rule("HIGH", 90, "0", "5.00", "100", null));
        assertThat(engine.evaluate(transfer("1000"), rules, code -> 0).orElseThrow().rule().code())
                .isEqualTo("HIGH");
    }

    @Test
    void fallsBackToNextRuleWhenDailyLimitIsReached() {
        List<RuleSnapshot> rules = List.of(
                rule("LIMITED", 90, "0", "5.00", "100", 3),
                rule("FALLBACK", 10, "0", "1.00", "100", null));
        Map<String, Long> usedToday = Map.of("LIMITED", 3L);

        var decision = engine.evaluate(transfer("1000"), rules, code -> usedToday.getOrDefault(code, 0L));

        assertThat(decision.orElseThrow().rule().code()).isEqualTo("FALLBACK");
    }

    @Test
    void respectsValidityWindow() {
        RuleSnapshot expired = new RuleSnapshot(1L, "OLD", TransactionType.TRANSFER, BigDecimal.ZERO,
                new BigDecimal("2"), new BigDecimal("100"), null, 1, null, NOW.minusSeconds(1));
        RuleSnapshot future = new RuleSnapshot(2L, "NEW", TransactionType.TRANSFER, BigDecimal.ZERO,
                new BigDecimal("2"), new BigDecimal("100"), null, 1, NOW.plusSeconds(1), null);
        assertThat(engine.evaluate(transfer("1000"), List.of(expired, future), code -> 0)).isEmpty();
    }

    @Test
    void skipsRuleWhoseRewardRoundsToZero() {
        assertThat(engine.evaluate(transfer("0.40"), List.of(rule("TINY", 1, "0", "1.00", "100", null)),
                code -> 0)).isEmpty();
    }

    private static TransactionCompletedEvent transfer(String amount) {
        return new TransactionCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), TransactionType.TRANSFER, 7L,
                new BigDecimal(amount), "INR", NOW);
    }

    private static RuleSnapshot rule(String code, int priority, String min, String percent, String max,
            Integer dailyLimit) {
        return new RuleSnapshot((long) priority, code, TransactionType.TRANSFER, new BigDecimal(min),
                new BigDecimal(percent), new BigDecimal(max), dailyLimit, priority, null, null);
    }
}
