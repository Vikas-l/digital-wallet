package com.digitalwallet.rewards.rule;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.digitalwallet.common.events.TransactionType;
import com.digitalwallet.rewards.config.RewardsProperties;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cache-aside for active rules in Redis. Rules are read for every transaction event
 * but change rarely, so the hot path avoids the database. The cache is shared by
 * all instances; an admin change evicts it (after commit) so every instance sees
 * the new rules on its next read. The TTL bounds staleness if an eviction is lost.
 */
@Component
public class RuleCache {

    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);
    private static final String KEY_PREFIX = "digitalwallet:rules:active:";
    private static final TypeReference<List<RuleSnapshot>> LIST_TYPE = new TypeReference<>() {
    };

    private final RedissonClient redisson;
    private final RewardRuleRepository ruleRepository;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RuleCache(RedissonClient redisson, RewardRuleRepository ruleRepository, ObjectMapper objectMapper,
            RewardsProperties properties) {
        this.redisson = redisson;
        this.ruleRepository = ruleRepository;
        this.objectMapper = objectMapper;
        this.ttl = properties.ruleCacheTtl();
    }

    public List<RuleSnapshot> activeRules(TransactionType type) {
        RBucket<String> bucket = bucket(type);
        try {
            String cached = bucket.get();
            if (cached != null) {
                return objectMapper.readValue(cached, LIST_TYPE);
            }
        } catch (JsonProcessingException | RuntimeException e) {
            // a cache problem must never break reward processing: fall back to the database
            log.warn("Rule cache read failed for {}, loading from database: {}", type, e.getMessage());
        }
        List<RuleSnapshot> rules = ruleRepository.findByActiveTrueAndTransactionType(type).stream()
                .map(RewardRule::toSnapshot)
                .toList();
        try {
            bucket.set(objectMapper.writeValueAsString(rules), ttl);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Rule cache write failed for {}: {}", type, e.getMessage());
        }
        return rules;
    }

    /** Evicts after the surrounding transaction commits, so no reader can re-cache the old rows. */
    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictAll();
                }
            });
        } else {
            evictAll();
        }
    }

    public void evictAll() {
        for (TransactionType type : TransactionType.values()) {
            bucket(type).delete();
        }
    }

    private RBucket<String> bucket(TransactionType type) {
        return redisson.getBucket(KEY_PREFIX + type.name(), StringCodec.INSTANCE);
    }
}
