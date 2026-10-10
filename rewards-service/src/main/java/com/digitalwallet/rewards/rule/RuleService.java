package com.digitalwallet.rewards.rule;

import java.util.List;

import com.digitalwallet.rewards.error.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RuleService {

    private final RewardRuleRepository ruleRepository;
    private final RuleCache ruleCache;

    public RuleService(RewardRuleRepository ruleRepository, RuleCache ruleCache) {
        this.ruleRepository = ruleRepository;
        this.ruleCache = ruleCache;
    }

    @Transactional(readOnly = true)
    public List<RuleResponse> list() {
        return ruleRepository.findAllByOrderByPriorityDescIdAsc().stream().map(RuleResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public RuleResponse get(Long id) {
        return RuleResponse.of(find(id));
    }

    @Transactional
    public RuleResponse create(RuleRequest request) {
        if (ruleRepository.existsByCode(request.code())) {
            throw ApiException.conflict("RULE_CODE_EXISTS", "A rule with code " + request.code() + " already exists");
        }
        RewardRule rule = ruleRepository.save(new RewardRule(request));
        ruleCache.evictAfterCommit();
        return RuleResponse.of(rule);
    }

    @Transactional
    public RuleResponse update(Long id, RuleRequest request) {
        RewardRule rule = find(id);
        if (!rule.getCode().equals(request.code())) {
            throw ApiException.badRequest("RULE_CODE_IMMUTABLE", "The code of an existing rule cannot be changed");
        }
        rule.apply(request);
        ruleCache.evictAfterCommit();
        return RuleResponse.of(ruleRepository.saveAndFlush(rule));
    }

    @Transactional
    public RuleResponse setActive(Long id, boolean active) {
        RewardRule rule = find(id);
        rule.setActive(active);
        ruleCache.evictAfterCommit();
        return RuleResponse.of(ruleRepository.saveAndFlush(rule));
    }

    private RewardRule find(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("RULE_NOT_FOUND", "Rule " + id + " not found"));
    }
}
