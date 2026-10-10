package com.digitalwallet.rewards.rule;

import java.util.List;

import com.digitalwallet.common.events.TransactionType;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardRuleRepository extends JpaRepository<RewardRule, Long> {

    List<RewardRule> findByActiveTrueAndTransactionType(TransactionType transactionType);

    boolean existsByCode(String code);

    List<RewardRule> findAllByOrderByPriorityDescIdAsc();
}
