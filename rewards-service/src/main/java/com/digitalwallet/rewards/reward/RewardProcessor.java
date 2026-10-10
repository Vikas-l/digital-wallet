package com.digitalwallet.rewards.reward;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.digitalwallet.common.events.RewardGrantedEvent;
import com.digitalwallet.common.events.Topics;
import com.digitalwallet.common.events.TransactionCompletedEvent;
import com.digitalwallet.common.events.TransactionType;
import com.digitalwallet.rewards.config.RewardsProperties;
import com.digitalwallet.rewards.messaging.ProcessedEvent;
import com.digitalwallet.rewards.messaging.ProcessedEventRepository;
import com.digitalwallet.rewards.outbox.OutboxWriter;
import com.digitalwallet.rewards.rule.RuleCache;
import com.digitalwallet.rewards.rule.RuleEngine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RewardProcessor {

    private static final Logger log = LoggerFactory.getLogger(RewardProcessor.class);

    private final ProcessedEventRepository processedEventRepository;
    private final RewardRepository rewardRepository;
    private final RuleCache ruleCache;
    private final RuleEngine ruleEngine;
    private final OutboxWriter outboxWriter;
    private final Leaderboard leaderboard;
    private final ZoneId zone;

    public RewardProcessor(ProcessedEventRepository processedEventRepository, RewardRepository rewardRepository,
            RuleCache ruleCache, RuleEngine ruleEngine, OutboxWriter outboxWriter, Leaderboard leaderboard,
            RewardsProperties properties) {
        this.processedEventRepository = processedEventRepository;
        this.rewardRepository = rewardRepository;
        this.ruleCache = ruleCache;
        this.ruleEngine = ruleEngine;
        this.outboxWriter = outboxWriter;
        this.leaderboard = leaderboard;
        this.zone = properties.businessZone();
    }

    /**
     * Idempotent: the processed-event marker, the reward row and the outbox event
     * commit together. Events are keyed by user id, so one user's events are handled
     * sequentially on one partition, and the daily-limit count cannot race.
     */
    @Transactional
    public Optional<Reward> process(TransactionCompletedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Skipping duplicate event {}", event.eventId());
            return Optional.empty();
        }
        processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));

        if (event.type() == TransactionType.REWARD
                || rewardRepository.existsBySourceTransactionId(event.transactionId())) {
            return Optional.empty();
        }

        Instant startOfDay = event.occurredAt().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        return ruleEngine.evaluate(event, ruleCache.activeRules(event.type()),
                        ruleCode -> rewardRepository.countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(
                                event.userId(), ruleCode, startOfDay))
                .map(decision -> grant(event, decision));
    }

    private Reward grant(TransactionCompletedEvent event, RuleEngine.Decision decision) {
        Reward reward = rewardRepository.save(new Reward(event.userId(), event.transactionId(),
                decision.rule().code(), event.amount(), decision.rewardAmount(), event.occurredAt()));

        UUID eventId = UUID.randomUUID();
        RewardGrantedEvent granted = new RewardGrantedEvent(eventId, reward.getId(), event.transactionId(),
                event.userId(), reward.getRewardAmount(), reward.getRuleCode(), Instant.now());
        outboxWriter.append(eventId, "Reward", reward.getId().toString(), Topics.REWARD_GRANTED,
                String.valueOf(event.userId()), granted);
        leaderboard.recordAfterCommit(event.userId(), reward.getRewardAmount(), reward.getCreatedAt());

        log.info("Granted {} cashback to user {} for transaction {} via {}", reward.getRewardAmount(),
                event.userId(), event.transactionId(), reward.getRuleCode());
        return reward;
    }
}
