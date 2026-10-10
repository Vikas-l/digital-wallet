package com.digitalwallet.rewards.reward;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RewardRepository extends JpaRepository<Reward, UUID> {

    boolean existsBySourceTransactionId(UUID sourceTransactionId);

    long countByUserIdAndRuleCodeAndCreatedAtGreaterThanEqual(Long userId, String ruleCode, Instant from);

    Page<Reward> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    @Query("select coalesce(sum(r.rewardAmount), 0) from Reward r where r.userId = :userId")
    BigDecimal totalForUser(@Param("userId") Long userId);
}
