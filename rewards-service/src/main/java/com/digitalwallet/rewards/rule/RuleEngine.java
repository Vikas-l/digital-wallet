package com.digitalwallet.rewards.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;

import com.digitalwallet.common.events.TransactionCompletedEvent;

import org.springframework.stereotype.Component;

/**
 * Pure decision logic, no I/O, so it is fully unit-testable. Given a transaction and
 * the active rules, it picks the highest-priority rule that applies and computes
 * the cashback. At most one rule pays out per transaction.
 */
@Component
public class RuleEngine {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Decision(RuleSnapshot rule, BigDecimal rewardAmount) {
    }

    /**
     * @param usedToday how many rewards the user already received today from a rule (by rule code)
     */
    public Optional<Decision> evaluate(TransactionCompletedEvent event, List<RuleSnapshot> rules,
            ToLongFunction<String> usedToday) {
        return rules.stream()
                .filter(r -> r.transactionType() == event.type())
                .filter(r -> r.isLiveAt(event.occurredAt()))
                .filter(r -> event.amount().compareTo(r.minAmount()) >= 0)
                .sorted(Comparator.comparingInt(RuleSnapshot::priority).reversed()
                        .thenComparing(RuleSnapshot::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .filter(r -> r.dailyLimitPerUser() == null || usedToday.applyAsLong(r.code()) < r.dailyLimitPerUser())
                .map(r -> new Decision(r, cashback(event.amount(), r)))
                .filter(d -> d.rewardAmount().signum() > 0)
                .findFirst();
    }

    static BigDecimal cashback(BigDecimal amount, RuleSnapshot rule) {
        // round down: never pay out more than the rule promises
        BigDecimal raw = amount.multiply(rule.cashbackPercent()).divide(HUNDRED, 2, RoundingMode.DOWN);
        return raw.min(rule.maxReward());
    }
}
